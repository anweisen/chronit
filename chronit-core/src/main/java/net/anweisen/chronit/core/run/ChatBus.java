package net.anweisen.chronit.core.run;

import net.anweisen.chronit.core.driver.ChatLine;

import java.util.regex.Pattern;

/** Incoming chat, system and action bar messages. */
public final class ChatBus extends SignalBus<ChatLine> {

    /**
     * Starts listening for a message matching {@code pattern}.
     *
     * <p>Register this before triggering whatever produces the reply, then call
     * {@link SignalWaiter#await}.
     */
    public SignalWaiter<ChatLine> expect(Pattern pattern) {
        return expect(line -> pattern.matcher(line.plainText()).find());
    }
}
