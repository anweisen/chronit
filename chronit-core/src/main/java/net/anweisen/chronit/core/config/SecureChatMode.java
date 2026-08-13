package net.anweisen.chronit.core.config;

/**
 * Whether to establish a signed chat session on join.
 *
 * <p>{@code enforce-secure-profile=true} is the default in a vanilla {@code server.properties}, and
 * such servers reject unsigned chat messages. Commands are unaffected — they go out on a packet
 * that carries no signature — so this only matters for visits that send plain chat.
 */
public enum SecureChatMode {
    /** Establish a chat session when the account has usable certificates, otherwise carry on. */
    AUTO,
    /** Always establish a chat session; fail the visit if certificates are unavailable. */
    ON,
    /** Never establish a chat session. Plain chat will be rejected by enforcing servers. */
    OFF
}
