package net.anweisen.chronit.web.view;

import net.anweisen.chronit.core.state.RunStatus;
import net.anweisen.chronit.core.state.VisitStatus;
import net.anweisen.chronit.web.html.Element;
import net.anweisen.chronit.web.html.Node;

import java.time.Instant;
import java.time.ZonedDateTime;

import static net.anweisen.chronit.web.html.H.attr;
import static net.anweisen.chronit.web.html.H.cls;
import static net.anweisen.chronit.web.html.H.dd;
import static net.anweisen.chronit.web.html.H.div;
import static net.anweisen.chronit.web.html.H.dl;
import static net.anweisen.chronit.web.html.H.dt;
import static net.anweisen.chronit.web.html.H.span;
import static net.anweisen.chronit.web.html.H.text;
import static net.anweisen.chronit.web.html.H.time;

/**
 * The shared vocabulary every page is built from.
 *
 * <p>Four shapes, and nothing else is invented at a call site:
 *
 * <ul>
 *   <li>a {@link #state} — a drawn mark and a word, in the colour of the thing it describes. It is
 *       not a filled capsule: a page of capsules reads as a page of buttons, and the eye stops
 *       telling them apart. Here the shape of the mark carries the meaning even in monochrome, and
 *       colour only confirms it.</li>
 *   <li>a {@link #facts} table — labels in one aligned column, values in another. Every piece of
 *       detail on every page is one of these rows, so "Europe/Berlin" is visibly a timezone rather
 *       than an anonymous fragment, and two blocks of facts line up with each other.</li>
 *   <li>a {@link #meta} strip — the same pairs in the same voice when they have to fit on one
 *       line, which is only ever a collapsed summary.</li>
 *   <li>a {@link #rail} — the two-pixel edge that starts every job, account and run. It is the one
 *       device that makes the whole interface look like one interface, and it carries status
 *       without drawing a box around anything.</li>
 * </ul>
 */
public final class Ui {

    private Ui() {
    }

    /**
     * The colour and mark a status is drawn in.
     *
     * <p>{@link #STOP} is deliberately not a shade of red. Someone who pressed stop knows what
     * they did and does not need to be alarmed about it, and a history where deliberate stops look
     * like failures is a history nobody reads carefully.
     */
    public enum Tone {
        NEUTRAL("is-neutral"),
        OK("is-ok"),
        WARN("is-warn"),
        BAD("is-bad"),
        STOP("is-stop"),
        SKIP("is-skip"),
        LIVE("is-live");

        private final String className;

        Tone(String className) {
            this.className = className;
        }

        public String className() {
            return className;
        }
    }

    // ---------------------------------------------------------------- status

    public static Tone toneOf(RunStatus status) {
        return switch (status) {
            case SUCCEEDED -> Tone.OK;
            case PARTIAL -> Tone.WARN;
            case FAILED -> Tone.BAD;
            case CANCELLED -> Tone.STOP;
            case SKIPPED -> Tone.SKIP;
        };
    }

    public static Tone toneOf(VisitStatus status) {
        return switch (status) {
            case SUCCEEDED -> Tone.OK;
            case FAILED -> Tone.BAD;
            case CANCELLED -> Tone.STOP;
            case SKIPPED -> Tone.SKIP;
        };
    }

    /** The words shown to an operator, which are not the enum names. */
    public static String labelOf(RunStatus status) {
        return switch (status) {
            case SUCCEEDED -> "complete";
            case PARTIAL -> "partial";
            case FAILED -> "failed";
            case CANCELLED -> "stopped";
            case SKIPPED -> "skipped";
        };
    }

    public static String labelOf(VisitStatus status) {
        return switch (status) {
            case SUCCEEDED -> "ok";
            case FAILED -> "failed";
            case CANCELLED -> "stopped";
            case SKIPPED -> "not reached";
        };
    }

    /** A drawn mark and a word. The one way a state is ever shown. */
    public static Element state(Tone tone, String label) {
        return span(cls("state " + tone.className()),
                mark(tone),
                span(cls("state__word"), text(label)));
    }

