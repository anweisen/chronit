package dev.chronit.web.view;

import dev.chronit.core.state.RunRecord;
import dev.chronit.core.util.Durations;
import dev.chronit.core.util.Redactor;
import dev.chronit.web.html.Node;

import java.util.List;

import static dev.chronit.web.html.H.attr;
import static dev.chronit.web.html.H.cls;
import static dev.chronit.web.html.H.details;
import static dev.chronit.web.html.H.div;
import static dev.chronit.web.html.H.el;
import static dev.chronit.web.html.H.span;
import static dev.chronit.web.html.H.summary;
import static dev.chronit.web.html.H.text;

/**
 * The run history.
 *
 * <p>Rendered on its own so the browser can refetch just this part when a run finishes, instead of
 * reloading the page. Because it is still the server rendering it, there is only one description of
 * what a run looks like.
 */
public final class RunsView {

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
        long failed = record.visits().size() - record.successCount();

        return details(cls("run"),
                summary(cls("run__summary"),
                        Ui.chip(ok ? Ui.Tone.OK : Ui.Tone.BAD, ok ? "ok" : failed + " failed"),
                        span(cls("run__job"), text(record.jobId())),
                        span(cls("run__when"),
                                Ui.relativeTime(record.startedAt(), absolute(record.startedAt()))),
                        span(cls("run__when faint"),
                                text(Durations.format(record.duration())
                                        + " · " + record.visits().size() + " visit(s)"
                                        + " · " + record.trigger()))),
                div(cls("run__detail"), Node.each(record.visits(), RunsView::visit)));
    }

    private static Node visit(RunRecord.VisitRecord visit) {
        return div(cls("run__visit"),
                div(cls("run__visit-head"),
                        Ui.chip(visit.success() ? Ui.Tone.OK : Ui.Tone.BAD, visit.serverId()),
                        span(cls("faint"), text("as " + visit.accountId()))),
                // A failure detail often quotes the action that failed, and the most likely action
                // to fail is a server login. Escaping alone would faithfully render the password.
                div(cls("run__visit-detail"), text(Redactor.redact(visit.detail()))),
                div(cls("run__visit-meta"),
                        meta("took", Durations.format(visit.duration())),
                        meta("actions", String.valueOf(visit.actionsRun())),
                        meta("attempts", String.valueOf(visit.attempts())),
                        visit.protocolVersion() > 0
                                ? meta("protocol", visit.protocolVersion()
                                + (visit.translated() ? " (translated)" : ""))
                                : Node.empty(),
                        meta("started", absolute(visit.startedAt()))));
    }

    /**
     * The server-rendered fallback, shown until the script replaces it with a relative label — and
     * kept readable for anyone browsing with scripting off.
     */
    private static String absolute(java.time.Instant instant) {
        return java.time.format.DateTimeFormatter.ofPattern("d MMM HH:mm", java.util.Locale.ENGLISH)
                .withZone(java.time.ZoneId.systemDefault())
                .format(instant);
    }

    private static Node meta(String label, Object value) {
        return span(el("span", attr("style", "opacity:.7"), text(label + " ")), text(String.valueOf(value)));
    }
}
