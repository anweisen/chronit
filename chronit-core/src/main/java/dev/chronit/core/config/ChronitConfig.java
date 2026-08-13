package dev.chronit.core.config;

import java.nio.file.Path;
import java.util.List;
import java.util.Optional;

/**
 * Root of the configuration file.
 *
 * @param stateDir    where tokens, run history and the resource pack cache live
 * @param secretsFile optional YAML file of {@code key: value} pairs referenced as
 *                    {@code {{secrets.key}}} elsewhere in the config, so passwords need not sit in
 *                    the main file
 */
public record ChronitConfig(
        Path stateDir,
        Path secretsFile,
        DefaultsConfig defaults,
        AuthConfig auth,
        List<AccountConfig> accounts,
        List<ServerConfig> servers,
        List<JobConfig> jobs,
        WebConfig web) {

    public static final Path DEFAULT_STATE_DIR = Path.of("/data");

    public Path stateDirOrDefault() {
        return stateDir != null ? stateDir : DEFAULT_STATE_DIR;
    }

    public AuthConfig authOrDefaults() {
        return auth != null ? auth : AuthConfig.DEFAULTS;
    }

    /** Defaults with anything unset filled in from {@link DefaultsConfig#DEFAULTS}. */
    public DefaultsConfig effectiveDefaults() {
        return defaults != null
                ? defaults.withFallback(DefaultsConfig.DEFAULTS)
                : DefaultsConfig.DEFAULTS;
    }

    public WebConfig webOrDisabled() {
        return web != null ? web : WebConfig.DISABLED;
    }

    public List<AccountConfig> accountsOrEmpty() {
        return accounts != null ? accounts : List.of();
    }

    public List<ServerConfig> serversOrEmpty() {
        return servers != null ? servers : List.of();
    }

    public List<JobConfig> jobsOrEmpty() {
        return jobs != null ? jobs : List.of();
    }

    public Optional<AccountConfig> account(String id) {
        return accountsOrEmpty().stream().filter(a -> a.id().equals(id)).findFirst();
    }

    public Optional<ServerConfig> server(String id) {
        return serversOrEmpty().stream().filter(s -> s.id().equals(id)).findFirst();
    }

    public Optional<JobConfig> job(String id) {
        return jobsOrEmpty().stream().filter(j -> j.id().equals(id)).findFirst();
    }
}
