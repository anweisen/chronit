package net.anweisen.chronit.core.config;

import java.nio.file.Path;
import java.time.Duration;

/**
 * How to respond when a server pushes a resource pack.
 *
 * <p>Servers configured with {@code require-resource-pack=true} terminate the connection when a
 * client declines, so the default is to accept. Every field is nullable: null means "inherit from
 * defaults".
 *
 * @param mode          acceptance strategy
 * @param strict        in {@link Mode#DOWNLOAD}, report a failure when the downloaded bytes do not
 *                      match the server-supplied SHA-1 instead of accepting anyway
 * @param downloadDelay in {@link Mode#FAKE}, how long to pretend the download took
 * @param applyDelay    how long to pretend applying the pack took, in both modes
 * @param cacheDir      where downloaded packs are cached, keyed by hash
 * @param maxSizeMb     refuse to download packs larger than this
 * @param httpTimeout   per-request timeout for pack downloads
 */
public record ResourcePackConfig(
    Mode mode,
    Boolean strict,
    Duration downloadDelay,
    Duration applyDelay,
    Path cacheDir,
    Integer maxSizeMb,
    Duration httpTimeout) {

  public enum Mode {
    /**
     * Never fetch anything; report the full vanilla success sequence after plausible delays.
     * Cheapest and works everywhere, but the timing is invented.
     */
    FAKE,
    /**
     * Actually download the pack and report real elapsed time. Slower and uses bandwidth, but
     * a server-side plugin comparing pack size against how long the client took sees
     * consistent numbers.
     */
    DOWNLOAD,
    /**
     * Decline. Expect to be kicked when the pack is marked required — this exists mainly for
     * diagnosing whether a pack is the cause of a failed join.
     */
    DECLINE
  }

  public static final ResourcePackConfig DEFAULTS = new ResourcePackConfig(
      Mode.FAKE,
      Boolean.FALSE,
      Duration.ofMillis(1500),
      Duration.ofMillis(800),
      Path.of("/data/packs"),
      250,
      Duration.ofSeconds(60));

  /** Returns a copy with every unset field taken from {@code base}. */
  public ResourcePackConfig withFallback(ResourcePackConfig base) {
    if (base == null) {
      return this;
    }
    return new ResourcePackConfig(
        mode != null ? mode : base.mode,
        strict != null ? strict : base.strict,
        downloadDelay != null ? downloadDelay : base.downloadDelay,
        applyDelay != null ? applyDelay : base.applyDelay,
        cacheDir != null ? cacheDir : base.cacheDir,
        maxSizeMb != null ? maxSizeMb : base.maxSizeMb,
        httpTimeout != null ? httpTimeout : base.httpTimeout);
  }
}
