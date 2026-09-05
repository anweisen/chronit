package net.anweisen.chronit.core.run;

import net.anweisen.chronit.core.driver.ClientHandle;
import net.anweisen.chronit.core.driver.Phase;
import net.anweisen.chronit.core.util.Durations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

/**
 * A job that is currently running: what it is doing right now, and the handle used to stop it.
 *
 * <p>Stopping needs two things to happen, and doing only one leaves the job half-dead. The worker
 * thread spends most of its life blocked — sleeping between actions, waiting out a {@code stayFor},
 * waiting on a join — so it has to be interrupted. And the server has a session open for us, which
 * should be closed the way a client closes it rather than left for the read timeout to notice; an
 * account that vanishes without disconnecting is the one that gets "already logged in" on its next
 * visit.
 *
 * <p>So cancelling disconnects first, then interrupts.
 *
 * <p>It also carries the live progress the dashboard shows. Every field that changes calls the
 * change signal, which is what pushes an update to connected browsers — a job moving from
 * {@code CONFIGURATION} to {@code IN_WORLD} appears immediately rather than on the next poll.
 */
public final class JobExecution {

  /**
   * What a job is doing while no session is open.
   *
   * <p>{@link Phase} describes a session, and these are the stretches where there is no session to
   * describe. They are not a rare edge: a visit to a server that refuses the connection fails in
   * milliseconds and is then followed by a backoff of half a minute, so a job having a bad night
   * spends nearly all of its run in {@link #RETRY} rather than in any phase. Reporting that as
   * {@code CLOSED} said "between visits", which was both uninformative and wrong — the visit had
   * not finished, it was about to be attempted again.
   */
  public enum Wait {
    /** A session is open, or the next attempt is starting right now. */
    NONE,
    /** The attempt failed and the next one is waiting out its backoff. */
    RETRY,
    /** The visit is over and the configured gap before the next one is running. */
    NEXT_VISIT,
    /** The account is signed in somewhere else, and this visit has to wait its turn. */
    ACCOUNT
  }

  private static final Logger log = LoggerFactory.getLogger(JobExecution.class);

  private final String jobId;
  private final String trigger;
  private final Instant startedAt;
  private final Thread worker;
  private final int visitCount;
  private final Runnable onChange;

  private final AtomicBoolean cancelled = new AtomicBoolean();
  private final AtomicReference<ClientHandle> activeClient = new AtomicReference<>();
  private volatile String currentServer;
  private volatile String currentAccount;
  private volatile int visitIndex;
  private volatile int attempt = 1;
  private volatile int attemptsAllowed = 1;
  private volatile Phase phase = Phase.CONNECTING;
  /** True between the start of a visit and the teardown of its session. */
  private volatile boolean live;
  private volatile Wait waiting = Wait.NONE;
  private volatile Instant waitingUntil;
  private volatile Duration waitingFor;
  private volatile String waitingReason;

  JobExecution(String jobId, String trigger, Thread worker, int visitCount, Runnable onChange) {
    this.jobId = jobId;
    this.trigger = trigger;
    this.startedAt = Instant.now();
    this.worker = worker;
    this.visitCount = visitCount;
    this.onChange = onChange == null ? () -> {
    } : onChange;
  }

  public String jobId() {
    return jobId;
  }

  public String trigger() {
    return trigger;
  }

  public Instant startedAt() {
    return startedAt;
  }

  public Duration elapsed() {
    return Duration.between(startedAt, Instant.now());
  }

  /** Which server the job is on right now, if any. */
  public String currentServer() {
    return currentServer;
  }

  public String currentAccount() {
    return currentAccount;
  }

  /** One-based position in the visit chain, or 0 before the first visit starts. */
  public int visitIndex() {
    return visitIndex;
  }

  public int visitCount() {
    return visitCount;
  }

  /** Which retry of the current visit is in flight, counting from one. */
  public int attempt() {
    return attempt;
  }

  /** How many attempts this visit is allowed in total, the first one included. */
  public int attemptsAllowed() {
    return attemptsAllowed;
  }

  /** What the job is waiting for, when it is between sessions rather than in one. */
  public Wait waiting() {
    return waiting;
  }

  /** When the wait is due to end, so the dashboard can count it down. */
  public Instant waitingUntil() {
    return waitingUntil;
  }

