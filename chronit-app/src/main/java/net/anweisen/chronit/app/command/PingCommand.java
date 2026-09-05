package net.anweisen.chronit.app.command;

import net.anweisen.chronit.core.config.ChronitConfig;
import net.anweisen.chronit.core.config.ProtocolSpec;
import net.anweisen.chronit.core.config.ServerConfig;
import net.anweisen.chronit.core.driver.DriverException;
import net.anweisen.chronit.core.driver.ServerStatus;
import net.anweisen.chronit.core.driver.ServerTarget;
import net.anweisen.chronit.core.util.Durations;
import net.anweisen.chronit.driver.mcpl.McplDriver;
import picocli.CommandLine;

import java.time.Duration;
import java.util.concurrent.Callable;

/**
 * Server list ping.
 *
 * <p>Handy for checking a server is reachable before scheduling against it. Note the caveat printed
 * with the result: the protocol shown is what the server <em>is</em>, not the range of client
 * versions it will accept, so a mismatch here does not necessarily mean a join would fail.
 */
@CommandLine.Command(
    name = "ping",
    description = "Query a server's status without logging in.")
public final class PingCommand implements Callable<Integer> {

  @CommandLine.Mixin
  ConfigMixin configMixin;

  @CommandLine.Parameters(index = "0",
      description = "Server id from the configuration, or a host[:port].")
  String server;

  @CommandLine.Option(names = "--timeout", description = "How long to wait. Default: 10s.")
  String timeout = "10s";

  @Override
  public Integer call() {
    ServerTarget target = resolveTarget();
    McplDriver driver = new McplDriver();
    try {
      ServerStatus status = driver.ping(target, Durations.parse(timeout));

      System.out.println(target.address());
      System.out.printf("  version   %s (protocol %d)%n", status.versionName(), status.protocolVersion());
      System.out.printf("  players   %d / %d%n", status.onlinePlayers(), status.maxPlayers());
      System.out.printf("  latency   %s%n", Durations.format(status.latency()));
      System.out.printf("  motd      %s%n", status.description().replace("\n", "\n            "));
      System.out.println();

      if (status.protocolVersion() == McplDriver.NATIVE_PROTOCOL) {
        System.out.println("This client speaks the same protocol; a direct connection should work.");
      } else {
        System.out.printf("This client speaks protocol %d (Minecraft %s). Many servers accept newer "
                + "clients than they advertise, so a direct connection is still worth trying — "
                + "that is what protocol: auto does.%n",
            McplDriver.NATIVE_PROTOCOL, McplDriver.NATIVE_VERSION);
      }
      return 0;
    } catch (DriverException e) {
      System.err.println("Ping failed: " + e.getMessage());
      return CommandLine.ExitCode.SOFTWARE;
    } finally {
      driver.shutdown();
    }
  }

  private ServerTarget resolveTarget() {
    // A raw host:port is accepted so the command is usable before anything is configured.
    if (server.contains(".") || server.contains(":")) {
      ChronitConfig config = tryLoad();
      if (config != null && config.server(server).isPresent()) {
        return ServerTarget.of(config.server(server).orElseThrow());
      }
      String[] parts = server.split(":", 2);
      int port = parts.length > 1 ? Integer.parseInt(parts[1]) : ServerConfig.DEFAULT_PORT;
      return new ServerTarget(parts[0], port, ProtocolSpec.AUTO, null);
    }

    ChronitConfig config = configMixin.load();
    ServerConfig configured = config.server(server).orElseThrow(() ->
        new IllegalArgumentException("No server with id '" + server + "'. Configured servers: "
            + config.serversOrEmpty().stream().map(ServerConfig::id).toList()));
    return ServerTarget.of(configured);
  }

  private ChronitConfig tryLoad() {
    try {
      return configMixin.load();
    } catch (RuntimeException e) {
      return null;
    }
  }
}
