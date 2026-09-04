package net.anweisen.chronit.core.run;

import net.anweisen.chronit.core.config.ChronitConfig;
import net.anweisen.chronit.core.config.JobConfig;
import net.anweisen.chronit.core.util.Durations;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Fires jobs on their cron schedules.
 *
 * <p>The daemon exists rather than leaning entirely on system cron because a visit is a long-lived
 * stateful session — connect, wait to spawn, run commands, stay half an hour, leave — which suits a
 * supervised process better than a fire-and-forget invocation. Microsoft tokens also need periodic
 * refreshing, and a resident process can do that quietly instead of on the critical path of a run.
 * The one-shot command line remains available for anyone who prefers external scheduling.
 */
public final class Scheduler implements AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(Scheduler.class);

    /**
     * Cron granularity is a minute, so re-checking every 30 seconds cannot miss a fire time while
     * keeping the loop cheap. Each job also records what it last fired, so a double check within
     * the same minute cannot fire it twice.
     */
    private static final Duration TICK = Duration.ofSeconds(30);

    private final ChronitConfig config;
    private final Orchestrator orchestrator;
    private final ScheduledExecutorService ticker =
            Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "chronit-scheduler");
                thread.setDaemon(true);
                return thread;
            });

    /** Jobs run on their own threads so a long visit never delays another job's fire time. */
    private final ExecutorService workers = Executors.newCachedThreadPool(runnable -> {
        Thread thread = new Thread(runnable, "chronit-job");
        thread.setDaemon(false);
        return thread;
    });

    private final Map<String, CronSchedule> schedules = new ConcurrentHashMap<>();
    private final Map<String, ZonedDateTime> lastFired = new ConcurrentHashMap<>();
    private final AtomicBoolean started = new AtomicBoolean();

    public Scheduler(ChronitConfig config, Orchestrator orchestrator) {
        this.config = config;
        this.orchestrator = orchestrator;
        for (JobConfig job : config.jobsOrEmpty()) {
            if (job.isEnabled()) {
                schedules.put(job.id(), CronSchedule.parse(job.cron(), job.zoneOrDefault()));
            }
        }
    }

    public void start() {
        if (!started.compareAndSet(false, true)) {
            return;
        }
        if (schedules.isEmpty()) {
            log.warn("No enabled jobs; the scheduler has nothing to do");
        }
        schedules.forEach((id, schedule) -> log.info("Job '{}' scheduled: {} — next run {}",
                id, schedule, schedule.next().map(Object::toString).orElse("never")));

        runMissedJobs();
        ticker.scheduleAtFixedRate(this::tick, 0, TICK.toSeconds(), TimeUnit.SECONDS);
    }

    /** Jobs whose fire time passed while the process was down, for those configured to catch up. */
    private void runMissedJobs() {
        for (JobConfig job : config.jobsOrEmpty()) {
            if (!job.isEnabled() || job.misfireOrDefault() != JobConfig.Misfire.RUN_ONCE) {
                continue;
            }
            CronSchedule schedule = schedules.get(job.id());
            Optional<ZonedDateTime> previous = schedule.lastBefore(ZonedDateTime.now(schedule.zone()));
            if (previous.isPresent()) {
                log.info("Job '{}' had a missed run at {}; running it now (misfire: runOnce)",
                        job.id(), previous.get());
                lastFired.put(job.id(), previous.get());
                submit(job, "misfire");
            }
        }
    }

    private void tick() {
        try {
            ZonedDateTime now = ZonedDateTime.now();
            for (JobConfig job : config.jobsOrEmpty()) {
                if (!job.isEnabled()) {
                    continue;
                }
                CronSchedule schedule = schedules.get(job.id());
                if (schedule == null) {
                    continue;
                }

                Optional<ZonedDateTime> due = schedule.lastBefore(now.withZoneSameInstant(schedule.zone()));
                if (due.isEmpty()) {
                    continue;
                }
                ZonedDateTime fireTime = due.get();

                // Only fire for a time we have not already fired, and only if it is recent — after
                // a long downtime the previous fire time could be days ago, which is what the
                // misfire policy is for, not this loop.
                ZonedDateTime alreadyFired = lastFired.get(job.id());
                boolean isNew = alreadyFired == null || fireTime.isAfter(alreadyFired);
                boolean isRecent = Duration.between(fireTime, now).compareTo(TICK.multipliedBy(3)) <= 0;

                if (isNew && isRecent) {
                    lastFired.put(job.id(), fireTime);
                    submit(job, "schedule");
                } else if (isNew) {
                    // Record it so a restart does not immediately fire a stale time.
                    lastFired.put(job.id(), fireTime);
                }
            }
        } catch (RuntimeException e) {
            // The ticker must survive anything, or the daemon silently stops scheduling.
            log.error("Scheduler tick failed: {}", e.toString(), e);
        }
    }

    private void submit(JobConfig job, String trigger) {
        workers.execute(() -> {
            try {
                orchestrator.runJob(job, trigger);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                log.warn("Job '{}' was interrupted", job.id());
            } catch (RuntimeException e) {
                log.error("Job '{}' failed unexpectedly: {}", job.id(), e.toString(), e);
            }
        });
    }

    /** Upcoming fire times, for the status display. */
    public List<Upcoming> upcoming() {
        List<Upcoming> result = new ArrayList<>();
        schedules.forEach((id, schedule) -> result.add(new Upcoming(
                id,
                schedule.expression(),
                schedule.zone().getId(),
                schedule.next().orElse(null),
                schedule.timeUntilNext().map(Durations::format).orElse("never"),
                orchestrator.isRunning(id))));
        // Two jobs that will never fire again compare equal. Returning "after" for both, as this
        // did, is not a valid ordering, and TimSort rejects one outright past 32 elements.
        result.sort(Comparator.comparing(Upcoming::nextRun,
                Comparator.nullsLast(Comparator.naturalOrder())));
        return result;
    }

    public record Upcoming(String jobId, String cron, String timezone,
                           ZonedDateTime nextRun, String inText, boolean running) {
    }

    @Override
    public void close() {
        ticker.shutdownNow();
        workers.shutdown();
        try {
            // Let a visit in progress leave cleanly rather than dropping the connection.
            if (!workers.awaitTermination(30, TimeUnit.SECONDS)) {
                log.warn("A job was still running at shutdown; forcing it to stop");
                workers.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            workers.shutdownNow();
        }
    }
}
