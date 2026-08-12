package dev.chronit.core.util;

import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class DurationsTest {

    @Test
    void parsesCompactForms() {
        assertEquals(Duration.ofMillis(250), Durations.parse("250ms"));
        assertEquals(Duration.ofSeconds(30), Durations.parse("30s"));
        assertEquals(Duration.ofMinutes(5), Durations.parse("5m"));
        assertEquals(Duration.ofHours(2), Durations.parse("2h"));
        assertEquals(Duration.ofDays(1), Durations.parse("1d"));
        assertEquals(Duration.ofMinutes(90), Durations.parse("1h30m"));
        assertEquals(Duration.ofSeconds(3661), Durations.parse("1h1m1s"));
    }

    @Test
    void parsesIsoForms() {
        assertEquals(Duration.ofMinutes(30), Durations.parse("PT30M"));
        assertEquals(Duration.ofMinutes(90), Durations.parse("PT1H30M"));
    }

    @Test
    void treatsABareNumberAsSeconds() {
        assertEquals(Duration.ofSeconds(45), Durations.parse("45"));
    }

    @Test
    void isNotConfusedByTheIsoMinuteDesignator() {
        // "PT1M" must be a minute, not the compact form's reading of the trailing M.
        assertEquals(Duration.ofMinutes(1), Durations.parse("PT1M"));
    }

    @Test
    void rejectsNonsense() {
        assertThrows(RuntimeException.class, () -> Durations.parse("soon"));
        assertThrows(RuntimeException.class, () -> Durations.parse("5x"));
        assertThrows(RuntimeException.class, () -> Durations.parse("10m junk"));
        assertThrows(IllegalArgumentException.class, () -> Durations.parse(""));
    }

    @Test
    void formatsReadably() {
        assertEquals("250ms", Durations.format(Duration.ofMillis(250)));
        assertEquals("30s", Durations.format(Duration.ofSeconds(30)));
        assertEquals("1h30m", Durations.format(Duration.ofMinutes(90)));
        assertEquals("0s", Durations.format(Duration.ZERO));
    }

    @Test
    void roundTripsThroughFormatAndParse() {
        for (Duration duration : new Duration[]{
                Duration.ofSeconds(1), Duration.ofSeconds(59), Duration.ofMinutes(5),
                Duration.ofMinutes(90), Duration.ofHours(3)}) {
            assertEquals(duration, Durations.parse(Durations.format(duration)));
        }
    }
}