    public static Element state(RunStatus status) {
        return state(toneOf(status), labelOf(status));
    }

    public static Element state(VisitStatus status) {
        return state(toneOf(status), labelOf(status));
    }

    /** The live state a running job shows instead of a finished one, with its own animation. */
    public static Element liveState(String label) {
        return span(cls("state is-live"), attr("data-job-state", ""),
                span(cls("state__spinner"), attr("aria-hidden", "true")),
                span(cls("state__word"), attr("data-job-phase", ""), text(label)));
    }

    /**
     * The mark itself.
     *
     * <p>Shape first, colour second — a check, a cross, a bar, a dash — so the interface still
     * works for a reader who cannot separate the two hues that matter most.
     */
    private static Node mark(Tone tone) {
        String path = switch (tone) {
            case OK -> "<path d='M2 6.2 4.8 9 10 3'/>";
            case BAD -> "<path d='M2.6 2.6 9.4 9.4M9.4 2.6 2.6 9.4'/>";
            case WARN -> "<path d='M6 1.6 11 10.4H1z'/><path d='M6 5v2.1'/>";
            case STOP -> "<path d='M3.4 2.4v7.2M8.6 2.4v7.2'/>";
            case SKIP -> "<path d='M2 6h8' stroke-dasharray='2 2'/>";
            case LIVE -> "<circle cx='6' cy='6' r='3'/>";
            case NEUTRAL -> "<circle cx='6' cy='6' r='3.6'/>";
        };
        return Node.raw("<svg class='state__mark' viewBox='0 0 12 12' fill='none'"
                + " stroke='currentColor' stroke-width='1.6' stroke-linecap='round'"
                + " stroke-linejoin='round' aria-hidden='true'>" + path + "</svg>");
    }

    // ---------------------------------------------------------------- rail

    /**
     * The coloured edge that begins every row.
     *
     * <p>Two pixels of colour and a node at the top, instead of a border on four sides. It gives a
     * row an identity and a status at a glance without the page becoming a stack of containers.
     */
    public static Element rail(Tone tone) {
        return span(cls("rail " + tone.className()), attr("aria-hidden", "true"),
                span(cls("rail__node")));
    }

    // ---------------------------------------------------------------- meta

    /**
     * The same pairs as {@link #facts}, on one line.
     *
     * <p>For a collapsed summary, where a table would be the wrong shape and a run of text
     * separated by dots would lose which word is the label. Separated by hairlines rather than
     * boxed individually.
     */
    public static Element meta(Node... items) {
        return div(cls("meta"), Node.fragment(items));
    }

    public static Element metaItem(String label, String value) {
        return span(cls("meta__item"),
                span(cls("meta__label"), text(label)),
                span(cls("meta__value"), text(value)));
    }

    // ---------------------------------------------------------------- facts

    /**
     * The one table.
     *
     * <p>Every piece of detail anywhere on the page — a job's schedule, a visit's configuration, a
     * run's per-visit record, the system information — is a list of these rows: the label in a
     * column of its own, the value aligned against it. One column of labels down the left is what
     * makes a set of facts scannable; a grid that reflows into two, three or four columns depending
     * on the width available means the reader has to find each label before they can read it, and
     * no two blocks on the page line up with each other.
     *
     * <p>{@link #meta} is the same information in the same voice when it has to fit on one line —
     * a collapsed summary — and nothing else lays out a label and a value.
     */
    public static Element facts(Node... rows) {
        return dl(cls("facts"), Node.fragment(rows));
    }

    public static Element fact(String label, Node value) {
        return div(cls("fact"), dt(cls("fact__label"), text(label)), dd(cls("fact__value"), value));
    }

    public static Element fact(String label, String value) {
        return fact(label, text(value));
    }

    /** A value that is a path, an expression or an address, so it is set in the mono face. */
    public static Element factMono(String label, String value) {
        return div(cls("fact"), dt(cls("fact__label"), text(label)),
                dd(cls("fact__value fact__value--mono"), text(value)));
    }

