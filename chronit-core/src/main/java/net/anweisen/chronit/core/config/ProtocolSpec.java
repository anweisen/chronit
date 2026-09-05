package net.anweisen.chronit.core.config;

import java.util.Locale;

/**
 * The {@code protocol:} setting on a server.
 *
 * <p>Deliberately does not ship a table of version name to protocol number. Such a table rots with
 * every Minecraft release, and there is already an authoritative one: ViaVersion's registry, which
 * the optional translation module exposes. So a name is resolved by the driver (which knows its own
 * native version) or by the translation provider, and a numeric protocol id always works.
 */
public sealed interface ProtocolSpec {

  /** Connect natively, and fall back to translation only if the server rejects the version. */
  record Auto() implements ProtocolSpec {
  }

  /** Speak exactly this protocol number, translating if it is not the driver's native one. */
  record Exact(int protocol) implements ProtocolSpec {
  }

  /** Speak this named version, resolved at connect time. */
  record Named(String version) implements ProtocolSpec {
  }

  ProtocolSpec AUTO = new Auto();

  /**
   * Parses the configured value.
   *
   * @param raw {@code null} or {@code "auto"}, a protocol number such as {@code "776"}, or a
   *            version name such as {@code "1.20.4"}
   */
  static ProtocolSpec parse(String raw) {
    if (raw == null || raw.isBlank() || raw.trim().equalsIgnoreCase("auto")) {
      return AUTO;
    }
    String text = raw.trim().toLowerCase(Locale.ROOT);
    if (text.matches("\\d+")) {
      return new Exact(Integer.parseInt(text));
    }
    return new Named(raw.trim());
  }

  default String describe() {
    return switch (this) {
      case Auto ignored -> "auto";
      case Exact exact -> "protocol " + exact.protocol();
      case Named named -> named.version();
    };
  }
}
