package net.anweisen.chronit.core.auth;

import com.google.gson.JsonObject;
import net.anweisen.chronit.core.config.AuthConfig;

import java.time.Instant;

/**
 * Chronit's own notes about a stored session, kept alongside the library's token JSON.
 *
 * <p>It exists because the one number an unattended deployment most wants — when the account will
 * stop working if left alone — is the one Microsoft never sends. The API reports the access token's
 * expiry, an hour out, and says nothing at all about the refresh token behind it. The refresh
 * token's ninety days can only be counted from the last time it was used, so that instant has to be
 * written down.
 *
 * <p>These fields live under a {@code chronit} key in the same file. The library reads its own keys
 * by name and ignores anything else, so this stays compatible in both directions: a file written by
 * an older version simply has no notes yet.
 */
record SessionMeta(Instant firstLoginAt, Instant lastRefreshAt) {

  /** Key in the token file. Namespaced so it can never collide with the library's own fields. */
  private static final String KEY = "chronit";

  static SessionMeta empty() {
    return new SessionMeta(null, null);
  }

  static SessionMeta loggedInNow() {
    Instant now = Instant.now();
    return new SessionMeta(now, now);
  }

  static SessionMeta read(JsonObject tokenFile) {
    if (tokenFile == null || !tokenFile.has(KEY) || !tokenFile.get(KEY).isJsonObject()) {
      return empty();
    }
    JsonObject json = tokenFile.getAsJsonObject(KEY);
    return new SessionMeta(instant(json, "firstLoginAt"), instant(json, "lastRefreshAt"));
  }

  void writeInto(JsonObject tokenFile) {
    JsonObject json = new JsonObject();
    if (firstLoginAt != null) {
      json.addProperty("firstLoginAt", firstLoginAt.toString());
    }
    if (lastRefreshAt != null) {
      json.addProperty("lastRefreshAt", lastRefreshAt.toString());
    }
    tokenFile.add(KEY, json);
  }

  SessionMeta refreshedAt(Instant when) {
    return new SessionMeta(firstLoginAt != null ? firstLoginAt : when, when);
  }

  /**
   * When the refresh token lapses if nothing uses it before then, or null when this session
   * predates these notes and has not been refreshed since.
   */
  Instant sessionExpiry() {
    return lastRefreshAt != null ? lastRefreshAt.plus(AuthConfig.SESSION_LIFETIME) : null;
  }

  boolean hasLapsed() {
    Instant expiry = sessionExpiry();
    return expiry != null && expiry.isBefore(Instant.now());
  }

  private static Instant instant(JsonObject json, String field) {
    if (!json.has(field) || !json.get(field).isJsonPrimitive()) {
      return null;
    }
    try {
      return Instant.parse(json.get(field).getAsString());
    } catch (RuntimeException e) {
      // A hand-edited or truncated field is not worth failing a run over; the worst case is
      // that the countdown restarts at the next refresh.
      return null;
    }
  }
}
