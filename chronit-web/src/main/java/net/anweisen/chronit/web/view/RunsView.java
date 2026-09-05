package net.anweisen.chronit.web.view;

import net.anweisen.chronit.core.state.RunRecord;
import net.anweisen.chronit.core.state.RunStatus;
import net.anweisen.chronit.core.state.VisitStatus;
import net.anweisen.chronit.core.util.Durations;
import net.anweisen.chronit.core.util.Redactor;
import net.anweisen.chronit.web.html.Node;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import static net.anweisen.chronit.web.html.H.attr;
import static net.anweisen.chronit.web.html.H.cls;
import static net.anweisen.chronit.web.html.H.details;
import static net.anweisen.chronit.web.html.H.div;
import static net.anweisen.chronit.web.html.H.h3;
import static net.anweisen.chronit.web.html.H.li;
import static net.anweisen.chronit.web.html.H.ol;
import static net.anweisen.chronit.web.html.H.p;
import static net.anweisen.chronit.web.html.H.span;
import static net.anweisen.chronit.web.html.H.summary;
import static net.anweisen.chronit.web.html.H.text;

/**
 * The run history, drawn as a timeline.
 *
 * <p>A list of runs is a chronology, so it is drawn as one: a single hairline down the page with a
 * node on it for each run, coloured and shaped by how that run ended. Expanding a run puts its
 * visits on an indented spine of their own. Nothing is boxed — the spine and the node carry the
 * structure, which leaves colour free to mean status rather than decoration.
 *
 * <p>Rendered on its own so the live channel can push just this part when a run finishes, rather
 * than reloading the page. Because it is still the server rendering it, there is only one
 * description of what a run looks like.
 *
 * <p>Each visit answers the questions asked after a failure, in the order they get asked: did it
 * get in at all, how long did that take, what did it manage to do, and what ended it.
 */
public final class RunsView {

  private static final DateTimeFormatter ABSOLUTE =
      DateTimeFormatter.ofPattern("d MMM, HH:mm", Locale.ENGLISH);

  private RunsView() {
  }

  public static String render(List<RunRecord> runs) {
    if (runs.isEmpty()) {
      return Ui.empty("Nothing has run yet. Use “Run now” on a job to try one.").toHtml();
    }
    return ol(cls("timeline"), Node.each(runs, RunsView::run)).toHtml();
  }

  private static Node run(RunRecord record) {
    RunStatus status = record.status();
    Ui.Tone tone = Ui.toneOf(status);

    return li(cls("tl tl--run " + tone.className()),
        span(cls("tl__node"), attr("aria-hidden", "true")),
        details(cls("tl__body"), attr("data-remember", "run:" + record.runId()),
            summary(cls("tl__summary"),
                div(cls("tl__head"),
                    h3(cls("tl__title"), text(record.jobId())),
                    Ui.state(status),
                    span(cls("tl__when"),
                        Ui.relativeTime(record.startedAt(),
                            absolute(record.startedAt())))),
                Ui.meta(
                    Ui.metaItem("Took", Durations.format(record.duration())),
                    Ui.metaItem("Visits", tally(record)),
                    Ui.metaItem("Started by", record.trigger())),
                span(cls("disclosure__chevron"), Ui.icon("chevron"))),
            div(cls("fold"), div(cls("tl__detail"),
                ol(cls("timeline timeline--nested"),
                    Node.each(record.visits(), RunsView::visit))))));
  }

  /**
   * How the visits went, in one phrase.
   *
   * <p>"3 of 5, 2 not reached" says considerably more than "5 visits", and it is the line that
   * makes a stopped run legible at a glance: the run did some of its work, and the rest was never
   * attempted rather than attempted and failed.
   */
  private static String tally(RunRecord record) {
    long attempted = record.attemptedCount();
    long skipped = record.skippedCount();
    String core = record.successCount() + " of " + attempted;
    return skipped == 0 ? core : core + ", " + skipped + " not reached";
  }

  /**
   * One visit inside an expanded run.
   *
   * <p>Strictly one thing per line, top to bottom: who and how it went, then the record as an
   * aligned table, then the reason if there is one. Nothing sits beside anything else. The
   * previous arrangement put a heading row and a strip of pairs next to each other, which gave
   * the eye two competing places to start and left ragged space between them at most widths.
   */
  private static Node visit(RunRecord.VisitRecord visit) {
    VisitStatus status = visit.status();
    Ui.Tone tone = Ui.toneOf(status);
    boolean skipped = status == VisitStatus.SKIPPED;

    return li(cls("tl tl--visit " + tone.className()),
        span(cls("tl__node"), attr("aria-hidden", "true")),
        div(cls("tl__body"),
            div(cls("tl__head"),
                h3(cls("tl__title"), text(visit.serverId())),
                Ui.state(status)),
            Ui.facts(
                Ui.factMono("Account", visit.accountId()),
                Ui.fact("Started", visit.startedAt() == null
                    ? "never" : absolute(visit.startedAt())),
                skipped ? Node.empty() : record(visit),
                visit.outcome() == null || visit.outcome().isBlank()
                    ? Node.empty()
                    : Ui.fact("Ended", humanOutcome(visit.outcome()))),

            // A failure detail often quotes the action that failed, and the most likely
            // action to fail is a server login. Escaping alone would faithfully render
            // the password.
            visit.detail() == null || visit.detail().isBlank()
                ? Node.empty()
                : p(cls("tl__reason"), text(Redactor.redact(visit.detail())))));
  }

  /**
   * The measurements, in the order they get asked about after a failure.
   */
  private static Node record(RunRecord.VisitRecord visit) {
    return Node.fragment(
        // Whether it got into the world at all is the first thing worth knowing, and it
        // separates "could not connect" from "connected and then failed".
        visit.reachedTheWorld()
            ? Ui.fact("Joined", "in " + Durations.format(visit.timeToReady()))
            : Ui.factQuiet("Joined", "never"),
        Ui.fact(visit.reachedTheWorld() ? "Present" : "Gave up after",
            Durations.format(visit.duration())),
        Ui.fact("Actions run", String.valueOf(visit.actionsRun())),
        Ui.fact("Attempts", String.valueOf(visit.attempts())),
        visit.protocolVersion() > 0
            ? Ui.fact("Protocol", visit.protocolVersion()
            + (visit.translated() ? ", translated" : ", native"))
            : Node.empty());
  }

  /**
   * Turns the wire enum name into something readable without losing the distinction.
   */
  private static String humanOutcome(String outcome) {
    return switch (outcome) {
      case "CLIENT_CLOSED" -> "left cleanly";
      case "CANCELLED" -> "stopped by operator";
      case "VERSION_MISMATCH" -> "version refused";
      case "AUTH_FAILED" -> "auth failed";
      case "RESOURCE_PACK" -> "resource pack";
      case "KICKED" -> "kicked";
      case "TIMEOUT" -> "timed out";
      case "NETWORK" -> "network";
      default -> outcome.toLowerCase(Locale.ENGLISH).replace('_', ' ');
    };
  }

  /**
   * The server-rendered fallback, replaced by a relative label once the script runs — and kept
   * readable for anyone browsing with scripting off.
   */
  private static String absolute(Instant instant) {
    return ABSOLUTE.withZone(ZoneId.systemDefault()).format(instant);
  }
}
