package net.anweisen.chronit.core.config;

import java.time.Duration;
import java.util.List;

/**
 * One server visit inside a job: connect as an account, run a command sequence, linger, leave.
 *
 * @param server   id of a configured server
 * @param account  id of a configured account
 * @param stayFor  how long to remain connected after the ready sequence finishes. The idle
 *                 movement loop runs for this whole period
 * @param onReady  commands to run once the client is in the world
 * @param onLeave  commands to run just before disconnecting, e.g. a logout command
 * @param onFail   retry policy
 * @param gapAfter pause before the next visit, giving the previous session time to be torn down
 *                 server-side before the same account reconnects elsewhere
 */
public record VisitConfig(
    String server,
    String account,
    Duration stayFor,
    List<ActionConfig> onReady,
    List<ActionConfig> onLeave,
    RetryConfig onFail,
    Duration gapAfter) {

  public Duration stayForOrDefault() {
    return stayFor != null ? stayFor : Duration.ZERO;
  }

  public Duration gapAfterOrDefault() {
    return gapAfter != null ? gapAfter : Duration.ofSeconds(5);
  }

  public List<ActionConfig> onReadyOrEmpty() {
    return onReady != null ? onReady : List.of();
  }

  public List<ActionConfig> onLeaveOrEmpty() {
    return onLeave != null ? onLeave : List.of();
  }
}
