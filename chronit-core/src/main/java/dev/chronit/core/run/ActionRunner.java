package dev.chronit.core.run;

import dev.chronit.core.config.ActionConfig;
import dev.chronit.core.config.WaitForConfig;
import dev.chronit.core.driver.ChatLine;
import dev.chronit.core.driver.ClientHandle;
import dev.chronit.core.util.Durations;
import dev.chronit.core.util.Jitter;
import dev.chronit.core.util.Redactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

/**
 * Runs a configured command sequence against a live session.
 *
 * <p>Two kinds of pacing are supported. A fixed {@code delayAfter} is simple but always either too
 * short or wastefully long, because how quickly a server answers varies with its load. A
 * {@code waitFor} pattern instead continues the moment the expected reply arrives, which is both
 * faster in the common case and more reliable in the slow one.
 */
public final class ActionRunner {

    private static final Logger log = LoggerFactory.getLogger(ActionRunner.class);

    private final ChatBus chat;
    private final Jitter jitter;

    public ActionRunner(ChatBus chat, Jitter jitter) {
        this.chat = chat;
        this.jitter = jitter;
    }

    public enum Outcome {
        /** Every action ran. */
        COMPLETED,
        /** A wait timed out with {@code onTimeout: stop}; the visit still counts as successful. */
        STOPPED,
        /** A wait timed out with {@code onTimeout: fail}, or the session dropped mid-sequence. */
        FAILED
    }

    public record Result(Outcome outcome, int executed, String detail) {

        public boolean isSuccess() {
            return outcome != Outcome.FAILED;
        }
    }

    /**
     * Executes {@code actions} in order.
     *
     * @throws InterruptedException if the run is cancelled while pausing
     */
    public Result run(ClientHandle client, List<ActionConfig> actions) throws InterruptedException {
        int executed = 0;

        for (int i = 0; i < actions.size(); i++) {
            ActionConfig action = actions.get(i);
            String label = "action " + (i + 1) + "/" + actions.size() + " (" + action.describe() + ")";

            if (!client.isConnected()) {
                return new Result(Outcome.FAILED, executed, "session ended before " + label);
            }

            // The waiter has to exist before the command goes out, or a fast reply is missed.
            ChatBus.Waiter waiter = action.waitFor() != null
                    ? chat.expect(Pattern.compile(action.waitFor().chat()))
                    : null;

            try {
                switch (action.kind()) {
                    case COMMAND -> {
                        log.info("Sending /{}", Redactor.redact(action.command()));
                        client.sendCommand(action.command());
                    }
                    case CHAT -> {
                        log.info("Saying: {}", Redactor.redact(action.chat()));
                        client.sendChat(action.chat());
                    }
                    case WAIT -> log.debug("Pausing for {}", Durations.format(action.pause()));
                }
                executed++;

                if (action.kind() == ActionConfig.Kind.WAIT) {
                    sleep(action.pause());
                }

                if (waiter != null) {
                    Result failure = awaitReply(action.waitFor(), waiter, label, executed);
                    if (failure != null) {
                        return failure;
                    }
                }

                sleep(action.delayAfter());
            } catch (IllegalStateException e) {
                // Raised when the session left the world underneath us.
                return new Result(Outcome.FAILED, executed, label + " failed: " + e.getMessage());
            } finally {
                if (waiter != null) {
                    waiter.close();
                }
            }
        }

        return new Result(Outcome.COMPLETED, executed, "all actions completed");
    }

    /** @return null to continue, or the terminal result */
    private Result awaitReply(WaitForConfig waitFor, ChatBus.Waiter waiter, String label, int executed)
            throws InterruptedException {
        Duration timeout = waitFor.timeoutOrDefault();
        try {
            ChatLine line = waiter.await(timeout);
            log.debug("Matched expected reply: {}", Redactor.redact(line.plainText()));
            return null;
        } catch (TimeoutException e) {
            String message = "no reply matching /" + waitFor.chat() + "/ within " + Durations.format(timeout);
            return switch (waitFor.onTimeoutOrDefault()) {
                case CONTINUE -> {
                    log.warn("{}: {} — continuing", label, message);
                    yield null;
                }
                case STOP -> {
                    log.warn("{}: {} — stopping the sequence", label, message);
                    yield new Result(Outcome.STOPPED, executed, message);
                }
                case FAIL -> {
                    log.error("{}: {} — failing the visit", label, message);
                    yield new Result(Outcome.FAILED, executed, message);
                }
            };
        }
    }

    private void sleep(Duration base) throws InterruptedException {
        if (base == null || base.isZero() || base.isNegative()) {
            return;
        }
        Duration actual = jitter.apply(base);
        Thread.sleep(actual.toMillis());
    }
}
