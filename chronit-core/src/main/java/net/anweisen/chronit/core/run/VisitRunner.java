package net.anweisen.chronit.core.run;

import net.anweisen.chronit.core.auth.AccountManager;
import net.anweisen.chronit.core.auth.AuthException;
import net.anweisen.chronit.core.config.AccountConfig;
import net.anweisen.chronit.core.config.ChronitConfig;
import net.anweisen.chronit.core.config.RetryConfig;
import net.anweisen.chronit.core.config.ServerConfig;
import net.anweisen.chronit.core.config.VisitConfig;
import net.anweisen.chronit.core.driver.AuthContext;
import net.anweisen.chronit.core.driver.ChatLine;
import net.anweisen.chronit.core.driver.ClientEvents;
import net.anweisen.chronit.core.driver.ClientHandle;
import net.anweisen.chronit.core.driver.ConnectRequest;
import net.anweisen.chronit.core.driver.ContainerInfo;
import net.anweisen.chronit.core.driver.DisconnectInfo;
import net.anweisen.chronit.core.driver.DriverException;
import net.anweisen.chronit.core.driver.MinecraftClientDriver;
import net.anweisen.chronit.core.driver.Phase;
import net.anweisen.chronit.core.driver.ReadyInfo;
import net.anweisen.chronit.core.driver.ResourcePackEvent;
import net.anweisen.chronit.core.driver.ServerTarget;
import net.anweisen.chronit.core.driver.SessionSettings;
import net.anweisen.chronit.core.state.RunRecord;
import net.anweisen.chronit.core.state.VisitStatus;
import net.anweisen.chronit.core.util.Durations;
import net.anweisen.chronit.core.util.Redactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Performs one server visit: join, run the command sequence, linger, leave.
 *
 * <p>Retries are handled here rather than at the job level so that a single flaky server does not
 * cost the whole schedule, and so a version rejection can be retried immediately through a
 * translation layer instead of counting as a failure.
 */
public final class VisitRunner {

  private static final Logger log = LoggerFactory.getLogger(VisitRunner.class);

  private final MinecraftClientDriver driver;
  private final AccountManager accounts;
  private final ProtocolResolver protocols;
  private final AccountLocks locks;
  private final ChronitConfig config;

  public VisitRunner(MinecraftClientDriver driver,
                     AccountManager accounts,
                     ProtocolResolver protocols,
                     AccountLocks locks,
                     ChronitConfig config) {
    this.driver = driver;
    this.accounts = accounts;
    this.protocols = protocols;
    this.locks = locks;
    this.config = config;
  }

  public RunRecord.VisitRecord run(VisitConfig visit) throws InterruptedException {
    return run(visit, null, 1);
  }