    /** A value worth reading first — the next fire time, the clock on a running job. */
    public static Element factStrong(String label, Node value) {
        return div(cls("fact"), dt(cls("fact__label"), text(label)),
                dd(cls("fact__value fact__value--strong"), value));
    }

    /** A value the reader is meant to skim past: an unset option, an absent measurement. */
    public static Element factQuiet(String label, String value) {
        return div(cls("fact"), dt(cls("fact__label"), text(label)),
                dd(cls("fact__value fact__value--quiet"), text(value)));
    }

    // ---------------------------------------------------------------- figures

    /**
     * A number worth reading from across the room, with its label beneath.
     *
     * <p>Set in tabular figures so a countdown does not shuffle the layout once a second.
     */
    public static Element figure(Node value, String label) {
        return div(cls("figure"),
                div(cls("figure__value"), value),
                div(cls("figure__label"), text(label)));
    }

    // ---------------------------------------------------------------- time

    /**
     * A timestamp the browser keeps current.
     *
     * <p>The machine-readable instant goes in the attribute and the script rewrites the label every
     * second, so "in 21h 6m" stays true without the page asking the server anything.
     */
    public static Element relativeTime(Instant instant, String fallback) {
        if (instant == null) {
            return time(text(fallback));
        }
        return time(attr("datetime", instant.toString()),
                attr("data-relative", ""),
                attr("title", instant.toString()),
                text(fallback));
    }

    public static Element relativeTime(ZonedDateTime moment, String fallback) {
        return relativeTime(moment == null ? null : moment.toInstant(), fallback);
    }

    // ---------------------------------------------------------------- misc

    public static Element empty(String message) {
        return div(cls("empty"), span(cls("empty__text"), text(message)));
    }

    /**
     * A hairline progress line, for a job partway through its visit chain.
     *
     * <p>A line rather than a bar in a trough: the shape of the information is "how far along",
     * and drawing a container around it adds a rectangle and says nothing.
     */
    public static Element progress(int done, int total) {
        int percent = total <= 0 ? 0 : Math.min(100, Math.max(0, done * 100 / total));
        return div(cls("progress"), attr("data-progress", ""),
                attr("role", "progressbar"),
                attr("aria-valuemin", "0"),
                attr("aria-valuemax", String.valueOf(Math.max(total, 0))),
                attr("aria-valuenow", String.valueOf(Math.max(done, 0))),
                span(cls("progress__fill"), attr("style", "width:" + percent + "%")));
    }

    /** Inline icons, authored here — the only place raw markup is used. */
    public static Node icon(String name) {
        String path = switch (name) {
            case "theme" -> "<circle cx='12' cy='12' r='4.2'/><path d='M12 2.5v2M12 19.5v2M2.5 12h2"
                    + "M19.5 12h2M5 5l1.5 1.5M17.5 17.5L19 19M19 5l-1.5 1.5M6.5 17.5L5 19'/>";
            case "play" -> "<path d='M8 5.5v13l11-6.5z' fill='currentColor' stroke='none'/>";
            case "stop" -> "<path d='M8 7.5v9M16 7.5v9'/>";
            case "warn" -> "<path d='M12 4.5 2.8 20h18.4z'/><path d='M12 10v4.2M12 17.2v.1'/>";
            case "clock" -> "<circle cx='12' cy='12' r='8.4'/><path d='M12 7.4V12l3 1.8'/>";
            case "key" -> "<circle cx='8.5' cy='12' r='3.4'/><path d='M11.9 12H21M18 12v3M15 12v2.2'/>";
            case "chevron" -> "<path d='M9 5.5 15.5 12 9 18.5'/>";
            case "arrow" -> "<path d='M5 12h13M12.5 6.5 19 12l-6.5 5.5'/>";
            default -> "";
        };
        return Node.raw("<svg viewBox='0 0 24 24' fill='none' stroke='currentColor'"
                + " stroke-width='1.7' stroke-linecap='round' stroke-linejoin='round'"
                + " aria-hidden='true'>" + path + "</svg>");
    }
}
