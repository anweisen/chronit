package net.anweisen.chronit.core.run;

import net.anweisen.chronit.core.config.ActionConfig;
import net.anweisen.chronit.core.config.WaitForConfig;
import net.anweisen.chronit.core.driver.ChatLine;
import net.anweisen.chronit.core.driver.ClientHandle;
import net.anweisen.chronit.core.driver.ContainerInfo;
import net.anweisen.chronit.core.util.Durations;
import net.anweisen.chronit.core.util.Jitter;
import net.anweisen.chronit.core.util.Redactor;
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
 * {@code waitFor} pattern instead continues the moment the expected reply — a chat message, or a
 * menu opening — arrives, which is both faster in the common case and more reliable in the slow one.
 */
public final class ActionRunner {

    private static final Logger log = LoggerFactory.getLogger(ActionRunner.class);

    private final ChatBus chat;
    private final ScreenBus screens;
    private final Jitter jitter;

    public ActionRunner(ChatBus chat, ScreenBus screens, Jitter jitter) {
        this.chat = chat;
        this.screens = screens;
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

            // The waiter has to exist before the action goes out. A menu can open within a
            // millisecond of the command that asked for it.
            SignalWaiter<?> waiter = action.waitFor() != null ? register(action.waitFor()) : null;

            try {
                perform(client, action);
                executed++;

                if (waiter != null) {
                    Result failure = awaitSignal(action.waitFor(), waiter, label, executed);
                    if (failure != null) {
                        return failure;
                    }
                }

                sleep(action.delayAfter());
            } catch (IllegalStateException e) {
                // Raised when the session left the world underneath us, or an action needed a
                // container that is not open.
                log.warn("{} failed: {}", label, e.getMessage());
                return new Result(Outcome.FAILED, executed, label + " failed: " + e.getMessage());
            } finally {
                if (waiter != null) {
                    waiter.close();
                }
            }
        }

        return new Result(Outcome.COMPLETED, executed, "all actions completed");
    }

    private void perform(ClientHandle client, ActionConfig action) throws InterruptedException {
        switch (action.kind()) {
            case COMMAND -> {
                log.info("Sending /{}", Redactor.redact(action.command()));
                client.sendCommand(action.command());
            }
            case CHAT -> {
                log.info("Saying: {}", Redactor.redact(action.chat()));
                client.sendChat(action.chat());
            }
            case WAIT -> {
                log.debug("Pausing for {}", Durations.format(action.pause()));
                sleep(action.pause());
            }
            case CLICK -> {
                ContainerInfo container = client.openContainer().orElse(null);
                log.info("Clicking {}{}",
                        action.click().toSlotClick().describe(),
                        container != null ? " in " + container.describe() : "");
                client.clickSlot(action.click().toSlotClick());
            }
            case CLOSE_SCREEN -> {
                if (Boolean.TRUE.equals(action.closeScreen())) {
                    log.info("Closing the open menu");
                    client.closeScreen();
                }
            }
        }
    }

    private SignalWaiter<?> register(WaitForConfig waitFor) {
        Pattern pattern = Pattern.compile(waitFor.pattern());
        return switch (waitFor.subject()) {
            case CHAT -> chat.expect(pattern);
            case SCREEN -> screens.expect(pattern);
        };
    }

    /** @return null to continue, or the terminal result */
    private Result awaitSignal(WaitForConfig waitFor, SignalWaiter<?> waiter, String label, int executed)
            throws InterruptedException {
        Duration timeout = waitFor.timeoutOrDefault();
        try {
            Object matched = waiter.await(timeout);
            log.debug("Matched {}: {}", waitFor.describe(), describeMatch(matched));
            return null;
        } catch (TimeoutException e) {
            String message = "waited " + Durations.format(timeout) + " for " + waitFor.describe()
                    + " and it did not happen";
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

    private static String describeMatch(Object matched) {
        if (matched instanceof ContainerInfo container) {
            return container.describe();
        }
        if (matched instanceof ChatLine line) {
            return Redactor.redact(line.plainText());
        }
        return String.valueOf(matched);
    }

    private void sleep(Duration base) throws InterruptedException {
        if (base == null || base.isZero() || base.isNegative()) {
            return;
        }
        Duration actual = jitter.apply(base);
        Thread.sleep(actual.toMillis());
    }
}
