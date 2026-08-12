package dev.chronit.core.config;

import java.time.ZoneId;
import java.util.List;

/**
 * A scheduled sequence of visits.
 *
 * @param cron     standard five-field cron, or six fields when the first is seconds
 * @param timezone zone the cron expression is interpreted in. Defaults to the process zone, which
 *                 in the container is whatever {@code TZ} is set to
 * @param overlap  what to do when the previous run of this job is still going
 * @param misfire  what to do about a run that was due while the process was down
 */
public record JobConfig(
        String id,
        String cron,
        ZoneId timezone,
        Overlap overlap,
        Misfire misfire,
        Boolean enabled,
        List<VisitConfig> visits) {

    public enum Overlap {
        /** Drop the new run. */
        SKIP,
        /** Run it as soon as the previous one finishes. */
        QUEUE
    }

    public enum Misfire {
        /** Wait for the next scheduled time. */
        IGNORE,
        /** Run once immediately on startup if a fire time was missed while the process was down. */
        RUN_ONCE
    }

    public boolean isEnabled() {
        return enabled == null || enabled;
    }

    public Overlap overlapOrDefault() {
        return overlap != null ? overlap : Overlap.SKIP;
    }

    public Misfire misfireOrDefault() {
        return misfire != null ? misfire : Misfire.IGNORE;
    }

    public ZoneId zoneOrDefault() {
        return timezone != null ? timezone : ZoneId.systemDefault();
    }
}
