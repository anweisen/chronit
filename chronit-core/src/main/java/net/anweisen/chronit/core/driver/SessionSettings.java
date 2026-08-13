package net.anweisen.chronit.core.driver;

import net.anweisen.chronit.core.config.ChronitConfig;
import net.anweisen.chronit.core.config.DefaultsConfig;
import net.anweisen.chronit.core.config.ReadyWhenConfig;
import net.anweisen.chronit.core.config.ResourcePackConfig;
import net.anweisen.chronit.core.config.SecureChatMode;
import net.anweisen.chronit.core.config.ServerConfig;
import net.anweisen.chronit.core.util.Jitter;

import java.time.Duration;

/**
 * Per-server settings with defaults already merged in, so drivers never deal with nulls or
 * inheritance rules.
 */
public record SessionSettings(
        String brand,
        ClientInformation clientInformation,
        ResourcePackConfig resourcePack,
        boolean acceptCodeOfConduct,
        boolean followTransfers,
        Jitter jitter,
        ReadyWhenConfig readyWhen,
        Duration connectTimeout,
        SecureChatMode secureChat) {

    /** Merges a server's overrides over the configured defaults over the built-in defaults. */
    public static SessionSettings resolve(ChronitConfig config, ServerConfig server) {
        DefaultsConfig defaults = config.effectiveDefaults();

        ResourcePackConfig pack = server.resourcePack() != null
                ? server.resourcePack().withFallback(defaults.resourcePack()).withFallback(ResourcePackConfig.DEFAULTS)
                : defaults.resourcePack().withFallback(ResourcePackConfig.DEFAULTS);

        ReadyWhenConfig ready = server.readyWhen() != null
                ? server.readyWhen().withFallback(defaults.readyWhen()).withFallback(ReadyWhenConfig.DEFAULTS)
                : defaults.readyWhen().withFallback(ReadyWhenConfig.DEFAULTS);

        ClientInformation clientInfo = server.clientInformation() != null
                ? server.clientInformation().withFallback(defaults.clientInformation()).toClientInformation()
                : defaults.clientInformation().toClientInformation();

        // The pack cache lives under the state directory unless explicitly placed elsewhere, so a
        // single mounted volume is all the container needs.
        if (pack.cacheDir() == null || pack.cacheDir().equals(ResourcePackConfig.DEFAULTS.cacheDir())) {
            pack = new ResourcePackConfig(pack.mode(), pack.strict(), pack.downloadDelay(),
                    pack.applyDelay(), config.stateDirOrDefault().resolve("packs"),
                    pack.maxSizeMb(), pack.httpTimeout());
        }

        return new SessionSettings(
                server.brand() != null ? server.brand() : defaults.brand(),
                clientInfo,
                pack,
                server.acceptCodeOfConduct() != null ? server.acceptCodeOfConduct() : defaults.acceptCodeOfConduct(),
                server.followTransfers() != null ? server.followTransfers() : defaults.followTransfers(),
                new Jitter(defaults.jitter()),
                ready,
                server.connectTimeout() != null ? server.connectTimeout() : defaults.connectTimeout(),
                server.secureChat() != null ? server.secureChat() : defaults.secureChat());
    }
}
