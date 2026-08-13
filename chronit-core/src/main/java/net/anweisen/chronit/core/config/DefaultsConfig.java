package net.anweisen.chronit.core.config;

import java.time.Duration;

/**
 * Session settings applied to every server unless overridden per server.
 *
 * @param brand value sent on the {@code minecraft:brand} plugin channel. Vanilla sends
 *              {@code vanilla}; anything else is visible to server plugins
 * @param jitter relative randomisation applied to all configured delays, e.g. {@code 0.15} for ±15%
 */
public record DefaultsConfig(
        String brand,
        ClientInfoConfig clientInformation,
        ResourcePackConfig resourcePack,
        Boolean acceptCodeOfConduct,
        Boolean followTransfers,
        Double jitter,
        ReadyWhenConfig readyWhen,
        Duration connectTimeout,
        SecureChatMode secureChat,
        RetryConfig onFail) {

    public static final DefaultsConfig DEFAULTS = new DefaultsConfig(
            "vanilla",
            ClientInfoConfig.DEFAULTS,
            ResourcePackConfig.DEFAULTS,
            Boolean.TRUE,
            Boolean.TRUE,
            0.15d,
            ReadyWhenConfig.DEFAULTS,
            Duration.ofSeconds(30),
            SecureChatMode.AUTO,
            RetryConfig.DEFAULTS);

    /** Returns a copy with every unset field taken from {@code base}, recursing into sub-records. */
    public DefaultsConfig withFallback(DefaultsConfig base) {
        if (base == null) {
            return this;
        }
        return new DefaultsConfig(
                brand != null ? brand : base.brand,
                clientInformation != null
                        ? clientInformation.withFallback(base.clientInformation) : base.clientInformation,
                resourcePack != null
                        ? resourcePack.withFallback(base.resourcePack) : base.resourcePack,
                acceptCodeOfConduct != null ? acceptCodeOfConduct : base.acceptCodeOfConduct,
                followTransfers != null ? followTransfers : base.followTransfers,
                jitter != null ? jitter : base.jitter,
                readyWhen != null ? readyWhen.withFallback(base.readyWhen) : base.readyWhen,
                connectTimeout != null ? connectTimeout : base.connectTimeout,
                secureChat != null ? secureChat : base.secureChat,
                onFail != null ? onFail.withFallback(base.onFail) : base.onFail);
    }
}
