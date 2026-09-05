package net.anweisen.chronit.core.run;

import net.anweisen.chronit.core.auth.AccountManager;
import net.anweisen.chronit.core.config.ChronitConfig;
import net.anweisen.chronit.core.config.JobConfig;
import net.anweisen.chronit.core.config.RetryConfig;
import net.anweisen.chronit.core.config.VisitConfig;
import net.anweisen.chronit.core.driver.MinecraftClientDriver;
import net.anweisen.chronit.core.state.RunHistory;
import net.anweisen.chronit.core.state.RunRecord;
import net.anweisen.chronit.core.state.RunStatus;
import net.anweisen.chronit.core.state.VisitStatus;
import net.anweisen.chronit.core.util.Durations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Runs jobs: a sequence of server visits, in order, with gaps in between.
 *
 * <p>Shared by the scheduler, the one-shot command line and the web interface, so that a manually
 * triggered run behaves exactly like a scheduled one.
 */
public final class Orchestrator {

  private static final Logger log = LoggerFactory.getLogger(Orchestrator.class);

  private final ChronitConfig config;
  private final MinecraftClientDriver driver;
  private final AccountManager accounts;
  private final ProtocolResolver protocols;
  private final AccountLocks locks = new AccountLocks();
  private final RunHistory history;

  /** Jobs currently executing, so they can be shown and stopped. */
  private final Map<String, JobExecution> running = new ConcurrentHashMap<>();

  /** One per job, held for the length of a run: the overlap policy in one place. */
  private final Map<String, ReentrantLock> jobLocks = new ConcurrentHashMap<>();

  /**
   * Told whenever anything observable about a run changes.
   *
   * <p>This is what makes the dashboard live rather than polled. It is deliberately a bare
   * signal with no payload: the web interface already knows how to describe the whole state, and
   * a listener that has to be kept in step with a growing event vocabulary is a listener that
   * eventually falls behind it. Listeners run on the job thread, so they must not block.
   */
  private final List<Runnable> watchers = new CopyOnWriteArrayList<>();

  private final AtomicReference<RunRecord> lastRun = new AtomicReference<>();

  public Orchestrator(ChronitConfig config, MinecraftClientDriver driver, AccountManager accounts) {
    this.config = config;
    this.driver = driver;
    this.accounts = accounts;
    this.protocols = new ProtocolResolver(driver);
    this.history = new RunHistory(config.stateDirOrDefault());
  }

  public RunHistory history() {
    return history;
  }

  public ProtocolResolver protocols() {
    return protocols;
  }

  public AccountLocks locks() {
    return locks;
  }

  public Optional<RunRecord> lastRun() {
    return Optional.ofNullable(lastRun.get());
  }

  public boolean isRunning(String jobId) {
    return running.containsKey(jobId);
  }

  public Map<String, JobExecution> runningJobs() {
    return Map.copyOf(running);
  }

  public Optional<JobExecution> execution(String jobId) {
    return Optional.ofNullable(running.get(jobId));
  }

  /**
   * Registers a listener told whenever a run changes state.
   *
   * @return a handle that removes the listener again
   */
  public AutoCloseable watch(Runnable listener) {
    watchers.add(listener);
    return () -> watchers.remove(listener);
  }

  /** Never lets a misbehaving listener take a job down with it. */
  private void announce() {
    for (Runnable watcher : watchers) {
      try {
        watcher.run();
      } catch (RuntimeException e) {
        log.debug("A run listener failed: {}", e.toString());
      }
    }
  }

  /**
   * Stops a running job.
   *
   * <p>The visit in progress ends as cancelled and the remaining visits are skipped; the run is
   * still written to the history, because "someone stopped it at 20:04" is exactly the kind of
   * thing you want to find later.
   *
   * @return false when that job was not running
   */
  public boolean cancel(String jobId) {
    JobExecution execution = running.get(jobId);
    return execution != null && execution.cancel();
  }

  /**
   * Runs a job to completion, one run of a given job at a time.
   *
   * <p>The overlap policy is enforced by holding the job's lock for the whole run rather than by
   * looking at {@link #running}: the scheduler, the command line and the web interface can all
   * start the same job, and a check followed by a registration lets two of them through the gap
   * between the two. That mattered for more than the policy — the second run would replace the
   * first in {@link #running}, so the first to finish removed the other's entry and left a job
   * that could no longer be found or stopped.
   *
   * @param trigger how the run was started, recorded in the history ("schedule", "cli", "web")
   * @return the record, or empty if the overlap policy dropped this run
   */
  public Optional<RunRecord> runJob(JobConfig job, String trigger) throws InterruptedException {
    ReentrantLock slot = jobLocks.computeIfAbsent(job.id(), ignored -> new ReentrantLock(true));
    if (!slot.tryLock()) {
      if (job.overlapOrDefault() == JobConfig.Overlap.SKIP) {
        log.warn("Job '{}' is still running; skipping this run (overlap: skip)", job.id());
        return Optional.empty();
      }
      log.info("Job '{}' is still running; queueing this run behind it (overlap: queue)", job.id());
      slot.lockInterruptibly();
    }
    try {
      return Optional.of(runClaimed(job, trigger));
    } finally {
      slot.unlock();
    }
  }

