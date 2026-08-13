package dev.chronit.core.driver;

import java.util.Locale;

/**
 * Why a session ended.
 *
 * <p>The {@link Kind} classification exists mainly so {@code protocol: auto} can tell a version
 * rejection apart from an ordinary kick and decide whether retrying through the translation layer
 * is worth it — and so failures in the run history say something more useful than "disconnected".
 */
public record DisconnectInfo(
        Kind kind,
        String reason,
        Throwable cause) {

    public enum Kind {
        /** We closed the connection ourselves, as planned. */
        CLIENT_CLOSED,
        /** An operator stopped the job while it was running. */
        CANCELLED,
        /** The server rejected our protocol version. */
        VERSION_MISMATCH,
        /** Authentication or session validation failed. */
        AUTH_FAILED,
        /** Refused over the resource pack — declined, or the pack failed and was required. */
        RESOURCE_PACK,
        /** Kicked for any other reason the server stated: full, banned, whitelist, anti-bot. */
        KICKED,
        /** Readiness was not reached in time. */
        TIMEOUT,
        /** Socket-level failure. */
        NETWORK,
        UNKNOWN
    }

    public static DisconnectInfo clientClosed(String reason) {
        return new DisconnectInfo(Kind.CLIENT_CLOSED, reason, null);
    }

    /**
     * Classifies a server-supplied kick message.
     *
     * <p>There is no machine-readable reason code in the protocol — the server sends a chat
     * component meant for a human — so this matches on the vanilla translation strings and the
     * phrasings the widely deployed proxies and version-compatibility plugins use.
     */
    public static DisconnectInfo fromKick(String reason, Throwable cause) {
        String text = reason == null ? "" : reason.toLowerCase(Locale.ROOT);

        if (text.contains("outdated client") || text.contains("outdated server")
                || text.contains("incompatible") || text.contains("version mismatch")
                || text.contains("multiplayer.disconnect.outdated")
                || (text.contains("version") && (text.contains("not supported") || text.contains("unsupported")))
                || (text.contains("please use") && text.contains("connect"))) {
            return new DisconnectInfo(Kind.VERSION_MISMATCH, reason, cause);
        }
        if (text.contains("resource pack") || text.contains("resourcepack")) {
            return new DisconnectInfo(Kind.RESOURCE_PACK, reason, cause);
        }
        if (text.contains("authentic") || text.contains("not verified") || text.contains("session")
                || text.contains("invalid signature") || text.contains("unverified username")
                || text.contains("failed to log in") || text.contains("chat validation")) {
            return new DisconnectInfo(Kind.AUTH_FAILED, reason, cause);
        }
        if (reason != null && !reason.isBlank()) {
            return new DisconnectInfo(Kind.KICKED, reason, cause);
        }
        return new DisconnectInfo(Kind.UNKNOWN, reason, cause);
    }

    /**
     * Classifies a failure that carries an exception.
     *
     * <p>A server kick arrives as a packet and never has a {@link Throwable} attached, so a cause
     * means something failed locally. The accompanying reason in that case is a bare translation
     * key — {@code disconnect.genericReason} and the like — which tells an operator nothing, while
     * the exception says "Connection refused". So the exception text is what gets classified and
     * reported, and anything unrecognised is a network failure rather than a kick.
     */
    public static DisconnectInfo fromCause(Throwable cause) {
        String message = rootMessage(cause);
        DisconnectInfo classified = fromKick(message, cause);
        return classified.kind() == Kind.KICKED || classified.kind() == Kind.UNKNOWN
                ? new DisconnectInfo(Kind.NETWORK, message, cause)
                : classified;
    }

    private static String rootMessage(Throwable cause) {
        Throwable current = cause;
        while (current.getCause() != null && current.getCause() != current) {
            current = current.getCause();
        }
        String message = current.getMessage();
        return message != null && !message.isBlank()
                ? current.getClass().getSimpleName() + ": " + message
                : current.getClass().getSimpleName();
    }

    /** True when retrying through a translation layer could plausibly help. */
    public boolean suggestsTranslation() {
        return kind == Kind.VERSION_MISMATCH;
    }

    public String describe() {
        String detail = reason != null && !reason.isBlank() ? reason
                : cause != null ? String.valueOf(cause.getMessage()) : "no reason given";
        return kind + ": " + detail;
    }
}
