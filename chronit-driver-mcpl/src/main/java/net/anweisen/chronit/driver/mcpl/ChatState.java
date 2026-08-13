package net.anweisen.chronit.driver.mcpl;

import net.anweisen.chronit.core.config.SecureChatMode;
import net.anweisen.chronit.core.driver.AuthContext;
import net.anweisen.chronit.core.driver.PlayerCertificates;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatAckPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatSessionUpdatePacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Signature;
import java.time.Instant;
import java.util.BitSet;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Chat acknowledgement bookkeeping and, where possible, message signing.
 *
 * <p>Two separate concerns live here, both of which get a session disconnected if neglected:
 *
 * <ul>
 *   <li><b>Acknowledgements.</b> Since 1.19.1 the server counts the signed messages it has sent and
 *       expects the client to acknowledge them. Let that count run away and the server closes the
 *       connection over a chat validation failure — which, on a busy server, happens within a minute
 *       of joining whether or not the bot ever says anything.
 *   <li><b>Signing.</b> {@code enforce-secure-profile=true} is the default in a vanilla
 *       {@code server.properties}, and those servers drop unsigned chat. Commands are exempt: the
 *       command packet carries no signature at all, so the configured command sequences work
 *       regardless of what happens here.
 * </ul>
 */
final class ChatState {

    private static final Logger log = LoggerFactory.getLogger(ChatState.class);

    /**
     * Acknowledge after this many received messages. The protocol tracks the last 20, so staying
     * at or below the window size cannot overrun it. Acknowledging more often than vanilla is
     * harmless; acknowledging more than was received is a protocol error, so the count is exact.
     */
    private static final int ACK_THRESHOLD = 20;

    /** Signed messages carry a fixed-width RSA signature. */
    private static final int SIGNATURE_LENGTH = 256;

    private final Session session;
    private final AuthContext auth;
    private final SecureChatMode mode;
    private final UUID chatSessionId = UUID.randomUUID();

    private final AtomicInteger pendingAcknowledgements = new AtomicInteger();
    private final AtomicInteger messageIndex = new AtomicInteger();

    private volatile boolean signingAvailable;

    ChatState(Session session, AuthContext auth, SecureChatMode mode) {
        this.session = session;
        this.auth = auth;
        this.mode = mode;
    }

    /**
     * Announces a chat session, which is what allows the server to verify our signatures.
     *
     * @return false when signing was requested but no usable certificate is available
     */
    boolean establish() {
        if (mode == SecureChatMode.OFF) {
            log.debug("Secure chat disabled by configuration; plain chat will be rejected by servers "
                    + "with enforce-secure-profile enabled");
            return true;
        }

        PlayerCertificates certificates = auth.certificates();
        if (certificates == null || !certificates.isUsable()) {
            if (mode == SecureChatMode.ON) {
                log.error("secureChat is 'on' but no usable player certificate is available "
                        + "({}). Offline accounts cannot sign chat.",
                        certificates == null ? "none fetched" : "expired");
                return false;
            }
            log.debug("No usable player certificate; continuing without a chat session");
            return true;
        }

        session.send(new ServerboundChatSessionUpdatePacket(
                chatSessionId,
                certificates.expiresAt().toEpochMilli(),
                certificates.publicKey(),
                certificates.publicKeySignature()));
        signingAvailable = true;
        log.debug("Announced chat session {} (certificate expires {})", chatSessionId, certificates.expiresAt());
        return true;
    }

    /**
     * Records that a signed message arrived, acknowledging in batches.
     *
     * <p>Called from the network thread, so it does no work beyond a counter and an occasional
     * small packet.
     */
    void onSignedMessageReceived() {
        if (pendingAcknowledgements.incrementAndGet() >= ACK_THRESHOLD) {
            flushAcknowledgements();
        }
    }

    /** Acknowledges everything received so far and returns how many that was. */
    private int takePending() {
        return pendingAcknowledgements.getAndSet(0);
    }

    void flushAcknowledgements() {
        int count = takePending();
        if (count > 0 && session.isConnected()) {
            session.send(new ServerboundChatAckPacket(count));
            log.trace("Acknowledged {} chat message(s)", count);
        }
    }

    /**
     * Sends a command.
     *
     * <p>Uses the unsigned command packet, which carries no acknowledgement or signature fields at
     * all. That is why command sequences work even on servers enforcing secure profiles, and why
     * nothing above applies here.
     *
     * @param command without the leading slash
     */
    void sendCommand(String command) {
        session.send(new ServerboundChatCommandPacket(command));
    }

    /** Sends a plain chat message, signed when a chat session was established. */
    void sendChat(String message) {
        long timestamp = Instant.now().toEpochMilli();
        long salt = ThreadLocalRandom.current().nextLong();
        // Acknowledgements ride along with the message rather than needing a separate packet.
        int offset = takePending();
        byte[] signature = signingAvailable ? sign(message, timestamp, salt) : null;

        session.send(new ServerboundChatPacket(
                message,
                timestamp,
                salt,
                signature,
                offset,
                new BitSet(20),
                0));
    }

    /**
     * Produces the signature the server expects for a chat message.
     *
     * <p>The signed payload is the 1.19.3+ layout: a version marker, the sender and chat session
     * ids, a per-session message index, the salt, the timestamp in seconds, the message bytes, and
     * the previously seen signatures. We acknowledge without claiming to have seen specific
     * messages, so that last list is always empty.
     *
     * @return the signature, or null if signing failed — the message is then sent unsigned, which
     *         a non-enforcing server still accepts
     */
    private byte[] sign(String message, long timestampMillis, long salt) {
        PlayerCertificates certificates = auth.certificates();
        if (certificates == null || !certificates.isUsable()) {
            return null;
        }
        try {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream(256);
            try (DataOutputStream out = new DataOutputStream(buffer)) {
                out.writeInt(1);
                writeUuid(out, auth.uuid());
                writeUuid(out, chatSessionId);
                out.writeInt(messageIndex.getAndIncrement());
                out.writeLong(salt);
                out.writeLong(timestampMillis / 1000L);
                byte[] content = message.getBytes(StandardCharsets.UTF_8);
                out.writeInt(content.length);
                out.write(content);
                out.writeInt(0);
            }

            Signature signer = Signature.getInstance("SHA256withRSA");
            signer.initSign(certificates.privateKey());
            signer.update(buffer.toByteArray());
            byte[] signature = signer.sign();

            if (signature.length != SIGNATURE_LENGTH) {
                log.warn("Chat signature is {} bytes, expected {}; sending unsigned",
                        signature.length, SIGNATURE_LENGTH);
                return null;
            }
            return signature;
        } catch (GeneralSecurityException | IOException e) {
            log.warn("Could not sign chat message, sending it unsigned: {}", e.toString());
            return null;
        }
    }

    private static void writeUuid(DataOutputStream out, UUID uuid) throws IOException {
        out.writeLong(uuid.getMostSignificantBits());
        out.writeLong(uuid.getLeastSignificantBits());
    }
}
