package dev.chronit.core.auth;

import java.time.Instant;

/**
 * What is known about an account without contacting Microsoft.
 *
 * @param state         whether it is usable right now
 * @param username      profile name, once one has been fetched
 * @param tokenExpiry   when the cached Minecraft token stops being valid. Rarely interesting: it is
 *                      refreshed automatically well before it matters
 * @param sessionExpiry when the account would stop working if nothing touched it again — the number
 *                      that actually decides whether a person has to sit down at a browser. Null
 *                      when the session has not been refreshed since it was stored
 * @param lastRefresh   when the Microsoft session was last renewed
 */
public record AccountStatus(
        String id,
        State state,
        String username,
        Instant tokenExpiry,
        Instant sessionExpiry,
        Instant lastRefresh,
        String detail) {

    public enum State {
        /** Offline account: nothing to authenticate. */
        OFFLINE,
        /** A stored session exists and can be refreshed. */
        READY,
        /** No stored session, or it can no longer be renewed. Needs {@code chronit login}. */
        NEEDS_LOGIN,
        /**
         * A stored session exists but something is wrong with it that a login would not fix — an
         * unreadable token file, an Xbox restriction, or a refresh that keeps failing.
         */
        ERROR
    }

    public static AccountStatus offline(String id, String username) {
        return new AccountStatus(id, State.OFFLINE, username, null, null, null, "offline mode");
    }

    public static AccountStatus needsLogin(String id, String detail) {
        return new AccountStatus(id, State.NEEDS_LOGIN, null, null, null, null, detail);
    }

    public static AccountStatus error(String id, String detail) {
        return new AccountStatus(id, State.ERROR, null, null, null, null, detail);
    }

    public boolean isUsable() {
        return state == State.OFFLINE || state == State.READY;
    }
}