  /** The body of a run, with this job's slot already held. */
  private RunRecord runClaimed(JobConfig job, String trigger) throws InterruptedException {
    List<VisitConfig> plan = job.visits() == null ? List.of() : job.visits();
    JobExecution execution = new JobExecution(
        job.id(), trigger, Thread.currentThread(), plan.size(), this::announce);
    running.put(job.id(), execution);
    String runId = UUID.randomUUID().toString().substring(0, 8);
    Instant startedAt = Instant.now();
    List<RunRecord.VisitRecord> visits = new ArrayList<>();
    // Why the chain stopped, recorded on every visit it never reached rather than leaving
    // those visits out of the history entirely.
    String abandonedBecause = null;

    log.info("Starting job '{}' ({} visit(s), trigger: {})", job.id(), plan.size(), trigger);
    announce();
    try {
      VisitRunner runner = new VisitRunner(driver, accounts, protocols, locks, config);

      for (int i = 0; i < plan.size(); i++) {
        if (execution.isCancelled()) {
          log.info("Job '{}' cancelled; skipping the remaining {} visit(s)",
              job.id(), plan.size() - i);
          abandonedBecause = "The job was stopped before this visit";
          break;
        }
        VisitConfig visit = plan.get(i);
        RunRecord.VisitRecord record = runner.run(visit, execution, i + 1);
        visits.add(record);
        announce();

        if (!record.success() && record.status() != VisitStatus.CANCELLED) {
          RetryConfig retry = visit.onFail() != null
              ? visit.onFail().withFallback(config.effectiveDefaults().onFail())
              : config.effectiveDefaults().onFail();
          if (retry.then() == RetryConfig.OnFail.ABORT_JOB) {
            log.error("Visit to '{}' failed ({}); abandoning the rest of job '{}'",
                visit.server(), record.detail(), job.id());
            abandonedBecause = "Abandoned after '" + visit.server() + "' failed";
            break;
          }
          log.warn("Visit to '{}' failed ({}); moving on", visit.server(), record.detail());
        }

        boolean isLast = i == plan.size() - 1;
        if (!isLast && !execution.isCancelled()) {
          Duration gap = visit.gapAfterOrDefault();
          if (!gap.isZero()) {
            // Also gives the server time to tear the session down before the same
            // account appears somewhere else.
            log.debug("Pausing {} before the next visit", Durations.format(gap));
            Thread.sleep(gap.toMillis());
          }
        }
      }
    } catch (InterruptedException e) {
      if (!execution.isCancelled()) {
        throw e;
      }
      log.info("Job '{}' stopped on request", job.id());
      if (abandonedBecause == null) {
        abandonedBecause = "The job was stopped before this visit";
      }
    } finally {
      running.remove(job.id(), execution);
      // Cancelling interrupts this thread. The scheduler's pool reuses it, so the flag has
      // to be cleared or the next job would abort the moment it slept.
      Thread.interrupted();
    }

    // Visits are appended in plan order, so whatever is left of the plan is what never ran.
    for (int i = visits.size(); i < plan.size(); i++) {
      VisitConfig visit = plan.get(i);
      visits.add(RunRecord.VisitRecord.skipped(visit.server(), visit.account(),
          abandonedBecause == null ? "Never attempted" : abandonedBecause));
    }

    RunRecord record = new RunRecord(runId, job.id(), trigger, startedAt, Instant.now(),
        visits, statusOf(execution, visits));
    history.append(record);
    lastRun.set(record);
    announce();

    log.info("Job '{}' {} in {} — {}/{} visit(s) succeeded, {} skipped",
        job.id(), record.status().name().toLowerCase(Locale.ENGLISH),
        Durations.format(record.duration()), record.successCount(), record.attemptedCount(),
        record.skippedCount());
    return record;
  }

  /**
   * The one place a run's overall status is decided.
   *
   * <p>Cancellation wins over everything else, including a chain whose visits all happened to
   * succeed before someone pressed stop — an operator who stopped a job wants to see that they
   * did, not a green tick.
   */
  private static RunStatus statusOf(JobExecution execution, List<RunRecord.VisitRecord> visits) {
    if (execution.isCancelled()) {
      return RunStatus.CANCELLED;
    }
    long attempted = visits.stream()
        .filter(visit -> visit.status() != VisitStatus.SKIPPED)
        .count();
    if (attempted == 0) {
      return RunStatus.SKIPPED;
    }
    long succeeded = visits.stream().filter(RunRecord.VisitRecord::success).count();
    if (succeeded == attempted) {
      return RunStatus.SUCCEEDED;
    }
    return succeeded == 0 ? RunStatus.FAILED : RunStatus.PARTIAL;
  }

  /** Runs a single ad-hoc visit, as used by {@code chronit run --server ...}. */
  public RunRecord runVisit(VisitConfig visit, String trigger) throws InterruptedException {
    String runId = UUID.randomUUID().toString().substring(0, 8);
    Instant startedAt = Instant.now();

    VisitRunner runner = new VisitRunner(driver, accounts, protocols, locks, config);
    RunRecord.VisitRecord result = runner.run(visit);

    RunRecord record = new RunRecord(runId, "(ad-hoc)", trigger, startedAt, Instant.now(), List.of(result));
    history.append(record);
    lastRun.set(record);
    announce();
    return record;
  }
}
