package dev.chronit.core.state;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

/** One execution of a job, as written to the run history. */
public record RunRecord(
        String runId,
        String jobId,
        String trigger,
        Instant startedAt,
        Instant finishedAt,
        List<VisitRecord> visits) {

    public Duration duration() {
        return finishedAt == null ? Duration.ZERO : Duration.between(startedAt, finishedAt);
    }

    public boolean succeeded() {
        return !visits.isEmpty() && visits.stream().allMatch(VisitRecord::success);
    }

    public long successCount() {
        return visits.stream().filter(VisitRecord::success).count();
    }

    /** One server visit within a run. */
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
            boolean translated) {
    }
}
