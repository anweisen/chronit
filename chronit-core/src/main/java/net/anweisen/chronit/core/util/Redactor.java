package net.anweisen.chronit.core.util;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * Registry of strings that must never appear in output.
 *
 * <p>Configuration routinely contains server login passwords (a {@code /login <password>} action
 * is the whole point of the command runner) and access tokens. Those values flow through command
 * text, exception messages and packet traces, so rather than trying to remember to redact at every
 * call site, every resolved secret is registered here once at load time and a logging converter
 * scrubs the final rendered line.
 */
public final class Redactor {

    private static final Set<String> SECRETS = new CopyOnWriteArraySet<>();

    /**
     * Secrets that get replaced rather than accumulated, keyed by what they are.
     *
     * <p>Access tokens are reissued every few hours. A daemon left running for a season would
     * otherwise collect hundreds of dead tokens, each one scanned for in every line it logs, to
     * protect strings that stopped being worth anything months ago.
     */
    private static final Map<String, String> ROTATING = new ConcurrentHashMap<>();

    private static final String MASK = "***";

    /** Shortest value worth masking; below this, redaction produces more noise than safety. */
    private static final int MIN_LENGTH = 4;

    private Redactor() {
    }

    /** Registers a value to be masked in all future output. No-op for null/short values. */
    public static void register(String secret) {
        if (secret != null && secret.length() >= MIN_LENGTH) {
            SECRETS.add(secret);
        }
    }

    /**
     * Registers a value that supersedes whatever was last registered under {@code key}.
     *
     * @param key what the secret is, e.g. {@code "minecraft:main"}. Stable across rotations
     */
    public static void registerRotating(String key, String secret) {
        if (key == null) {
            return;
        }
        if (secret == null || secret.length() < MIN_LENGTH) {
            ROTATING.remove(key);
            return;
        }
        ROTATING.put(key, secret);
    }

    /** Replaces every registered secret in {@code text} with a mask. */
    public static String redact(String text) {
        if (text == null || text.isEmpty() || SECRETS.isEmpty() && ROTATING.isEmpty()) {
            return text;
        }
        String result = text;
        for (String secret : SECRETS) {
            if (result.contains(secret)) {
                result = result.replace(secret, MASK);
            }
        }
        for (String secret : ROTATING.values()) {
            if (result.contains(secret)) {
                result = result.replace(secret, MASK);
            }
        }
        return result;
    }

    /** Visible for tests. */
    public static void clear() {
        SECRETS.clear();
        ROTATING.clear();
    }

    public static int size() {
        return SECRETS.size() + ROTATING.size();
    }
}
