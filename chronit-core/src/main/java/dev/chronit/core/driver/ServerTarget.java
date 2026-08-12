package dev.chronit.core.driver;

import dev.chronit.core.config.ProtocolSpec;
import dev.chronit.core.config.ProxyConfig;
import dev.chronit.core.config.ServerConfig;

/** Where to connect, and which protocol version to speak once there. */
public record ServerTarget(
        String host,
        int port,
        ProtocolSpec protocol,
        ProxyConfig proxy) {

    public static ServerTarget of(ServerConfig server) {
        return new ServerTarget(
                server.host(),
                server.portOrDefault(),
                ProtocolSpec.parse(server.protocol()),
                server.proxy());
    }

    /** Same target, but pinned to a specific protocol — used for the translated retry. */
    public ServerTarget withProtocol(int protocolVersion) {
        return new ServerTarget(host, port, new ProtocolSpec.Exact(protocolVersion), proxy);
    }

    public String address() {
        return host + ":" + port;
    }
}
