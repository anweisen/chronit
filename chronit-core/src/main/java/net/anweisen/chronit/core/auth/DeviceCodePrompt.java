package net.anweisen.chronit.core.auth;

import java.time.Instant;

/**
 * The code a person must enter to authorise the bot.
 *
 * <p>The device code flow is used rather than the redirect-based authorisation code flow because
 * this runs headless in a container: there is no browser to redirect and no public HTTPS endpoint
 * to redirect to. The user opens the link on any device, types the code, and the container polls
 * until Microsoft says it is done.
 *
 * @param directVerificationUri a link with the code already embedded, so there is nothing to type
 */
public record DeviceCodePrompt(
        String userCode,
        String verificationUri,
        String directVerificationUri,
        Instant expiresAt) {

    /** Ready-to-print instructions for the CLI and the container log. */
    public String describe() {
        return "Open " + verificationUri + " and enter the code " + userCode
                + "  (or open " + directVerificationUri + ")";
    }
}
