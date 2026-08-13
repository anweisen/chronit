package net.anweisen.chronit.core.driver;

import java.util.List;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.ServiceLoader;

/**
 * Optional protocol translation, letting the driver's native client version reach servers running
 * other versions.
 *
 * <p>Discovered through {@link ServiceLoader}, so {@code chronit-core} has no compile-time
 * reference to any implementation and the application works unchanged when none is present.
 *
 * <p>Version name resolution lives here rather than in a table in core deliberately: any such table
 * needs an entry for every Minecraft release ever made and goes stale the moment one ships. A
 * translation layer already maintains an authoritative registry, so it is asked instead.
 */
public interface TranslationProvider {

    String id();

    /** Whether a client speaking {@code nativeProtocol} can be translated to {@code targetProtocol}. */
    boolean canTranslate(int nativeProtocol, int targetProtocol);

    /**
     * Resolves a version name such as {@code 1.20.4} to its protocol number.
     *
     * @return empty when the name is unknown
     */
    OptionalInt resolveVersionName(String versionName);

    /** Human-readable version name for a protocol number, for logs. */
    Optional<String> versionName(int protocol);

    /** All providers on the classpath, in discovery order. */
    static List<TranslationProvider> discover() {
        return ServiceLoader.load(TranslationProvider.class).stream()
                .map(ServiceLoader.Provider::get)
                .toList();
    }

    /** The first provider that can reach {@code targetProtocol}, if any. */
    static Optional<TranslationProvider> forTarget(int nativeProtocol, int targetProtocol) {
        return discover().stream()
                .filter(provider -> provider.canTranslate(nativeProtocol, targetProtocol))
                .findFirst();
    }
}
