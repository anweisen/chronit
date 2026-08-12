package dev.chronit.core.run;

import dev.chronit.core.auth.AccountManager;
import dev.chronit.core.config.ChronitConfig;
import dev.chronit.core.config.JobConfig;
import dev.chronit.core.config.RetryConfig;
import dev.chronit.core.config.VisitConfig;
import dev.chronit.core.driver.MinecraftClientDriver;
import dev.chronit.core.state.RunHistory;
import dev.chronit.core.state.RunRecord;
import dev.chronit.core.util.Durations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

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

    /** Jobs currently executing, so overlap policy can be applied. */
    private final Map<String, Instant> running = new ConcurrentHashMap<>();

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

    public Map<String, Instant> runningJobs() {
        return Map.copyOf(running);
    }

    /**
     * Runs a job to completion.
     *
     * @param trigger how the run was started, recorded in the history ("schedule", "cli", "web")
     * @return the record, or empty if the overlap policy dropped this run
     */
    public Optional<RunRecord> runJob(JobConfig job, String trigger) throws InterruptedException {
        if (job.overlapOrDefault() == JobConfig.Overlap.SKIP && running.containsKey(job.id())) {
            log.warn("Job '{}' is still running from {}; skipping this run (overlap: skip)",
                    job.id(), running.get(job.id()));
            return Optional.empty();
        }

        running.put(job.id(), Instant.now());
        String runId = UUID.randomUUID().toString().substring(0, 8);
        Instant startedAt = Instant.now();
        List<RunRecord.VisitRecord> visits = new ArrayList<>();

        log.info("Starting job '{}' ({} visit(s), trigger: {})", job.id(), job.visits().size(), trigger);
        try {
            VisitRunner runner = new VisitRunner(driver, accounts, protocols, locks, config);

            for (int i = 0; i < job.visits().size(); i++) {
                VisitConfig visit = job.visits().get(i);
                RunRecord.VisitRecord record = runner.run(visit);
                visits.add(record);

                if (!record.success()) {
                    RetryConfig retry = visit.onFail() != null
                            ? visit.onFail().withFallback(config.effectiveDefaults().onFail())
                            : config.effectiveDefaults().onFail();
                    if (retry.then() == RetryConfig.OnFail.ABORT_JOB) {
                        log.error("Visit to '{}' failed ({}); abandoning the rest of job '{}'",
                                visit.server(), record.detail(), job.id());
                        break;
                    }
                    log.warn("Visit to '{}' failed ({}); moving on", visit.server(), record.detail());
                }

                boolean isLast = i == job.visits().size() - 1;
                if (!isLast) {
                    Duration gap = visit.gapAfterOrDefault();
                    if (!gap.isZero()) {
                        // Also gives the server time to tear the session down before the same
                        // account appears somewhere else.
                        log.debug("Pausing {} before the next visit", Durations.format(gap));
                        Thread.sleep(gap.toMillis());
                    }
                }
            }
        } finally {
            running.remove(job.id());
        }

        RunRecord record = new RunRecord(runId, job.id(), trigger, startedAt, Instant.now(), List.copyOf(visits));
        history.append(record);
        lastRun.set(record);

        log.info("Job '{}' finished in {} — {}/{} visit(s) succeeded",
                job.id(), Durations.format(record.duration()), record.successCount(), visits.size());
        return Optional.of(record);
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
        return record;
    }
}
