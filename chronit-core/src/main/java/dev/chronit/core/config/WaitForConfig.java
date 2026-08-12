package dev.chronit.core.config;

import java.time.Duration;

/**
 * Waits for a server message before continuing the sequence.
 *
 * <p>Far more reliable than a fixed delay: an authentication plugin that usually answers in 200ms
 * can take five seconds under load, and a fixed delay long enough to be safe wastes time on every
 * other run.
 *
 * @param chat      regular expression matched against the plain text of incoming chat, system and
 *                  action bar messages
 * @param timeout   how long to wait before giving up
 * @param onTimeout what to do when the message never arrives
 */
public record WaitForConfig(
        String chat,
        Duration timeout,
        OnTimeout onTimeout) {

    public enum OnTimeout {
        /** Carry on with the next action. */
        CONTINUE,
        /** Stop the sequence but treat the visit as successful. */
        STOP,
        /** Fail the visit, triggering the configured retry policy. */
        FAIL
    }

    public Duration timeoutOrDefault() {
        return timeout != null ? timeout : Duration.ofSeconds(15);
    }

    public OnTimeout onTimeoutOrDefault() {
        return onTimeout != null ? onTimeout : OnTimeout.CONTINUE;
    }
}
