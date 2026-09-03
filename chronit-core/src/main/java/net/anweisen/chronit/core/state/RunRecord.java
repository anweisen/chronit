package net.anweisen.chronit.core.state;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/**
 * One execution of a job, as written to the run history.
 *
 * @param status how it ended. Written explicitly rather than worked out at display time, because
 *               "an operator stopped this" is not recoverable from the visits alone — a job
 *               stopped during the gap between two successful visits looks identical to one that
 *               finished. History written before this field existed deserialises with a null here
 *               and the compact constructor derives the closest equivalent, so old files stay
 *               readable
 */
public record RunRecord(
        String runId,
        String jobId,
        String trigger,
        Instant startedAt,
        Instant finishedAt,
        List<VisitRecord> visits,
        RunStatus status) {

    public RunRecord {
        visits = visits == null ? List.of() : List.copyOf(visits);
        if (status == null) {
            status = derive(visits);
        }
    }

    /** The pre-{@link RunStatus} shape, kept so existing callers and history files still work. */
    public RunRecord(String runId, String jobId, String trigger,
                     Instant startedAt, Instant finishedAt, List<VisitRecord> visits) {
        this(runId, jobId, trigger, startedAt, finishedAt, visits, null);
    }

    private static RunStatus derive(List<VisitRecord> visits) {
        if (visits.isEmpty()) {
            return RunStatus.SKIPPED;
        }
        if (visits.stream().anyMatch(visit -> visit.status() == VisitStatus.CANCELLED)) {
            return RunStatus.CANCELLED;
        }
        long attempted = visits.stream().filter(visit -> visit.status() != VisitStatus.SKIPPED).count();
        long succeeded = visits.stream().filter(VisitRecord::success).count();
        if (attempted == 0) {
            return RunStatus.SKIPPED;
        }
        if (succeeded == attempted) {
            return RunStatus.SUCCEEDED;
        }
        return succeeded == 0 ? RunStatus.FAILED : RunStatus.PARTIAL;
    }

    public Duration duration() {
        return finishedAt == null ? Duration.ZERO : Duration.between(startedAt, finishedAt);
    }

    public boolean succeeded() {
        return status == RunStatus.SUCCEEDED;
    }

    public long successCount() {
        return visits.stream().filter(VisitRecord::success).count();
    }

    /** Visits that were actually tried — the denominator worth showing next to {@link #successCount()}. */
    public long attemptedCount() {
        return visits.stream().filter(visit -> visit.status() != VisitStatus.SKIPPED).count();
    }

    public long skippedCount() {
        return visits.stream().filter(visit -> visit.status() == VisitStatus.SKIPPED).count();
    }

    /**
     * One server visit within a run.
     *
     * @param timeToReady how long the join took — connect, configuration, spawn. Null when the
     *                    visit never got that far, which is itself the useful signal
     * @param outcome     why it ended, as a {@code DisconnectInfo.Kind} name. Distinguishes a
     *                    version rejection from a resource pack refusal from a plain timeout
     *                    without anyone having to read the message
     * @param status      succeeded, failed, stopped or never attempted. Derived from the older
     *                    fields when absent, so history files predating it still load
     */
    public record VisitRecord(
            String serverId,
            String accountId,
            Instant startedAt,
            Duration duration,
            boolean success,
            String detail,
            int actionsRun,
            int attempts,
            int protocolVersion,
            boolean translated,
            Duration timeToReady,
            String outcome,
            VisitStatus status) {

        public VisitRecord {
            if (status == null) {
                status = success ? VisitStatus.SUCCEEDED
                        : "CANCELLED".equals(outcome) ? VisitStatus.CANCELLED
                        : VisitStatus.FAILED;
            }
        }

        /** The pre-{@link VisitStatus} shape, for history files written before it existed. */
        public VisitRecord(String serverId, String accountId, Instant startedAt, Duration duration,
                           boolean success, String detail, int actionsRun, int attempts,
                           int protocolVersion, boolean translated, Duration timeToReady,
                           String outcome) {
            this(serverId, accountId, startedAt, duration, success, detail, actionsRun, attempts,
                    protocolVersion, translated, timeToReady, outcome, null);
        }

        /** A visit the job never reached, recorded so the gap in a run is visible rather than absent. */
        public static VisitRecord skipped(String serverId, String accountId, String reason) {
            return new VisitRecord(serverId, accountId, null, Duration.ZERO, false, reason,
                    0, 0, -1, false, null, null, VisitStatus.SKIPPED);
        }

        public boolean reachedTheWorld() {
            return timeToReady != null;
        }
    }
}
