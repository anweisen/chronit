package dev.chronit.core.config;

import java.nio.file.Path;

/**
 * A Minecraft account the bot can log in as.
 *
 * @param id         referenced by visits
 * @param auth       authentication mode
 * @param username   required for {@link AuthMode#OFFLINE}; ignored for Microsoft accounts, whose
 *                   name comes from the profile
 * @param tokenStore where the Microsoft refresh/access tokens are persisted between runs. Defaults
 *                   to {@code <stateDir>/tokens/<id>.json}
 * @param clientId   Azure application (client) ID for the device code flow. Defaults to the
 *                   library's built-in official client ID; override to use your own registration
 */
public record AccountConfig(
        String id,
        AuthMode auth,
        String username,
        Path tokenStore,
        String clientId) {

    public enum AuthMode {
        /** Microsoft account via the OAuth2 device code flow. Required for online-mode servers. */
        MICROSOFT,
        /**
         * No authentication; the username is sent as-is and the UUID derived the same way vanilla
         * derives it offline. Only works against servers with {@code online-mode=false}, which
         * makes it the right choice for local testing.
         */
        OFFLINE
    }

    public AuthMode authOrDefault() {
        return auth != null ? auth : AuthMode.MICROSOFT;
    }
}
