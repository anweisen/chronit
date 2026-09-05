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
  private volatile Phase phase = Phase.CONNECTING;
  /** True between the start of a visit and the teardown of its session. */
  private volatile boolean live;

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
  void beginVisit(int index, String serverId, String accountId, int attempt) {
    this.visitIndex = index;
    this.currentServer = serverId;
    this.currentAccount = accountId;
    this.attempt = attempt;
    this.phase = Phase.CONNECTING;
    this.live = true;
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
