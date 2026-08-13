package net.anweisen.chronit.core.config;

import java.time.Duration;

/**
 * What to do when a visit fails — connection refused, kicked, or readiness never reached.
 *
 * @param retries how many additional attempts to make
 * @param backoff pause between attempts; doubles each time up to a cap
 * @param then    what to do once the attempts are exhausted
 */
public record RetryConfig(
        Integer retries,
        Duration backoff,
        OnFail then) {

    public enum OnFail {
        /** Move on to the next visit in the job. */
        SKIP,
        /** Abandon the remaining visits in this job. */
        ABORT_JOB
    }

    public static final RetryConfig DEFAULTS =
            new RetryConfig(1, Duration.ofSeconds(30), OnFail.SKIP);

    /** Cap on the exponential backoff, so a long retry chain cannot run past the next scheduled job. */
    public static final Duration MAX_BACKOFF = Duration.ofMinutes(5);

    public RetryConfig withFallback(RetryConfig base) {
        if (base == null) {
            return this;
        }
        return new RetryConfig(
                retries != null ? retries : base.retries,
                backoff != null ? backoff : base.backoff,
                then != null ? then : base.then);
    }

    /** Backoff before attempt number {@code attempt} (1-based), doubling and capped. */
    public Duration backoffFor(int attempt) {
        Duration base = backoff != null ? backoff : DEFAULTS.backoff();
        Duration scaled = base.multipliedBy(1L << Math.min(attempt - 1, 8));
        return scaled.compareTo(MAX_BACKOFF) > 0 ? MAX_BACKOFF : scaled;
    }
}
