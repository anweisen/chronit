package dev.chronit.web.view;

import dev.chronit.core.state.RunRecord;
import dev.chronit.core.util.Durations;
import dev.chronit.core.util.Redactor;
import dev.chronit.web.html.Node;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Locale;

import static dev.chronit.web.html.H.attr;
import static dev.chronit.web.html.H.cls;
import static dev.chronit.web.html.H.details;
import static dev.chronit.web.html.H.div;
import static dev.chronit.web.html.H.h3;
import static dev.chronit.web.html.H.p;
import static dev.chronit.web.html.H.span;
import static dev.chronit.web.html.H.summary;
import static dev.chronit.web.html.H.text;

/**
 * The run history.
 *
 * <p>Rendered on its own so the browser can refetch just this part when a run finishes, instead of
 * reloading the page. Because it is still the server rendering it, there is only one description of
 * what a run looks like.
 *
 * <p>Each visit answers the questions asked after a failure, in the order they get asked: did it
 * get in at all, how long did that take, what did it manage to do, and what ended it. Those are
 * labelled values rather than a run-on line, so the answer to any one of them can be found without
 * reading the others.
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
        return div(cls("runs"), Node.each(runs, RunsView::run)).toHtml();
    }

    private static Node run(RunRecord record) {
        boolean ok = record.succeeded();
        boolean cancelled = record.visits().stream()
                .anyMatch(visit -> "CANCELLED".equals(visit.outcome()));
        long failed = record.visits().size() - record.successCount();

        Ui.Tone tone = ok ? Ui.Tone.OK : cancelled ? Ui.Tone.NEUTRAL : Ui.Tone.BAD;
        String label = ok ? "ok" : cancelled ? "stopped" : failed + " failed";

        return details(cls("run"),
                summary(cls("run__summary"),
                        span(cls(("chip " + tone.className()).trim()), text(label)),
                        h3(cls("run__job"), text(record.jobId())),
                        span(cls("run__when"),
                                Ui.relativeTime(record.startedAt(), absolute(record.startedAt()))),
                        Ui.tags(
                                Ui.tag(Durations.format(record.duration())),
                                Ui.tag(record.visits().size() == 1
                                        ? "1 visit" : record.visits().size() + " visits"),
                                Ui.tag(record.trigger())),
                        span(cls("disclosure__chevron"))),
                div(cls("run__detail"), Node.each(record.visits(), RunsView::visit)));
    }

    private static Node visit(RunRecord.VisitRecord visit) {
        boolean cancelled = "CANCELLED".equals(visit.outcome());
        boolean failed = !visit.success() && !cancelled;

        Ui.Tone tone = visit.success() ? Ui.Tone.OK : cancelled ? Ui.Tone.NEUTRAL : Ui.Tone.BAD;
        String label = visit.success() ? "ok" : cancelled ? "stopped" : "failed";

        return div(cls("run__visit" + (failed ? " run__visit--failed" : "")),
                div(cls("run__visit-head"),
                        span(cls(("chip " + tone.className()).trim()), text(label)),
                        h3(cls("run__visit-server"), text(visit.serverId())),
                        Ui.tags(
                                Ui.tag("as " + visit.accountId()),
                                visit.outcome() == null || visit.outcome().isBlank()
                                        ? Node.empty()
                                        : Ui.tag(humanOutcome(visit.outcome())))),

                Ui.dataCompact(
                        // Whether it got into the world at all is the first thing worth knowing,
                        // and it separates "could not connect" from "connected and then failed".
                        Ui.datum("Joined", visit.reachedTheWorld()
                                ? "in " + Durations.format(visit.timeToReady())
                                : "never"),
                        Ui.datum(visit.reachedTheWorld() ? "Present" : "Gave up after",
                                Durations.format(visit.duration())),
                        Ui.datum("Actions", String.valueOf(visit.actionsRun())),
                        Ui.datum("Attempts", String.valueOf(visit.attempts())),
                        visit.protocolVersion() > 0
                                ? Ui.datum("Protocol", visit.protocolVersion()
                                + (visit.translated() ? ", translated" : ", native"))
                                : Node.empty(),
                        Ui.datum("Started", absolute(visit.startedAt()))),

                // A failure detail often quotes the action that failed, and the most likely action
                // to fail is a server login. Escaping alone would faithfully render the password.
                visit.detail() == null || visit.detail().isBlank()
                        ? Node.empty()
                        : p(cls("run__reason"), text(Redactor.redact(visit.detail()))));
    }

    /** Turns the wire enum name into something readable without losing the distinction. */
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
