package dev.chronit.core.run;

import dev.chronit.core.driver.ChatLine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@Timeout(value = 10, unit = TimeUnit.SECONDS)
class ChatBusTest {

    private static ChatLine line(String text) {
        return ChatLine.of(ChatLine.Source.SYSTEM, text, null);
    }

    @Test
    void completesWhenAMatchingLineArrives() throws Exception {
        ChatBus bus = new ChatBus();
        try (ChatBus.Waiter waiter = bus.expect(Pattern.compile("(?i)logged in"))) {
            bus.publish(line("Welcome!"));
            bus.publish(line("You are now logged in."));

            ChatLine matched = waiter.await(Duration.ofSeconds(1));
            assertEquals("You are now logged in.", matched.plainText());
        }
    }

    /**
     * Servers frequently answer within a few milliseconds, so a waiter registered after the
     * command was sent would miss the reply and wait out the whole timeout. The runner registers
     * first; this checks the bus does not lose a line delivered immediately afterwards.
     */
    @Test
    void doesNotMissAReplyThatArrivesImmediately() throws Exception {
        ChatBus bus = new ChatBus();
        try (ChatBus.Waiter waiter = bus.expect(Pattern.compile("pong"))) {
            bus.publish(line("pong"));
            assertEquals("pong", waiter.await(Duration.ofMillis(50)).plainText());
        }
    }

    @Test
    void timesOutWhenNothingMatches() {
        ChatBus bus = new ChatBus();
        try (ChatBus.Waiter waiter = bus.expect(Pattern.compile("never"))) {
            bus.publish(line("something else"));
            assertThrows(TimeoutException.class, () -> waiter.await(Duration.ofMillis(100)));
        }
    }

    @Test
    void closingAWaiterStopsItReceivingLines() throws Exception {
        ChatBus bus = new ChatBus();
        ChatBus.Waiter waiter = bus.expect(Pattern.compile("late"));
        waiter.close();
        bus.publish(line("late arrival"));

        assertThrows(TimeoutException.class, () -> waiter.await(Duration.ofMillis(50)));
    }

    @Test
    void abortingFailsOutstandingWaitsInsteadOfHanging() {
        ChatBus bus = new ChatBus();
        try (ChatBus.Waiter waiter = bus.expect(Pattern.compile("never"))) {
            bus.abort("session ended");
            TimeoutException error = assertThrows(TimeoutException.class,
                    () -> waiter.await(Duration.ofSeconds(5)));
            assertTrue(error.getMessage().contains("session ended"));
        }
    }

    @Test
    void observersSeeEveryLine() {
        ChatBus bus = new ChatBus();
        StringBuilder seen = new StringBuilder();
        try (AutoCloseable ignored = bus.observe(l -> seen.append(l.plainText()).append('|'))) {
            bus.publish(line("one"));
            bus.publish(line("two"));
        } catch (Exception e) {
            throw new AssertionError(e);
        }
        assertEquals("one|two|", seen.toString());
    }
}
