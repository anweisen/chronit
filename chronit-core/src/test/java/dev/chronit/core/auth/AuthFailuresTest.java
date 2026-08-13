package dev.chronit.core.auth;

import net.lenni0451.commons.httpclient.HttpResponse;
import net.raphimc.minecraftauth.java.exception.MinecraftProfileNotFoundException;
import net.raphimc.minecraftauth.java.exception.MinecraftServicesRequestException;
import net.raphimc.minecraftauth.msa.exception.MsaRequestException;
import net.raphimc.minecraftauth.xbl.exception.XblRequestException;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.MalformedURLException;
import java.net.SocketTimeoutException;
import java.net.URI;
import java.net.URL;
import java.net.UnknownHostException;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AuthFailuresTest {

    /**
     * The whole point of the classifier: an outage and a revoked account both arrive as an
     * IOException, and only one of them is a reason to send someone to a browser.
     */
    @Test
    void distinguishesARevokedSessionFromAnOutage() {
        assertEquals(AuthException.Kind.NEEDS_LOGIN,
                AuthFailures.classify(msaError(400, "invalid_grant",
                        "The user has revoked access for the application")));
        assertEquals(AuthException.Kind.TRANSIENT,
                AuthFailures.classify(msaError(503, "temporarily_unavailable", "Service is busy")));
    }

    @Test
    void treatsThrottlingAsWorthRetrying() {
        assertEquals(AuthException.Kind.TRANSIENT,
                AuthFailures.classify(msaError(429, "request_throttled", "Too many requests")));
    }

    @Test
    void treatsNetworkTroubleAsTransient() {
        assertEquals(AuthException.Kind.TRANSIENT,
                AuthFailures.classify(new UnknownHostException("login.live.com")));
        assertEquals(AuthException.Kind.TRANSIENT,
                AuthFailures.classify(new SocketTimeoutException("Read timed out")));
    }

    @Test
    void treatsAnAccountWithoutTheGameAsPermanent() {
        MinecraftServicesRequestException notFound = new MinecraftServicesRequestException(
                response(404), "NOT_FOUND", "no profile");
        assertEquals(AuthException.Kind.PERMANENT,
                AuthFailures.classify(new MinecraftProfileNotFoundException(notFound)));
    }

    /** An Xbox ban is not something a fresh login would get past, so it must not ask for one. */
    @Test
    void treatsXboxAccountRestrictionsAsPermanent() {
        assertEquals(AuthException.Kind.PERMANENT,
                AuthFailures.classify(new XblRequestException(response(401),
                        XblRequestException.XO_E_ENFORCEMENT_BAN)));
        assertEquals(AuthException.Kind.PERMANENT,
                AuthFailures.classify(new XblRequestException(response(401),
                        XblRequestException.XO_E_ACCOUNT_CREATION_REQUIRED)));
    }

    @Test
    void treatsAnExpiredXboxTokenAsNeedingLogin() {
        assertEquals(AuthException.Kind.NEEDS_LOGIN,
                AuthFailures.classify(new XblRequestException(response(401),
                        XblRequestException.XO_E_INVALID_USER_TOKEN)));
    }

    @Test
    void looksThroughWrappingExceptions() {
        assertEquals(AuthException.Kind.NEEDS_LOGIN,
                AuthFailures.classify(new IOException("refresh failed",
                        msaError(400, "invalid_grant", "expired"))));
    }

    @Test
    void treatsASessionStoredWithoutARefreshTokenAsNeedingLogin() {
        assertEquals(AuthException.Kind.NEEDS_LOGIN, AuthFailures.classify(new IllegalStateException(
                "Can't refresh MSA token, because it was created without a refresh token.")));
    }

    /**
     * An unrecognised failure is assumed transient. A wrong guess here costs a retry; guessing the
     * other way sends someone to a browser for nothing and teaches them to ignore the message.
     */
    @Test
    void assumesAnythingUnrecognisedIsWorthRetrying() {
        assertEquals(AuthException.Kind.TRANSIENT, AuthFailures.classify(new IOException("who knows")));
    }

    @Test
    void describesTheUsefulHalfOfTheLibraryMessage() {
        String description = AuthFailures.describe(
                msaError(400, "invalid_grant", "The refresh token has expired"));

        assertTrue(description.contains("invalid_grant"), description);
        assertTrue(description.contains("The refresh token has expired"), description);
        // Not the developer-facing framing the library builds its getMessage() from.
        assertTrue(description.startsWith("invalid_grant"), description);
    }

    private static MsaRequestException msaError(int status, String error, String description) {
        return new MsaRequestException(response(status), error, description);
    }

    private static HttpResponse response(int status) {
        try {
            URL url = URI.create("https://login.live.com/oauth20_token.srf").toURL();
            return new HttpResponse(url, status, new byte[0], Map.of());
        } catch (MalformedURLException e) {
            throw new AssertionError(e);
        }
    }
}
