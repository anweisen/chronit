package net.anweisen.chronit.web.view;

import net.anweisen.chronit.core.state.RunRecord;
import net.anweisen.chronit.core.util.Redactor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Checks that hostile and secret values survive a round trip through the views safely. */
class ViewTest {

    @AfterEach
    void reset() {
        Redactor.clear();
    }

    private static RunRecord runWith(String detail) {
        return new RunRecord("r1", "nightly", "schedule",
                Instant.parse("2026-08-12T18:00:00Z"), Instant.parse("2026-08-12T18:05:00Z"),
                List.of(new RunRecord.VisitRecord("survival", "main",
                        Instant.parse("2026-08-12T18:00:01Z"), Duration.ofMinutes(5),
                        false, detail, 0, 1, 776, false, null, "KICKED")));
    }

    /**
     * A kick reason is free text chosen by a server operator, and it lands straight in the run
     * history. It must never be able to reach into the page.
     */
    @Test
    void kickReasonsCannotInjectMarkup() {
        String html = RunsView.render(List.of(
                runWith("<img src=x onerror=alert(1)> you are banned")));

        assertFalse(html.contains("<img src=x"), html);
        assertTrue(html.contains("&lt;img src=x onerror=alert(1)&gt;"), html);
    }

    @Test
    void serverIdsAreEscapedInChips() {
        RunRecord record = new RunRecord("r1", "nightly", "schedule",
                Instant.parse("2026-08-12T18:00:00Z"), Instant.parse("2026-08-12T18:05:00Z"),
                List.of(new RunRecord.VisitRecord("</span><script>x</script>", "main",
                        Instant.parse("2026-08-12T18:00:01Z"), Duration.ofMinutes(5),
                        true, "ok", 1, 1, 776, false, Duration.ofSeconds(3), "CLIENT_CLOSED")));

        String html = RunsView.render(List.of(record));
        assertFalse(html.contains("<script>x</script>"), html);
    }

    /** A visit that never got in must say so rather than reporting a meaningless "present" time. */
    @Test
    void distinguishesNeverJoinedFromJoinedAndLeft() {
        String neverJoined = RunsView.render(List.of(runWith("Connection refused")));
        assertTrue(neverJoined.contains("never"), neverJoined);
        assertTrue(neverJoined.contains("Gave up after"), neverJoined);

        RunRecord joined = new RunRecord("r2", "nightly", "schedule",
                Instant.parse("2026-08-12T18:00:00Z"), Instant.parse("2026-08-12T18:05:00Z"),
                List.of(new RunRecord.VisitRecord("survival", "main",
                        Instant.parse("2026-08-12T18:00:01Z"), Duration.ofMinutes(5),
                        true, "ran 3 action(s)", 3, 1, 776, false,
                        Duration.ofMillis(3200), "CLIENT_CLOSED")));
        String html = RunsView.render(List.of(joined));
        assertTrue(html.contains("Present"), html);
        assertTrue(html.contains("3s") || html.contains("3200ms"), html);
        assertTrue(html.contains("left cleanly"), "the outcome should read as prose: " + html);
    }

    /**
     * A cancelled visit is a third outcome, not a failure with a nicer message: it should read
     * as stopped, and not be styled as something that went wrong.
     */
    @Test
    void cancelledVisitsReadAsStoppedRatherThanFailed() {
        RunRecord cancelled = new RunRecord("r3", "nightly", "web",
                Instant.parse("2026-08-12T18:00:00Z"), Instant.parse("2026-08-12T18:00:04Z"),
                List.of(new RunRecord.VisitRecord("survival", "main",
                        Instant.parse("2026-08-12T18:00:01Z"), Duration.ofSeconds(4),
                        false, "Cancelled by an operator", 0, 1, -1, false, null, "CANCELLED")));

        String html = RunsView.render(List.of(cancelled));
        assertTrue(html.contains("stopped by operator"), html);
        assertFalse(html.contains("run__visit--failed"),
                "a stopped visit must not be dressed as a failure: " + html);
    }

    /** The design language: labelled values and chips, never a run of dot-separated fragments. */
    @Test
    void rendersLabelledValuesRatherThanDotSeparatedText() {
        String html = RunsView.render(List.of(runWith("Connection refused")));

        assertFalse(html.contains(" · "), "no dot-joined runs should survive: " + html);
        assertTrue(html.contains("datum__label"), html);
        assertTrue(html.contains("datum__value"), html);
        assertTrue(html.contains("class=\"tags\""), html);
    }

    @Test
    void emptyHistoryExplainsItself() {
        String html = RunsView.render(List.of());
        assertTrue(html.contains("Nothing has run yet"), html);
    }

    /**
     * Command payloads reach the dashboard through the job cards, and the most likely payload of
     * all is a server login password.
     */
    @Test
    void registeredSecretsAreMaskedBeforeReachingThePage() {
        Redactor.register("hunter2hunter2");
        String html = RunsView.render(List.of(
                runWith("command failed: /login hunter2hunter2")));

        assertFalse(html.contains("hunter2hunter2"),
                "a password must not survive into the rendered page: " + html);
    }
}
