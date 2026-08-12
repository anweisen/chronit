package dev.chronit.core.driver;

import java.time.Duration;

/**
 * Result of a server list ping.
 *
 * <p>Worth knowing about {@link #protocolVersion()}: a server running ViaVersion reports its own
 * native protocol here, not the range of client versions it will actually accept. A ping therefore
 * tells you what the server *is*, never what it will let in — which is why {@code protocol: auto}
 * attempts a native connection first and only consults this on rejection.
 */
public record ServerStatus(
        String versionName,
        int protocolVersion,
        int onlinePlayers,
        int maxPlayers,
        String description,
        Duration latency) {
}
