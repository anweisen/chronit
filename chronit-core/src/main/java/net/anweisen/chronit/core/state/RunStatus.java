package net.anweisen.chronit.core.state;

/**
 * How a run ended.
 *
 * <p>Previously this was a single boolean — every visit succeeded, or something did not — which
 * collapsed three genuinely different situations into one. A job whose second of five visits was
 * kicked is not the same as a job that could not reach any server, and neither is a job an operator
 * stopped on purpose. Presenting all three as "failed" trains people to ignore the word.
 */
public enum RunStatus {

  /** Every visit reached its server and did what it was asked. */
  SUCCEEDED,

  /** Some visits worked and some did not. The job ran; the schedule is only partly served. */
  PARTIAL,

  /** Nothing succeeded. */
  FAILED,

  /** An operator stopped it. Not a fault, and never coloured as one. */
  CANCELLED,

  /** The overlap policy dropped this fire time because the previous run was still going. */
  SKIPPED;

  /** True for the two states that mean something went wrong on its own. */
  public boolean isProblem() {
    return this == PARTIAL || this == FAILED;
  }
}
