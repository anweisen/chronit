package net.anweisen.chronit.core.auth;

import com.google.gson.JsonObject;
import net.anweisen.chronit.core.config.AuthConfig;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SessionMetaTest {

    @Test
    void roundTripsThroughTheTokenFile() {
        Instant login = Instant.parse("2026-01-01T00:00:00Z");
        Instant refresh = Instant.parse("2026-03-01T12:00:00Z");

        JsonObject file = new JsonObject();
        new SessionMeta(login, refresh).writeInto(file);
        SessionMeta read = SessionMeta.read(file);

        assertEquals(login, read.firstLoginAt());
        assertEquals(refresh, read.lastRefreshAt());
    }

    /**
     * The notes sit inside the library's own JSON, so they have to stay out of its way: it reads
     * its fields by name and never sees this one.
     */
    @Test
    void keepsItselfUnderOneNamespacedKey() {
        JsonObject file = new JsonObject();
        file.addProperty("msaToken", "the library's own field");

        SessionMeta.loggedInNow().writeInto(file);

        assertEquals(2, file.size());
        assertTrue(file.has("chronit"));
        assertEquals("the library's own field", file.get("msaToken").getAsString());
    }

    /** Token files written before these notes existed must keep working. */
    @Test
    void readsAFileWithNoNotesAtAll() {
        SessionMeta meta = SessionMeta.read(new JsonObject());

        assertNull(meta.firstLoginAt());
        assertNull(meta.lastRefreshAt());
        assertNull(meta.sessionExpiry());
        assertFalse(meta.hasLapsed());
    }

    @Test
    void countsTheSessionWindowFromTheLastRefresh() {
        Instant refresh = Instant.parse("2026-05-01T00:00:00Z");

        SessionMeta meta = SessionMeta.empty().refreshedAt(refresh);

        assertEquals(refresh.plus(AuthConfig.SESSION_LIFETIME), meta.sessionExpiry());
        // A first refresh on a session with no notes also establishes when it started.
        assertEquals(refresh, meta.firstLoginAt());
    }

    @Test
    void keepsTheOriginalLoginInstantAcrossRefreshes() {
        Instant login = Instant.parse("2026-01-01T00:00:00Z");
        Instant later = Instant.parse("2026-04-01T00:00:00Z");

        SessionMeta meta = new SessionMeta(login, login).refreshedAt(later);

        assertEquals(login, meta.firstLoginAt());
        assertEquals(later, meta.lastRefreshAt());
    }

    @Test
    void noticesASessionLeftAloneTooLong() {
        Instant longAgo = Instant.now().minus(AuthConfig.SESSION_LIFETIME).minus(1, ChronoUnit.DAYS);

        assertTrue(SessionMeta.empty().refreshedAt(longAgo).hasLapsed());
        assertFalse(SessionMeta.empty().refreshedAt(Instant.now()).hasLapsed());
    }

    @Test
    void toleratesAHandEditedField() {
        JsonObject notes = new JsonObject();
        notes.addProperty("lastRefreshAt", "yesterday, probably");
        JsonObject file = new JsonObject();
        file.add("chronit", notes);

        // Worst case the countdown restarts at the next refresh, which is not worth failing a run.
        assertNull(SessionMeta.read(file).lastRefreshAt());
    }
}
