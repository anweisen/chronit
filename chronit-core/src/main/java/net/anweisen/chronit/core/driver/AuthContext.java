package net.anweisen.chronit.core.driver;

import java.util.UUID;

/**
 * A resolved identity ready to join with.
 *
 * <p>Only plain JDK types cross this boundary, so the driver has no dependency on how the account
 * was authenticated.
 *
 * @param online       when false the join is unauthenticated: no encryption handshake and no session
 *                     server call, which only works against {@code online-mode=false} servers
 * @param accessToken  Minecraft services token, null when offline
 * @param certificates key material for signed chat, null when unavailable
 */
public record AuthContext(
        String username,
        UUID uuid,
        boolean online,
        String accessToken,
        PlayerCertificates certificates) {

    /**
     * Builds an offline identity, deriving the UUID exactly as the vanilla server does for
     * {@code online-mode=false} so the bot keeps a stable identity across runs.
     */
    public static AuthContext offline(String username) {
        UUID uuid = UUID.nameUUIDFromBytes(
                ("OfflinePlayer:" + username).getBytes(java.nio.charset.StandardCharsets.UTF_8));
        return new AuthContext(username, uuid, false, null, null);
    }

    public boolean canSignChat() {
        return certificates != null && certificates.isUsable();
    }
}
