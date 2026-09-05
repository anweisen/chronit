package net.anweisen.chronit.core.util;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Randomises delays by a configured fraction.
 *
 * <p>Every timing in a session — command spacing, resource pack "download" time, the idle
 * movement loop — runs through here so that repeated runs are not byte-for-byte identical.
 * A real client's timings vary; a bot whose every run is identical to the millisecond is
 * needlessly conspicuous in server logs.
 */
public final class Jitter {

  private final double fraction;

  /**
   * @param fraction relative spread, e.g. {@code 0.15} means ±15%. Clamped to [0, 1].
   */
  public Jitter(double fraction) {
    this.fraction = Math.clamp(fraction, 0.0d, 1.0d);
  }

  /** A jitter that returns durations unchanged. */
  public static Jitter none() {
    return new Jitter(0.0d);
  }

  public double fraction() {
    return fraction;
  }

  /** Returns {@code base} scaled by a random factor in {@code [1-fraction, 1+fraction]}. */
  public Duration apply(Duration base) {
    if (base == null || base.isZero() || base.isNegative() || fraction == 0.0d) {
      return base;
    }
    double factor = 1.0d + ThreadLocalRandom.current().nextDouble(-fraction, fraction);
    long millis = Math.max(0L, Math.round(base.toMillis() * factor));
    return Duration.ofMillis(millis);
  }

  /** Returns a random value in {@code [-magnitude, magnitude]}, for positional noise. */
  public static double noise(double magnitude) {
    if (magnitude <= 0.0d) {
      return 0.0d;
    }
    return ThreadLocalRandom.current().nextDouble(-magnitude, magnitude);
  }
}
