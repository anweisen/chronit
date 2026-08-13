package net.anweisen.chronit.core.util;

import java.time.Duration;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Duration parsing that accepts both ISO-8601 ({@code PT30M}) and the compact form people
 * actually write in configuration files ({@code 30m}, {@code 1h30m}, {@code 500ms}).
 */
public final class Durations {

    private static final Pattern COMPACT = Pattern.compile("(\\d+)\\s*(ms|s|m|h|d)");

    private Durations() {
    }

    /**
     * Parses a duration.
     *
     * @param raw ISO-8601 ({@code PT1H30M}) or compact ({@code 1h30m}, {@code 90s}, {@code 250ms})
     * @throws IllegalArgumentException if the text is not a duration
     */
    public static Duration parse(String raw) {
        if (raw == null) {
            throw new IllegalArgumentException("duration is null");
        }
        String text = raw.trim().toLowerCase(Locale.ROOT);
        if (text.isEmpty()) {
            throw new IllegalArgumentException("duration is empty");
        }

        // ISO-8601 first, so "PT1M" is never mistaken for the compact form.
        if (text.startsWith("p") || text.startsWith("+p") || text.startsWith("-p")) {
            return Duration.parse(raw.trim());
        }

        // A bare number is seconds, matching the convention of most cron-adjacent tools.
        if (text.matches("\\d+")) {
            return Duration.ofSeconds(Long.parseLong(text));
        }

        Matcher matcher = COMPACT.matcher(text);
        Duration total = Duration.ZERO;
        int consumed = 0;
        while (matcher.find()) {
            if (matcher.start() != consumed) {
                throw new IllegalArgumentException("not a duration: '" + raw + "'");
            }
            consumed = matcher.end();
            long value = Long.parseLong(matcher.group(1));
            total = total.plus(switch (matcher.group(2)) {
                case "ms" -> Duration.ofMillis(value);
                case "s" -> Duration.ofSeconds(value);
                case "m" -> Duration.ofMinutes(value);
                case "h" -> Duration.ofHours(value);
                case "d" -> Duration.ofDays(value);
                default -> throw new IllegalStateException("unreachable unit");
            });
        }
        if (consumed != text.length() || total.isZero() && consumed == 0) {
            throw new IllegalArgumentException("not a duration: '" + raw + "'");
        }
        return total;
    }

    /** Renders a duration in the compact form, for logs and the web interface. */
    public static String format(Duration duration) {
        if (duration == null) {
            return "-";
        }
        long totalMillis = duration.toMillis();
        if (totalMillis == 0) {
            // "0s" rather than "0ms": zero is a duration of no particular unit, and seconds is
            // the unit everything else in the configuration defaults to.
            return "0s";
        }
        if (totalMillis < 1000) {
            return totalMillis + "ms";
        }
        StringBuilder out = new StringBuilder();
        long seconds = totalMillis / 1000;
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long secs = seconds % 60;
        if (hours > 0) {
            out.append(hours).append('h');
        }
        if (minutes > 0) {
            out.append(minutes).append('m');
        }
        if (secs > 0 || out.isEmpty()) {
            out.append(secs).append('s');
        }
        return out.toString();
    }
}
