package dev.chronit.core.driver;

import java.security.PrivateKey;
import java.security.PublicKey;
import java.time.Instant;

/**
 * The key pair Mojang issues for signing chat messages.
 *
 * <p>Needed because {@code enforce-secure-profile=true} is the default in a vanilla
 * {@code server.properties}: such a server rejects chat from a client that never announced a chat
 * session. Commands are unaffected.
 *
 * @param publicKeySignature Mojang's signature over the public key, which the server verifies
 * @param expiresAt          certificates are short-lived and re-fetched with the access token
 */
public record PlayerCertificates(
        PublicKey publicKey,
        PrivateKey privateKey,
        byte[] publicKeySignature,
        Instant expiresAt) {

    public boolean isUsable() {
        return publicKey != null
                && privateKey != null
                && publicKeySignature != null
                && publicKeySignature.length > 0
                && expiresAt != null
                && expiresAt.isAfter(Instant.now());
    }
}