  /**
   * How long the wait is in total, which unlike the remaining time does not change while it runs.
   *
   * <p>That is the whole point of it: the deadline and this are both constant for the life of the
   * wait, so everything the dashboard is told about a waiting job is constant too, and the ticking
   * is left to the one place that can tick without anyone publishing anything. Null for a wait
   * with no known length.
   */
  public Duration waitingFor() {
    return waitingFor;
  }

  /** Why the last attempt failed, while waiting to retry it. Null otherwise. */
  public String waitingReason() {
    return waitingReason;
  }

  /** How far the live session has got. The single most useful thing to show while waiting. */
  public Phase phase() {
    return phase;
  }

  public boolean isCancelled() {
    return cancelled.get();
  }

  /**
   * Asks the job to stop.
   *
   * @return false if it was already stopping
   */
  public boolean cancel() {
    if (!cancelled.compareAndSet(false, true)) {
      return false;
    }
    log.info("Cancelling job '{}' after {}", jobId, Durations.format(elapsed()));
    onChange.run();

    ClientHandle client = activeClient.get();
    if (client != null) {
      try {
        // Leave properly, so the server tears the session down now rather than when its
        // read timeout eventually fires.
        client.disconnect("Cancelled");
      } catch (RuntimeException e) {
        log.debug("Client did not disconnect cleanly on cancel: {}", e.toString());
      }
    }
    worker.interrupt();
    return true;
  }

  /** Announces which visit is about to be attempted, before anything can block. */
  void beginVisit(int index, String serverId, String accountId, int attempt, int attemptsAllowed) {
    this.visitIndex = index;
    this.currentServer = serverId;
    this.currentAccount = accountId;
    this.attempt = attempt;
    this.attemptsAllowed = attemptsAllowed;
    this.phase = Phase.CONNECTING;
    this.live = true;
    this.waiting = Wait.NONE;
    this.waitingUntil = null;
    this.waitingFor = null;
    this.waitingReason = null;
    onChange.run();
  }

  /**
   * The attempt failed and the next one starts at {@code until}.
   *
   * @param nextAttempt the attempt being counted down to, so the live line names the one that is
   *                    coming rather than the one that has already been lost
   * @param reason      why the attempt that just failed did, so the wait says what it is waiting on
   */
  void awaitRetry(Duration backoff, int nextAttempt, String reason) {
    this.attempt = nextAttempt;
    this.waiting = Wait.RETRY;
    this.waitingUntil = Instant.now().plus(backoff);
    this.waitingFor = backoff;
    this.waitingReason = reason;
    onChange.run();
  }

  /**
   * The visit cannot start because the account it needs is on another server.
   *
   * <p>No deadline: it lasts as long as the other visit does, and inventing an estimate for it
   * would be worse than saying nothing.
   */
  void awaitAccount(String accountId) {
    this.waiting = Wait.ACCOUNT;
    this.waitingUntil = null;
    this.waitingFor = null;
    this.waitingReason = "Account '" + accountId + "' is on another server; waiting for it to leave.";
    onChange.run();
  }

  /** The visit is over and the gap before the next one is running. */
  void awaitNextVisit(Duration gap) {
    this.waiting = Wait.NEXT_VISIT;
    this.waitingUntil = Instant.now().plus(gap);
    this.waitingFor = gap;
    this.waitingReason = null;
    onChange.run();
  }

  /**
   * Reports how far the live session has got.
   *
   * <p>Ignored once the visit has been detached. A driver still emits {@code LEAVING} and
   * {@code CLOSED} as it tears the socket down, and accepting those would walk the phase
   * backwards after the visit had already finished — the dashboard would show a job that had
   * moved on to waiting still apparently leaving the server it had left.
   */
  void reportPhase(Phase phase) {
    if (live && this.phase != phase) {
      this.phase = phase;
      onChange.run();
    }
  }

  /** Registers the session currently in use, so cancelling can close it. */
  void attach(ClientHandle client, String serverId) {
    activeClient.set(client);
    currentServer = serverId;
    onChange.run();
  }

  void detach() {
    activeClient.set(null);
    live = false;
    phase = Phase.CLOSED;
    onChange.run();
  }

  /**
   * Turns a cancellation into the exception the run loop unwinds on.
   *
   * <p>Called at the points between blocking operations, so a cancel that arrives while the
   * thread happens to be running rather than sleeping still takes effect promptly.
   */
  void throwIfCancelled() throws InterruptedException {
    if (cancelled.get()) {
      throw new InterruptedException("Job '" + jobId + "' was cancelled");
    }
  }
}