  /**
   * @param execution  the running job, so a cancel can reach the live session and the dashboard
   *                   can say what is happening. Null for an ad-hoc visit that nothing can cancel
   * @param visitIndex one-based position in the job's visit chain, reported live
   */
  public RunRecord.VisitRecord run(VisitConfig visit, JobExecution execution, int visitIndex)
      throws InterruptedException {
    Instant startedAt = Instant.now();
    ServerConfig server = config.server(visit.server()).orElseThrow();
    AccountConfig account = config.account(visit.account()).orElseThrow();

    RetryConfig retry = visit.onFail() != null
        ? visit.onFail().withFallback(config.effectiveDefaults().onFail())
        : config.effectiveDefaults().onFail();
    int attempts = Math.max(1, 1 + (retry.retries() == null ? 0 : retry.retries()));

    // The same account must never be on two servers at once: logging in again invalidates the
    // earlier session server-side, so overlapping visits would silently kick each other.
    try (AccountLocks.Lease ignored = locks.acquire(account.id())) {
      String failure = null;
      // Kept from the final attempt so a failed visit can still say how far it got.
      Duration lastTimeToReady = null;
      DisconnectInfo.Kind lastKind = DisconnectInfo.Kind.UNKNOWN;
      // How many attempts actually happened, which is not the configured maximum when a
      // fatal failure stops the loop early.
      int attemptsMade = 0;
      try {
        for (int attempt = 1; attempt <= attempts; attempt++) {
          if (attempt > 1) {
            Duration backoff = retry.backoffFor(attempt - 1);
            log.info("Retrying {} (attempt {}/{}) in {}",
                server.id(), attempt, attempts, Durations.format(backoff));
            Thread.sleep(backoff.toMillis());
          }

          attemptsMade = attempt;
          if (execution != null) {
            execution.beginVisit(visitIndex, server.id(), account.id(), attempt);
          }
          Attempt result = attemptVisit(visit, server, account, attempt, execution);

          if (execution != null && execution.isCancelled()) {
            // A stop that landed inside the attempt: the session is already closed, and
            // reporting whatever the close looked like as a failure would be a lie.
            return cancelledRecord(server, account, startedAt, attemptsMade);
          }
          if (result.success()) {
            return new RunRecord.VisitRecord(server.id(), account.id(), startedAt,
                Duration.between(startedAt, Instant.now()), true, result.detail(),
                result.actionsRun(), attempt, result.protocolVersion(),
                result.translated(), result.timeToReady(), result.kind().name(),
                VisitStatus.SUCCEEDED);
          }
          failure = result.detail();
          lastTimeToReady = result.timeToReady();
          lastKind = result.kind();
          if (result.fatal()) {
            break;
          }
        }
      } catch (InterruptedException e) {
        if (execution == null || !execution.isCancelled()) {
          throw e;
        }
        // The interrupt can arrive anywhere in here — mid-join, or in the backoff between
        // two attempts, which is where a visit against an unreachable server spends most
        // of its time. Catching it around the whole loop rather than around the attempt is
        // what stops a stop during the backoff from losing the visit entirely and
        // recording a server that was tried as one that was never reached.
        return cancelledRecord(server, account, startedAt, attemptsMade);
      }
      return new RunRecord.VisitRecord(server.id(), account.id(), startedAt,
          Duration.between(startedAt, Instant.now()), false, failure, 0, attemptsMade, -1,
          false, lastTimeToReady, lastKind.name(), VisitStatus.FAILED);
    }
  }

  /**
   * @param kind why the session ended, when it got far enough to have one. Carried explicitly so
   *             the caller can recognise a version rejection without parsing a message.
   */
  private record Attempt(boolean success, boolean fatal, String detail, int actionsRun,
                         int protocolVersion, boolean translated, DisconnectInfo.Kind kind,
                         Duration timeToReady) {

    static Attempt failed(String detail, DisconnectInfo.Kind kind) {
      return new Attempt(false, false, detail, 0, -1, false, kind, null);
    }

    /** A failure no retry can fix — bad credentials, unreachable version. */
    static Attempt fatal(String detail, DisconnectInfo.Kind kind) {
      return new Attempt(false, true, detail, 0, -1, false, kind, null);
    }

    boolean rejectedOverVersion() {
      return kind == DisconnectInfo.Kind.VERSION_MISMATCH;
    }
  }

  /** A visit an operator stopped: a real outcome, distinct from a failure. */
  private static RunRecord.VisitRecord cancelledRecord(ServerConfig server, AccountConfig account,
                                                       Instant startedAt, int attemptsMade) {
    return new RunRecord.VisitRecord(server.id(), account.id(), startedAt,
        Duration.between(startedAt, Instant.now()), false, "Stopped by an operator",
        0, Math.max(1, attemptsMade), -1, false, null,
        DisconnectInfo.Kind.CANCELLED.name(), VisitStatus.CANCELLED);
  }

