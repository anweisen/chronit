package dev.chronit.core.auth;

import java.time.Instant;

/**
 * What is known about an account without contacting Microsoft.
 *
 * @param state      whether it is usable right now
 * @param username   profile name, once one has been fetched
 * @param tokenExpiry when the cached Minecraft token stops being valid; it is refreshed
 *                    automatically well before this matters
 */
public record AccountStatus(
        String id,
        State state,
        String username,
        Instant tokenExpiry,
        String detail) {

    public enum State {
        /** Offline account: nothing to authenticate. */
        OFFLINE,
        /** A stored session exists and can be refreshed. */
        READY,
        /** No stored session, or the refresh token has expired. Needs {@code chronit login}. */
        NEEDS_LOGIN,
        /** A stored session exists but the last refresh failed for another reason. */
        ERROR
    }

    public boolean isUsable() {
        return state == State.OFFLINE || state == State.READY;
    }
}
