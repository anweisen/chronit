package dev.chronit.core.driver;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The protocol carries no machine-readable kick reason — just a message meant for a person — so
 * the classification is textual. It decides whether {@code protocol: auto} bothers retrying
 * through a translation layer, so the version cases are the ones that matter.
 */
class DisconnectInfoTest {

    @Test
    void recognisesVersionRejections() {
        for (String reason : new String[]{
                "Outdated client! Please use 1.20.4",
                "Outdated server! I'm still on 1.20.4",
                "Your version is not supported",
                "multiplayer.disconnect.outdated_client",
                "Incompatible client version"}) {
            DisconnectInfo info = DisconnectInfo.fromKick(reason, null);
            assertEquals(DisconnectInfo.Kind.VERSION_MISMATCH, info.kind(), reason);
            assertTrue(info.suggestsTranslation(), reason);
        }
    }

    @Test
    void recognisesResourcePackRefusals() {
        assertEquals(DisconnectInfo.Kind.RESOURCE_PACK,
                DisconnectInfo.fromKick("You must accept the resource pack to play", null).kind());
    }

    @Test
    void recognisesAuthenticationFailures() {
        assertEquals(DisconnectInfo.Kind.AUTH_FAILED,
                DisconnectInfo.fromKick("Failed to log in: invalid session", null).kind());
        assertEquals(DisconnectInfo.Kind.AUTH_FAILED,
                DisconnectInfo.fromKick("Chat validation error", null).kind());
    }

    @Test
    void treatsOtherKicksAsOrdinaryAndNotWorthTranslating() {
        DisconnectInfo info = DisconnectInfo.fromKick("The server is full", null);
        assertEquals(DisconnectInfo.Kind.KICKED, info.kind());
        assertFalse(info.suggestsTranslation());
    }

    @Test
    void anEmptyReasonIsUnknownRatherThanAKick() {
        assertEquals(DisconnectInfo.Kind.UNKNOWN, DisconnectInfo.fromKick("", null).kind());
        assertEquals(DisconnectInfo.Kind.UNKNOWN, DisconnectInfo.fromKick(null, null).kind());
    }

    @Test
    void ourOwnDisconnectIsNeverMistakenForAKick() {
        assertEquals(DisconnectInfo.Kind.CLIENT_CLOSED,
                DisconnectInfo.clientClosed("Visit complete").kind());
    }

    @Test
    void transportFailuresAreNetworkFailuresAndReportTheException() {
        DisconnectInfo info = DisconnectInfo.fromCause(
                new java.io.IOException("wrapper", new java.net.ConnectException("Connection refused")));

        assertEquals(DisconnectInfo.Kind.NETWORK, info.kind());
        assertTrue(info.reason().contains("Connection refused"),
                "the exception is far more useful than the generic component: " + info.reason());
    }

    @Test
    void aCauseThatDoesNameTheVersionIsStillAVersionMismatch() {
        // Some failures arrive as an exception whose message is the server's own wording.
        assertEquals(DisconnectInfo.Kind.VERSION_MISMATCH,
                DisconnectInfo.fromCause(new RuntimeException("Outdated client, please use 1.20.4")).kind());
    }

    @Test
    void aCauseWithNoMessageStillProducesSomethingReadable() {
        DisconnectInfo info = DisconnectInfo.fromCause(new java.net.SocketException());
        assertEquals(DisconnectInfo.Kind.NETWORK, info.kind());
        assertTrue(info.reason().contains("SocketException"), info.reason());
    }
}
