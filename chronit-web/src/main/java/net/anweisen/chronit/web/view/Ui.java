package net.anweisen.chronit.web.view;

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
 * <p>There is deliberately no "put these strings next to each other with dots between them" helper.
 * Secondary information is always a {@link #datum} — a small capitalised label above its value — so
 * that "Europe/Berlin" is visibly a timezone and "10s" is visibly a duration, rather than both
 * being anonymous fragments in a run-on line. Anything genuinely list-like is a {@link #tags} row
 * of chips. Between them those two cover every place a dot-separated string would otherwise appear,
 * and they look the same on every page because they are the same components.
 */
public final class Ui {

    private Ui() {
    }

    public enum Tone {
        NEUTRAL(""), OK("chip--ok"), WARN("chip--warn"), BAD("chip--bad"), ACCENT("chip--accent");

        private final String className;

        Tone(String className) {
            this.className = className;
        }

        String className() {
            return className;
        }
    }

    // ---------------------------------------------------------------- chips

    public static Element chip(Tone tone, String label) {
        return span(cls(("chip " + tone.className()).trim()), text(label));
    }

    /** A chip with a slow pulse, for something happening right now. */
    public static Element runningChip() {
        return span(cls("chip chip--accent"), attr("data-job-status", ""),
                span(cls("chip__pulse")),
                text("running"));
    }

    /** A row of chips — the structured replacement for a dot-separated list. */
    public static Element tags(Node... items) {
        return div(cls("tags"), Node.fragment(items));
    }

    public static Element tag(String label) {
        return span(cls("tag"), text(label));
    }

    /** A tag whose value is monospaced, for identifiers and addresses. */
    public static Element tagMono(String label) {
        return span(cls("tag tag--mono"), text(label));
    }

    // ---------------------------------------------------------------- data

    /**
     * A labelled value.
     *
     * <p>The one shape used for every piece of secondary information on every page.
     */
    public static Element datum(String label, Node value) {
        return div(cls("datum"), dt(cls("datum__label"), text(label)), dd(cls("datum__value"), value));
    }

    public static Element datum(String label, String value) {
        return datum(label, text(value));
    }

    /** A grid of {@link #datum}s that reflows by available width. */
    public static Element data(Node... items) {
        return dl(cls("data"), Node.fragment(items));
    }

    /** A tighter grid, for dense contexts like a run's per-visit facts. */
    public static Element dataCompact(Node... items) {
        return dl(cls("data data--compact"), Node.fragment(items));
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

    /** Inline icons, authored here — the only place raw markup is used. */
    public static Node icon(String name) {
        String path = switch (name) {
            case "theme" -> "<circle cx='12' cy='12' r='4.2'/><path d='M12 2.5v2M12 19.5v2M2.5 12h2"
                    + "M19.5 12h2M5 5l1.5 1.5M17.5 17.5L19 19M19 5l-1.5 1.5M6.5 17.5L5 19'/>";
            case "play" -> "<path d='M8 5.5v13l11-6.5z' fill='currentColor' stroke='none'/>";
            case "stop" -> "<rect x='7' y='7' width='10' height='10' rx='2'"
                    + " fill='currentColor' stroke='none'/>";
            case "warn" -> "<path d='M12 4.5 2.8 20h18.4z'/><path d='M12 10v4.2M12 17.2v.1'/>";
            case "clock" -> "<circle cx='12' cy='12' r='8.4'/><path d='M12 7.4V12l3 1.8'/>";
            case "key" -> "<circle cx='8.5' cy='12' r='3.4'/><path d='M11.9 12H21M18 12v3M15 12v2.2'/>";
            default -> "";
        };
        return Node.raw("<svg viewBox='0 0 24 24' fill='none' stroke='currentColor'"
                + " stroke-width='1.7' stroke-linecap='round' stroke-linejoin='round'"
                + " aria-hidden='true'>" + path + "</svg>");
    }
}
