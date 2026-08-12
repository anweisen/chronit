package dev.chronit.core.config;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.time.Duration;

/**
 * One step in a command sequence. Exactly one of {@code command}, {@code chat} or {@code wait}
 * must be set.
 *
 * @param command    a command without the leading slash, e.g. {@code "warp daily"}
 * @param chat       a plain chat message
 * @param pause      the config's {@code wait:} key — pause for this long and do nothing else.
 *                   Named {@code pause} because a record component cannot be called {@code wait}
 *                   without colliding with {@link Object#wait()}
 * @param waitFor    block until a matching message arrives before continuing
 * @param delayAfter pause after this step, before the next one
 */
public record ActionConfig(
        String command,
        String chat,
        @JsonProperty("wait") Duration pause,
        WaitForConfig waitFor,
        Duration delayAfter) {

    public Kind kind() {
        if (command != null) {
            return Kind.COMMAND;
        }
        if (chat != null) {
            return Kind.CHAT;
        }
        return Kind.WAIT;
    }

    public enum Kind { COMMAND, CHAT, WAIT }

    /** Short description for logs, with the payload omitted so passwords are not echoed. */
    public String describe() {
        return switch (kind()) {
            case COMMAND -> "command /" + command;
            case CHAT -> "chat message";
            case WAIT -> "wait";
        };
    }
}
