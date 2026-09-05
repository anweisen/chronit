package net.anweisen.chronit.core.config;

import java.time.Duration;

/**
 * Waits for the server to do something before continuing the sequence.
 *
 * <p>Far more reliable than a fixed delay: an authentication plugin that usually answers in 200ms
 * can take five seconds under load, and a fixed delay long enough to be safe wastes time on every
 * other run. Exactly one of {@code chat} or {@code screen} must be set.
 *
 * @param chat      regular expression matched against the plain text of incoming chat, system and
 *                  action bar messages
 * @param screen    regular expression matched against the title of a container the server opens.
 *                  Satisfied only once the menu's contents have arrived, because a window is opened
 *                  before it is filled and a click in that gap does nothing. Use {@code ""} to
 *                  accept any menu
 * @param timeout   how long to wait before giving up
 * @param onTimeout what to do when it never happens
 */
public record WaitForConfig(
    String chat,
    String screen,
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

  public enum Subject { CHAT, SCREEN }

  public Subject subject() {
    return screen != null ? Subject.SCREEN : Subject.CHAT;
  }

  /** The regular expression to match, whichever subject was configured. */
  public String pattern() {
    return screen != null ? screen : chat;
  }

  public Duration timeoutOrDefault() {
    return timeout != null ? timeout : Duration.ofSeconds(15);
  }

  public OnTimeout onTimeoutOrDefault() {
    return onTimeout != null ? onTimeout : OnTimeout.CONTINUE;
  }

  public String describe() {
    return switch (subject()) {
      case CHAT -> "a message matching /" + chat + "/";
      case SCREEN -> screen.isEmpty()
          ? "a menu to open"
          : "a menu titled /" + screen + "/";
    };
  }
}
