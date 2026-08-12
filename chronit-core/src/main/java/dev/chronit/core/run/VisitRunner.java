package dev.chronit.core.run;

import dev.chronit.core.auth.AccountManager;
import dev.chronit.core.auth.AuthException;
import dev.chronit.core.config.AccountConfig;
import dev.chronit.core.config.ChronitConfig;
import dev.chronit.core.config.RetryConfig;
import dev.chronit.core.config.ServerConfig;
import dev.chronit.core.config.VisitConfig;
import dev.chronit.core.driver.AuthContext;
import dev.chronit.core.driver.ChatLine;
import dev.chronit.core.driver.ClientEvents;
import dev.chronit.core.driver.ClientHandle;
import dev.chronit.core.driver.ConnectRequest;
import dev.chronit.core.driver.DisconnectInfo;
import dev.chronit.core.driver.DriverException;
import dev.chronit.core.driver.MinecraftClientDriver;
import dev.chronit.core.driver.ReadyInfo;
import dev.chronit.core.driver.ResourcePackEvent;
import dev.chronit.core.driver.ServerTarget;
import dev.chronit.core.driver.SessionSettings;
import dev.chronit.core.state.RunRecord;
import dev.chronit.core.util.Durations;
import dev.chronit.core.util.Redactor;
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
            for (int attempt = 1; attempt <= attempts; attempt++) {
                if (attempt > 1) {
                    Duration backoff = retry.backoffFor(attempt - 1);
                    log.info("Retrying {} (attempt {}/{}) in {}",
                            server.id(), attempt, attempts, Durations.format(backoff));
                    Thread.sleep(backoff.toMillis());
                }

                Attempt result = attemptVisit(visit, server, account, attempt);
                if (result.success()) {
                    return new RunRecord.VisitRecord(server.id(), account.id(), startedAt,
                            Duration.between(startedAt, Instant.now()), true, result.detail(),
                            result.actionsRun(), attempt, result.protocolVersion(), result.translated());
                }
                failure = result.detail();
                if (result.fatal()) {
                    break;
                }
            }
            return new RunRecord.VisitRecord(server.id(), account.id(), startedAt,
                    Duration.between(startedAt, Instant.now()), false, failure, 0, attempts, -1, false);
        }
    }

    /**
     * @param kind why the session ended, when it got far enough to have one. Carried explicitly so
     *             the caller can recognise a version rejection without parsing a message.
     */
    private record Attempt(boolean success, boolean fatal, String detail, int actionsRun,
                           int protocolVersion, boolean translated, DisconnectInfo.Kind kind) {

        static Attempt failed(String detail, DisconnectInfo.Kind kind) {
            return new Attempt(false, false, detail, 0, -1, false, kind);
        }

        /** A failure no retry can fix — bad credentials, unreachable version. */
        static Attempt fatal(String detail) {
            return new Attempt(false, true, detail, 0, -1, false, DisconnectInfo.Kind.UNKNOWN);
        }

        boolean rejectedOverVersion() {
            return kind == DisconnectInfo.Kind.VERSION_MISMATCH;
        }
    }

    private Attempt attemptVisit(VisitConfig visit, ServerConfig server, AccountConfig account, int attempt)
            throws InterruptedException {
        AuthContext auth;
        try {
            auth = accounts.resolve(account);
        } catch (AuthException e) {
            log.error("Cannot visit {}: {}", server.id(), e.getMessage());
            return e.needsLogin()
                    ? Attempt.fatal(e.getMessage())
                    : Attempt.failed(e.getMessage(), DisconnectInfo.Kind.AUTH_FAILED);
        }

        SessionSettings settings = SessionSettings.resolve(config, server);
        ServerTarget target = ServerTarget.of(server);

        ProtocolResolver.Plan plan;
        try {
            plan = protocols.plan(target);
        } catch (DriverException e) {
            return Attempt.fatal(e.getMessage());
        }

        Attempt result = connectAndRun(visit, server, target, auth, settings, plan);

        // A rejection that names the protocol is worth one immediate retry through a translation
        // layer, since the alternative is failing a schedule over something we can work around.
        if (!result.success() && result.rejectedOverVersion()) {
            Optional<ProtocolResolver.Plan> fallback = protocols.replan(target);
            if (fallback.isPresent()) {
                result = connectAndRun(visit, server, target, auth, settings, fallback.get());
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
                                  ProtocolResolver.Plan plan) throws InterruptedException {
        ChatBus chat = new ChatBus();
        AtomicReference<DisconnectInfo> disconnect = new AtomicReference<>();

        log.info("Visiting {} ({}) as account '{}' using {}",
                server.id(), target.address(), auth.username(), plan.note());

        ClientHandle client;
        try {
            client = driver.connect(
                    new ConnectRequest(target, auth, settings, plan.protocolVersion(), plan.translated()),
                    new Events(chat, server.id(), disconnect));
        } catch (DriverException e) {
            return Attempt.fatal(e.getMessage());
        }

        try {
            ReadyInfo ready = awaitReady(client, settings.readyWhen().timeout());
            Instant readyAt = Instant.now();

            ActionRunner runner = new ActionRunner(chat, settings.jitter());
            ActionRunner.Result actions = runner.run(client, visit.onReadyOrEmpty());

            if (!actions.isSuccess()) {
                client.disconnect("Command sequence failed");
                return new Attempt(false, false, actions.detail(), actions.executed(),
                        ready.protocolVersion(), ready.translated(), DisconnectInfo.Kind.CLIENT_CLOSED);
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
                    DisconnectInfo.Kind.CLIENT_CLOSED);
        } catch (TimeoutException e) {
            client.disconnect("Timed out joining");
            return Attempt.failed(describeFailure(disconnect.get(), e.getMessage()),
                    kindOf(disconnect.get(), DisconnectInfo.Kind.TIMEOUT));
        } catch (ExecutionException e) {
            String message = e.getCause() != null ? e.getCause().getMessage() : e.getMessage();
            return Attempt.failed(describeFailure(disconnect.get(), message),
                    kindOf(disconnect.get(), DisconnectInfo.Kind.UNKNOWN));
        } finally {
            chat.abort("session ended");
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

    /** Bridges driver callbacks into the chat bus and the log. */
    private record Events(ChatBus chat,
                          String serverId,
                          AtomicReference<DisconnectInfo> disconnect) implements ClientEvents {

        @Override
        public void onChat(ChatLine line) {
            if (!line.plainText().isBlank()) {
                log.debug("[{}] {}", serverId, Redactor.redact(line.plainText()));
            }
            chat.publish(line);
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
