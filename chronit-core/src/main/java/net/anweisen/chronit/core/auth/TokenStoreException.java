package net.anweisen.chronit.core.auth;

/**
 * A stored session exists but cannot be read.
 *
 * <p>Distinct from there being no session at all, because the two want opposite handling: a missing
 * session means log in, an unreadable one means fix the key or the file — and above all do not log
 * in over the top of it, which would replace a working session with a new one for no reason.
 */
public class TokenStoreException extends RuntimeException {

  public TokenStoreException(String message) {
    super(message);
  }

  public TokenStoreException(String message, Throwable cause) {
    super(message, cause);
  }
}
