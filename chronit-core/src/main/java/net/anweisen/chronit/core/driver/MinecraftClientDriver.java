package net.anweisen.chronit.core.driver;

import java.time.Duration;

/**
 * The seam between scheduling/orchestration and the Minecraft protocol.
 *
 * <p>Everything version-specific lives behind this interface. Nothing in {@code chronit-core}
 * references a protocol library, which is what makes it possible to replace the implementation —
 * with a different library, a hand-written codec, or a build targeting another Minecraft version —
 * without touching configuration, scheduling or the command runner.
 *
 * <p>A driver speaks exactly one native protocol version. Reaching other versions is the job of a
 * translation layer, discovered separately and optional.
 */
public interface MinecraftClientDriver {

  /** Stable identifier for logs, e.g. {@code mcpl-26.2}. */
  String id();

  /** The Minecraft version this driver natively speaks, e.g. {@code 26.2}. */
  String nativeVersionName();

  /** The protocol number this driver natively speaks. */
  int nativeProtocol();

  /**
   * Performs a server list ping.
   *
   * <p>Note that the reported protocol is the server's own, not the range it accepts: a server
   * running a version-compatibility plugin advertises its native version while happily accepting
   * far newer clients.
   */
  ServerStatus ping(ServerTarget target, Duration timeout) throws DriverException;

  /**
   * Opens a session. Returns as soon as the connection attempt has started; use
   * {@link ClientHandle#whenReady()} to await the join.
   */
  ClientHandle connect(ConnectRequest request, ClientEvents events) throws DriverException;

  /** Releases shared resources such as event loop groups. */
  void shutdown();
}
