package net.anweisen.chronit.core.config;

/**
 * Optional per-server outbound proxy.
 *
 * <p>Useful when several accounts must not appear to share an address, or when the container has no
 * direct route to the server.
 */
public record ProxyConfig(
    Type type,
    String host,
    int port,
    String username,
    String password) {

  public enum Type { SOCKS4, SOCKS5, HTTP }
}