  private Attempt attemptVisit(VisitConfig visit, ServerConfig server, AccountConfig account,
                               int attempt, JobExecution execution)
      throws InterruptedException {
    AuthContext auth;
    try {
      auth = accounts.resolve(account);
    } catch (AuthException e) {
      log.error("Cannot visit {}: {}", server.id(), e.getMessage());
      // Only a Microsoft outage is worth another attempt. A session that can no longer be
      // renewed will fail identically however many times it is asked, and the backoff between
      // attempts is time the rest of the job could have used.
      return e.isRetryable()
          ? Attempt.failed(e.getMessage(), DisconnectInfo.Kind.AUTH_FAILED)
          : Attempt.fatal(e.getMessage(), DisconnectInfo.Kind.AUTH_FAILED);
    }

    SessionSettings settings = SessionSettings.resolve(config, server);
    ServerTarget target = ServerTarget.of(server);

    ProtocolResolver.Plan plan;
    try {
      plan = protocols.plan(target);
    } catch (DriverException e) {
      // No route to that protocol version, so nothing about retrying would differ.
      return Attempt.fatal(e.getMessage(), DisconnectInfo.Kind.VERSION_MISMATCH);
    }

    Attempt result = connectAndRun(visit, server, target, auth, settings, plan, execution);

    // A rejection that names the protocol is worth one immediate retry through a translation
    // layer, since the alternative is failing a schedule over something we can work around.
    if (!result.success() && result.rejectedOverVersion()) {
      Optional<ProtocolResolver.Plan> fallback = protocols.replan(target);
      if (fallback.isPresent()) {
        result = connectAndRun(visit, server, target, auth, settings, fallback.get(), execution);
        if (result.success()) {
          protocols.remember(target, fallback.get());
        }
      }
    } else if (result.success() && attempt == 1) {
      protocols.remember(target, plan);
    }
    return result;
  }

  private Attempt connectAndRun(VisitConfig visit,
                                ServerConfig server,
                                ServerTarget target,
                                AuthContext auth,
                                SessionSettings settings,
                                ProtocolResolver.Plan plan,
                                JobExecution execution) throws InterruptedException {
    ChatBus chat = new ChatBus();
    ScreenBus screens = new ScreenBus();
    AtomicReference<DisconnectInfo> disconnect = new AtomicReference<>();

    log.info("Visiting {} ({}) as account '{}' using {}",
        server.id(), target.address(), auth.username(), plan.note());

    ClientHandle client;
    try {
      client = driver.connect(
          new ConnectRequest(target, auth, settings, plan.protocolVersion(), plan.translated()),
          new Events(chat, screens, server.id(), disconnect, execution));
    } catch (DriverException e) {
      return Attempt.fatal(e.getMessage(), DisconnectInfo.Kind.NETWORK);
    }

    // Registered before anything blocks, so a cancel arriving mid-join closes the socket
    // instead of waiting for the readiness timeout to expire.
    if (execution != null) {
      execution.attach(client, server.id());
    }

    try {
      ReadyInfo ready = awaitReady(client, settings.readyWhen().timeout());
      Instant readyAt = Instant.now();

      ActionRunner runner = new ActionRunner(chat, screens, settings.jitter());
      ActionRunner.Result actions = runner.run(client, visit.onReadyOrEmpty());

      if (!actions.isSuccess()) {
        client.disconnect("Command sequence failed");
        return new Attempt(false, false, actions.detail(), actions.executed(),
            ready.protocolVersion(), ready.translated(), DisconnectInfo.Kind.CLIENT_CLOSED,
            ready.timeToReady());
      }

      lingerUntil(client, readyAt, visit.stayForOrDefault());

      if (!visit.onLeaveOrEmpty().isEmpty() && client.isConnected()) {
        runner.run(client, visit.onLeaveOrEmpty());
      }

      client.disconnect("Visit complete");
      log.info("Left {} after {}", server.id(), Durations.format(Duration.between(readyAt, Instant.now())));

      return new Attempt(true, false,
          "ran " + actions.executed() + " action(s); " + plan.note(),
          actions.executed(), ready.protocolVersion(), ready.translated(),
          DisconnectInfo.Kind.CLIENT_CLOSED, ready.timeToReady());
    } catch (TimeoutException e) {
      client.disconnect("Timed out joining");
      return Attempt.failed(describeFailure(disconnect.get(), e.getMessage()),
          kindOf(disconnect.get(), DisconnectInfo.Kind.TIMEOUT));
    } catch (ExecutionException e) {
      String message = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
      return Attempt.failed(describeFailure(disconnect.get(), message),
          kindOf(disconnect.get(), DisconnectInfo.Kind.UNKNOWN));
    } finally {
      if (execution != null) {
        execution.detach();
      }
      chat.abort("session ended");
      screens.abort("session ended");
      client.close();
    }
  }

