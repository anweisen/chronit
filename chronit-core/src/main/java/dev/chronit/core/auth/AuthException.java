package dev.chronit.core.auth;

/** Authentication failed, or an account needs a fresh interactive login. */
public class AuthException extends Exception {

    private final boolean needsLogin;

    public AuthException(String message, boolean needsLogin) {
        super(message);
        this.needsLogin = needsLogin;
    }

    public AuthException(String message, Throwable cause, boolean needsLogin) {
        super(message, cause);
        this.needsLogin = needsLogin;
    }

    /** True when the fix is an interactive {@code chronit login}, not a retry. */
    public boolean needsLogin() {
        return needsLogin;
    }
}
