package net.anweisen.chronit.core.driver;

import java.time.Instant;

/**
 * An incoming message, flattened to plain text.
 *
 * <p>Chat arrives as a rich component tree; matching {@code waitFor} patterns against the rendered
 * plain text is what users expect, since that is what they see in game. The original JSON is kept
 * for the log and the web interface.
 */
public record ChatLine(
        Source source,
        String plainText,
        String json,
        Instant at) {

    public enum Source {
        /** A signed message from another player. */
        PLAYER,
        /** Server messages, command output, most plugin output. */
        SYSTEM,
        /** A system message flagged as overlay — the text above the hotbar. */
        ACTION_BAR,
        /** Server-sent chat attributed to a player but not signed. */
        DISGUISED
    }

    public static ChatLine of(Source source, String plainText, String json) {
        return new ChatLine(source, plainText == null ? "" : plainText, json, Instant.now());
    }
}
