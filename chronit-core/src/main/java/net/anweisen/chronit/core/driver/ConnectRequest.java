package net.anweisen.chronit.core.driver;

/**
 * Everything a driver needs to open one session.
 *
 * @param protocolVersion the protocol to speak, already resolved. Equal to the driver's native
 *                        version for a direct connection; anything else requires a translation
 *                        provider and sets {@code translated}
 */
public record ConnectRequest(
        ServerTarget target,
        AuthContext auth,
        SessionSettings settings,
        int protocolVersion,
        boolean translated) {
}
