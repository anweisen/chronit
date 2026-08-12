package dev.chronit.web.view;

import dev.chronit.web.html.Element;
import dev.chronit.web.html.Node;

import java.time.Instant;
import java.time.ZonedDateTime;

import static dev.chronit.web.html.H.attr;
import static dev.chronit.web.html.H.cls;
import static dev.chronit.web.html.H.div;
import static dev.chronit.web.html.H.p;
import static dev.chronit.web.html.H.span;
import static dev.chronit.web.html.H.text;
import static dev.chronit.web.html.H.time;

/** Small pieces shared between the pages. */
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

    public static Element chip(Tone tone, String label, Node... extra) {
        return span(cls(("chip " + tone.className()).trim()),
                Node.fragment(extra),
                text(label));
    }

    public static Element chip(Tone tone, String label) {
        return span(cls(("chip " + tone.className()).trim()), text(label));
    }

    /** A chip with a slow pulse, for things that are happening right now. */
    public static Element runningChip() {
        return span(cls("chip chip--accent"), attr("data-job-status", ""),
                span(cls("chip__pulse")),
                text("running"));
    }

    public static Element stat(String label, Node value, String sub, Tone tone) {
        String modifier = switch (tone) {
            case OK -> " stat--ok";
            case WARN -> " stat--warn";
            case BAD -> " stat--bad";
            default -> "";
        };
        return div(cls("stat" + modifier),
                p(cls("stat__label"), text(label)),
                p(cls("stat__value"), value),
                sub == null ? Node.empty() : p(cls("stat__sub"), text(sub)));
    }

    public static Element stat(String label, String value, String sub, Tone tone) {
        String modifier = switch (tone) {
            case OK -> " stat--ok";
            case WARN -> " stat--warn";
            case BAD -> " stat--bad";
            default -> "";
        };
        return div(cls("stat" + modifier),
                p(cls("stat__label"), text(label)),
                p(cls("stat__value"), text(value)),
                sub == null ? Node.empty() : p(cls("stat__sub"), text(sub)));
    }

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

    public static Element empty(String message) {
        return div(cls("empty"), text(message));
    }

    /** Inline icons, authored here — the only place raw markup is used. */
    public static Node icon(String name) {
        String path = switch (name) {
            case "theme" -> "<circle cx='12' cy='12' r='4.2'/><path d='M12 2.5v2M12 19.5v2M2.5 12h2"
                    + "M19.5 12h2M5 5l1.5 1.5M17.5 17.5L19 19M19 5l-1.5 1.5M6.5 17.5L5 19'/>";
            case "play" -> "<path d='M8 5.5v13l11-6.5z'/>";
            default -> "";
        };
        return Node.raw("<svg viewBox='0 0 24 24' fill='none' stroke='currentColor'"
                + " stroke-width='1.7' stroke-linecap='round' stroke-linejoin='round'"
                + " aria-hidden='true'>" + path + "</svg>");
    }
}
