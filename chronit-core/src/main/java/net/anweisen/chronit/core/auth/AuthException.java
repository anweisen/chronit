package net.anweisen.chronit.core.auth;

/** Authentication failed. {@link #kind()} says what, if anything, will fix it. */
public class AuthException extends Exception {

  /**
   * What kind of failure it was.
   *
   * <p>The distinction matters because the three want opposite responses. Telling an operator to
   * re-authorise an account because a DNS lookup failed at 3am wastes their time and, worse,
   * trains them to ignore the message that means it for real.
   */
  public enum Kind {
    /** Network trouble or a Microsoft outage. The same call may well succeed in ten minutes. */
    TRANSIENT,
    /** The stored session can no longer be renewed. Only {@code chronit login} fixes it. */
    NEEDS_LOGIN,
    /**
     * Something about the account itself is wrong — no Minecraft profile, an Xbox restriction,
     * an unreadable token file. Logging in again would fail the same way.
     */
    PERMANENT
  }

  private final Kind kind;

  public AuthException(String message, Kind kind) {
    super(message);
    this.kind = kind;
  }

  public AuthException(String message, Throwable cause, Kind kind) {
    super(message, cause);
    this.kind = kind;
  }

  public Kind kind() {
    return kind;
  }

  /** True when the fix is an interactive {@code chronit login}, not a retry. */
  public boolean needsLogin() {
    return kind == Kind.NEEDS_LOGIN;
  }

  /** True when trying again later is worth doing. */
  public boolean isRetryable() {
    return kind == Kind.TRANSIENT;
  }
}
