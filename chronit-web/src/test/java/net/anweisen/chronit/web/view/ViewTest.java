package net.anweisen.chronit.web.view;

import net.anweisen.chronit.core.state.RunRecord;
import net.anweisen.chronit.core.state.RunStatus;
import net.anweisen.chronit.core.state.VisitStatus;
import net.anweisen.chronit.core.util.Redactor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
    assertEquals(RunStatus.CANCELLED, cancelled.status());
    assertTrue(html.contains("stopped by operator"), html);
    assertTrue(html.contains("is-stop"), html);
    assertFalse(html.contains("is-bad"),
        "a stopped visit must not be coloured as a failure: " + html);
  }

  /**
   * The design language: labelled values and a marked state word, never a run of dot-separated
   * fragments and never a capsule with a background.
   */
  @Test
  void rendersLabelledValuesRatherThanDotSeparatedText() {
    String html = RunsView.render(List.of(runWith("Connection refused")));

    assertFalse(html.contains(" · "), "no dot-joined runs should survive: " + html);
    assertTrue(html.contains("fact__label"), html);
    assertTrue(html.contains("fact__value"), html);
    assertTrue(html.contains("class=\"meta__label\""), html);
    assertTrue(html.contains("state__mark"), "a state is a mark and a word: " + html);
    assertFalse(html.contains("class=\"chip"), "chips were replaced by states: " + html);
  }

  /**
   * One table everywhere. A run's detail used to mix an inline pair strip with a reflowing grid,
   * which is two ways of showing a label and a value on the same screen.
   */
  @Test
  void detailUsesTheAlignedTableAndNothingElse() {
    String html = RunsView.render(List.of(runWith("Connection refused")));

    assertTrue(html.contains("class=\"facts\""), html);
    assertFalse(html.contains("class=\"data"),
        "the reflowing grid was replaced by the aligned table: " + html);
    // The inline strip survives only in the collapsed summary, never inside the detail.
    assertEquals(1, html.split("class=\"meta\"", -1).length - 1,
        "only the run summary may use the inline strip: " + html);
  }

  /**
   * A run that got partway through is neither a success nor a failure, and saying so is the
   * whole point of having more than a boolean.
   */
  @Test
  void partialRunsAreNeitherSucceededNorFailed() {
    RunRecord partial = new RunRecord("r4", "nightly", "schedule",
        Instant.parse("2026-08-12T18:00:00Z"), Instant.parse("2026-08-12T18:30:00Z"),
        List.of(
            new RunRecord.VisitRecord("survival", "main",
                Instant.parse("2026-08-12T18:00:01Z"), Duration.ofMinutes(20),
                true, "ran 2 action(s)", 2, 1, 776, false,
                Duration.ofSeconds(4), "CLIENT_CLOSED"),
            new RunRecord.VisitRecord("creative", "main",
                Instant.parse("2026-08-12T18:21:00Z"), Duration.ofMinutes(9),
                false, "Connection refused", 0, 2, -1, false, null, "NETWORK")));

    assertEquals(RunStatus.PARTIAL, partial.status());
    String html = RunsView.render(List.of(partial));
    assertTrue(html.contains("partial"), html);
    assertTrue(html.contains("1 of 2"), "the tally says how much of the chain worked: " + html);
  }

  /**
   * Visits a stopped job never reached used to be missing from the history entirely, which made
   * a five-visit job look like a two-visit one.
   */
  @Test
  void visitsThatWereNeverReachedAreShownRatherThanOmitted() {
    RunRecord stopped = new RunRecord("r5", "nightly", "web",
        Instant.parse("2026-08-12T18:00:00Z"), Instant.parse("2026-08-12T18:04:00Z"),
        List.of(
            new RunRecord.VisitRecord("survival", "main",
                Instant.parse("2026-08-12T18:00:01Z"), Duration.ofSeconds(30),
                false, "Stopped by an operator", 0, 1, -1, false, null,
                "CANCELLED", VisitStatus.CANCELLED),
            RunRecord.VisitRecord.skipped("creative", "main",
                "The job was stopped before this visit")),
        RunStatus.CANCELLED);

    assertEquals(2, stopped.visits().size());
    assertEquals(1, stopped.skippedCount());

    String html = RunsView.render(List.of(stopped));
    assertTrue(html.contains("creative"), "a visit that never ran is still listed: " + html);
    assertTrue(html.contains("not reached"), html);
    assertTrue(html.contains("1 not reached"), "the tally separates skipped from failed: " + html);
    assertFalse(html.contains("is-bad"), "nothing here failed: " + html);
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
