package dev.chronit.web.view;

import dev.chronit.core.state.RunRecord;
import dev.chronit.core.util.Redactor;
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
                        false, detail, 0, 1, 776, false)));
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
                        true, "ok", 1, 1, 776, false)));

        String html = RunsView.render(List.of(record));
        assertFalse(html.contains("<script>x</script>"), html);
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
