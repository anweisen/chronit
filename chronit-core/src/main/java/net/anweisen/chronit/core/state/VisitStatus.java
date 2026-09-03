package net.anweisen.chronit.core.state;

/**
 * How one visit within a run ended.
 *
 * <p>{@link #SKIPPED} is the one that did not exist before. When a job was stopped, or an earlier
 * visit failed with {@code then: abortJob}, the remaining visits simply never appeared in the
 * history — so a five-visit job that stopped after the second one was recorded as a two-visit run,
 * and the three servers that were never contacted looked like they had never been configured.
 */
public enum VisitStatus {

    SUCCEEDED,
    FAILED,

    /** The operator stopped the job while this visit was in progress. */
    CANCELLED,

    /** Never attempted, because the job stopped or was abandoned before reaching it. */
    SKIPPED
}
