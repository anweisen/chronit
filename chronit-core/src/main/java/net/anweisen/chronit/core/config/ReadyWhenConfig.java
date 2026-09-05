package net.anweisen.chronit.core.config;

import java.time.Duration;

/**
 * Defines when the client counts as "in the world" and command execution may begin.
 *
 * <p>Reaching the play protocol state is not the same as being in the world: the server still has
 * to send the join packet and an initial teleport, and many networks park arriving players in a
 * limbo or authentication world first. Commands sent before the real world is loaded are silently
 * dropped, so this gate is deliberately conservative.
 *
 * @param spawn     require the join packet plus a confirmed initial teleport (the reliable signal)
 * @param minChunks additionally require this many chunks to have arrived
 * @param chat      additionally require a chat/system message matching this regular expression,
 *                  for servers that announce readiness ("You are now logged in", "Joining survival")
 * @param settle    extra pause after the above are satisfied
 * @param timeout   give up on the visit if readiness is not reached within this time
 */
public record ReadyWhenConfig(
    Boolean spawn,
    Integer minChunks,
    String chat,
    Duration settle,
    Duration timeout) {

  public static final ReadyWhenConfig DEFAULTS = new ReadyWhenConfig(
      Boolean.TRUE,
      0,
      null,
      Duration.ofSeconds(2),
      Duration.ofSeconds(60));

  /** Returns a copy with every unset field taken from {@code base}. */
  public ReadyWhenConfig withFallback(ReadyWhenConfig base) {
    if (base == null) {
      return this;
    }
    return new ReadyWhenConfig(
        spawn != null ? spawn : base.spawn,
        minChunks != null ? minChunks : base.minChunks,
        chat != null ? chat : base.chat,
        settle != null ? settle : base.settle,
        timeout != null ? timeout : base.timeout);
  }
}
