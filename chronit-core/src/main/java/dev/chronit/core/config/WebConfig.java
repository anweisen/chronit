package dev.chronit.core.config;

/**
 * The optional status and login interface.
 *
 * @param bind  interface to listen on. Defaults to loopback; set to {@code 0.0.0.0} only behind a
 *              reverse proxy, and set a token when you do
 * @param token bearer token required on every request. When null, access is unauthenticated, which
 *              is refused unless the listener is bound to loopback
 */
public record WebConfig(
        Boolean enabled,
        String bind,
        Integer port,
        String token) {

    public static final WebConfig DISABLED = new WebConfig(Boolean.FALSE, "127.0.0.1", 8477, null);

    public boolean isEnabled() {
        return enabled != null && enabled;
    }

    public String bindOrDefault() {
        return bind != null ? bind : "127.0.0.1";
    }

    public int portOrDefault() {
        return port != null ? port : 8477;
    }

    public boolean isLoopbackOnly() {
        String b = bindOrDefault();
        return b.equals("127.0.0.1") || b.equals("::1") || b.equalsIgnoreCase("localhost");
    }
}