  private ReadyInfo awaitReady(ClientHandle client, Duration timeout)
      throws InterruptedException, ExecutionException, TimeoutException {
    // A little beyond the session's own deadline, so the session reports the specific reason
    // rather than this generic wait winning the race.
    Duration wait = timeout.plusSeconds(10);
    return client.whenReady().get(wait.toMillis(), TimeUnit.MILLISECONDS);
  }

  /**
   * Stays connected for the configured time.
   *
   * <p>Measured from the moment the world was reached, so {@code stayFor} is the total time
   * present on the server rather than time in addition to however long the commands took.
   */
  private void lingerUntil(ClientHandle client, Instant readyAt, Duration stayFor) throws InterruptedException {
    if (stayFor.isZero() || stayFor.isNegative()) {
      return;
    }
    Duration remaining = stayFor.minus(Duration.between(readyAt, Instant.now()));
    if (remaining.isNegative() || remaining.isZero()) {
      log.debug("Command sequence already took the whole {} stay", Durations.format(stayFor));
      return;
    }

    log.info("Staying for another {}", Durations.format(remaining));
    long deadline = System.nanoTime() + remaining.toNanos();
    while (System.nanoTime() < deadline) {
      if (!client.isConnected()) {
        log.warn("Disconnected before the stay was over");
        return;
      }
      Thread.sleep(Math.min(1000L, Math.max(1L, (deadline - System.nanoTime()) / 1_000_000L)));
    }
  }

  private static String describeFailure(DisconnectInfo disconnect, String fallback) {
    return disconnect != null ? disconnect.describe() : String.valueOf(fallback);
  }

  private static DisconnectInfo.Kind kindOf(DisconnectInfo disconnect, DisconnectInfo.Kind fallback) {
    return disconnect != null ? disconnect.kind() : fallback;
  }

  /** Bridges driver callbacks into the chat bus, the log, and the live job state. */
  private record Events(ChatBus chat,
                        ScreenBus screens,
                        String serverId,
                        AtomicReference<DisconnectInfo> disconnect,
                        JobExecution execution) implements ClientEvents {

    /**
     * The single most useful thing to show while a visit is in flight.
     *
     * <p>A join can sit in {@code CONFIGURATION} for half a minute behind a resource pack, and
     * a dashboard that only says "running" for that whole time is indistinguishable from one
     * that has stopped updating.
     */
    @Override
    public void onPhase(Phase phase) {
      if (execution != null) {
        execution.reportPhase(phase);
      }
    }

    @Override
    public void onChat(ChatLine line) {
      if (!line.plainText().isBlank()) {
        log.debug("[{}] {}", serverId, Redactor.redact(line.plainText()));
      }
      chat.publish(line);
    }

    @Override
    public void onScreen(ContainerInfo info) {
      log.debug("[{}] menu {}", serverId, info.describe());
      screens.publish(info);
    }

    @Override
    public void onScreenClose(int containerId) {
      log.debug("[{}] menu {} closed", serverId, containerId);
    }

    @Override
    public void onResourcePack(ResourcePackEvent event) {
      log.debug("[{}] resource pack {} -> {}", serverId,
          event.id().toString().substring(0, 8), event.status());
    }

    @Override
    public void onCodeOfConduct(String text) {
      log.info("[{}] accepted the server's code of conduct", serverId);
    }

    @Override
    public void onDisconnect(DisconnectInfo info) {
      disconnect.set(info);
      if (info.kind() != DisconnectInfo.Kind.CLIENT_CLOSED) {
        log.warn("[{}] disconnected — {}", serverId, info.describe());
      }
    }
  }
}
