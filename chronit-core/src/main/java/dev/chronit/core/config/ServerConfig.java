package dev.chronit.core.config;

import java.time.Duration;

/**
 * A server the bot can visit. Session settings left null are inherited from {@link DefaultsConfig}.
 *
 * @param protocol which protocol version to speak. {@code auto} (the default) connects natively
 *                 and only falls back to translation if the server rejects the version; a numeric
 *                 protocol id or a version name like {@code 1.20.4} forces translation, which
 *                 requires the optional ViaVersion module
 */
public record ServerConfig(
        String id,
        String host,
        Integer port,
        String protocol,
        String brand,
        ClientInfoConfig clientInformation,
        ResourcePackConfig resourcePack,
        Boolean acceptCodeOfConduct,
        Boolean followTransfers,
        ReadyWhenConfig readyWhen,
        Duration connectTimeout,
        SecureChatMode secureChat,
        ProxyConfig proxy) {

    public static final int DEFAULT_PORT = 25565;

    public int portOrDefault() {
        return port != null ? port : DEFAULT_PORT;
    }

    /** Display form used in logs and the web interface. */
    public String address() {
        return host + ":" + portOrDefault();
    }
}
