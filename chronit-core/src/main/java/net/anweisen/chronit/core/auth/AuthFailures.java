package net.anweisen.chronit.core.auth;

import net.lenni0451.commons.httpclient.exceptions.HttpRequestException;
import net.raphimc.minecraftauth.java.exception.MinecraftProfileNotFoundException;
import net.raphimc.minecraftauth.msa.exception.MsaRequestException;
import net.raphimc.minecraftauth.util.http.exception.ApiHttpRequestException;
import net.raphimc.minecraftauth.xbl.exception.XblRequestException;

import javax.net.ssl.SSLException;
import java.io.InterruptedIOException;
import java.net.ConnectException;
import java.net.NoRouteToHostException;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.UnknownHostException;
import java.util.Set;

/**
 * Works out what a failed token refresh actually means.
 *
 * <p>Everything the authentication library throws arrives as an {@link java.io.IOException}, so
 * without this a Microsoft outage and a revoked account are indistinguishable. Both were previously
 * reported as "run chronit login", which is wrong half the time and unhelpful the other half.
 *
 * <p>Anything unrecognised is treated as transient. That is the safe default for something running
 * unattended: a wrong "transient" costs a retry and shows up as an error in
 * {@code chronit accounts}, while a wrong "needs login" sends a person to a browser for nothing.
 */
final class AuthFailures {

  /**
   * OAuth error codes from the Microsoft token endpoint that mean the refresh token is finished.
   *
   * <p>{@code invalid_grant} is the one that matters in practice — it covers expiry, revocation
   * and a password change. The rest are here because they are equally final.
   */
  private static final Set<String> FATAL_OAUTH_ERRORS = Set.of(
      "invalid_grant",
      "invalid_client",
      "unauthorized_client",
      "interaction_required",
      "consent_required",
      "expired_token");

  /**
   * Xbox failures that describe the state of the account rather than the session. A fresh login
   * would hit exactly the same wall, so they are not a reason to prompt for one.
   */
  private static final Set<String> ACCOUNT_STATE_ERRORS = Set.of(
      "XO_E_ENFORCEMENT_BAN",
      "XO_E_THIRD_PARTY_BAN",
      "XO_E_ACCOUNT_CREATION_REQUIRED",
      "XO_E_ACCOUNT_TERMS_OF_USE_NOT_ACCEPTED",
      "XO_E_ACCOUNT_COUNTRY_NOT_AUTHORIZED",
      "XO_E_ACCOUNT_AGE_VERIFICATION_REQUIRED",
      "XO_E_ACCOUNT_PARENTALLY_RESTRICTED",
      "XO_E_ACCOUNT_CHILD_NOT_IN_FAMILY",
      "XO_E_ACCOUNT_CURFEW",
      "XO_E_ACCOUNT_TYPE_NOT_ALLOWED");

  private AuthFailures() {
  }

  static AuthException.Kind classify(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      AuthException.Kind kind = classifyOne(cause);
      if (kind != null) {
        return kind;
      }
      if (cause.getCause() == cause) {
        break;
      }
    }
    return AuthException.Kind.TRANSIENT;
  }

  /** @return null when this particular throwable says nothing useful */
  private static AuthException.Kind classifyOne(Throwable cause) {
    if (cause instanceof MinecraftProfileNotFoundException) {
      // The account authenticated fine; it just does not own Java Edition, or has never
      // picked a name.
      return AuthException.Kind.PERMANENT;
    }
    if (cause instanceof XblRequestException xbl) {
      return ACCOUNT_STATE_ERRORS.contains(xbl.getError())
          ? AuthException.Kind.PERMANENT
          : fromStatus(xbl);
    }
    if (cause instanceof MsaRequestException msa) {
      return FATAL_OAUTH_ERRORS.contains(msa.getError())
          ? AuthException.Kind.NEEDS_LOGIN
          : fromStatus(msa);
    }
    if (cause instanceof ApiHttpRequestException api) {
      return fromStatus(api);
    }
    if (cause instanceof HttpRequestException http) {
      return fromStatus(http);
    }
    if (cause instanceof UnknownHostException
        || cause instanceof ConnectException
        || cause instanceof NoRouteToHostException
        || cause instanceof SocketTimeoutException
        || cause instanceof InterruptedIOException
        || cause instanceof SocketException
        || cause instanceof SSLException) {
      return AuthException.Kind.TRANSIENT;
    }
    if (cause instanceof IllegalStateException && cause.getMessage() != null
        && cause.getMessage().contains("without a refresh token")) {
      // The library's own guard: the stored session was created without offline access, so
      // there is nothing to renew it with.
      return AuthException.Kind.NEEDS_LOGIN;
    }
    return null;
  }

  private static AuthException.Kind fromStatus(HttpRequestException error) {
    int status = error.getResponse() != null ? error.getResponse().getStatusCode() : 0;
    if (status == 429 || status >= 500) {
      // Throttling and outages: exactly what a later sweep is for.
      return AuthException.Kind.TRANSIENT;
    }
    if (status == 400 || status == 401 || status == 403) {
      return AuthException.Kind.NEEDS_LOGIN;
    }
    return AuthException.Kind.TRANSIENT;
  }

  /**
   * A one-line reason fit for a log or the dashboard.
   *
   * <p>The library's own messages are built for developers ({@code status: 400 Bad Request,
   * error: invalid_grant, error message: ...}); the useful half is the description.
   */
  static String describe(Throwable error) {
    for (Throwable cause = error; cause != null; cause = cause.getCause()) {
      if (cause instanceof ApiHttpRequestException api) {
        String message = api.getErrorMessage();
        return message != null && !message.isBlank()
            ? api.getError() + ": " + message
            : api.getError();
      }
      if (cause.getCause() == cause) {
        break;
      }
    }
    String message = error.getMessage();
    return message != null && !message.isBlank() ? message : error.toString();
  }
}
