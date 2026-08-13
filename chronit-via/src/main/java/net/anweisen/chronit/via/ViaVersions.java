package net.anweisen.chronit.via;

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.protocol.ProtocolPathEntry;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.OptionalInt;

/** Queries against ViaVersion's version registry. */
final class ViaVersions {

    private ViaVersions() {
    }

    /**
     * Whether a translation path exists.
     *
     * <p>Asks the protocol manager for an actual path rather than just comparing version numbers:
     * translation is a chain of per-version steps, and not every pair is connected.
     */
    static boolean canTranslate(int nativeProtocol, int targetProtocol) {
        if (nativeProtocol == targetProtocol) {
            return true;
        }
        if (!ProtocolVersion.isRegistered(nativeProtocol) || !ProtocolVersion.isRegistered(targetProtocol)) {
            return false;
        }
        List<ProtocolPathEntry> path = Via.getManager().getProtocolManager().getProtocolPath(
                ProtocolVersion.getProtocol(nativeProtocol),
                ProtocolVersion.getProtocol(targetProtocol));
        return path != null && !path.isEmpty();
    }

    /**
     * Resolves a version name such as {@code 1.20.4}.
     *
     * <p>Uses exact matching rather than ViaVersion's nearest-match helper: silently connecting as
     * a different version than the one written in the configuration would be a confusing way to
     * fail.
     */
    static OptionalInt resolveVersionName(String versionName) {
        String wanted = versionName.trim().toLowerCase(Locale.ROOT);
        for (ProtocolVersion version : ProtocolVersion.getProtocols()) {
            if (version.getName().toLowerCase(Locale.ROOT).equals(wanted)) {
                return OptionalInt.of(version.getVersion());
            }
            // Names for versions that share a protocol are recorded as a range, e.g. "1.21-1.21.1".
            if (version.getName().contains("-") && matchesRange(version.getName(), wanted)) {
                return OptionalInt.of(version.getVersion());
            }
        }
        return OptionalInt.empty();
    }

    private static boolean matchesRange(String rangeName, String wanted) {
        for (String part : rangeName.toLowerCase(Locale.ROOT).split("[-,/]")) {
            if (part.trim().equals(wanted)) {
                return true;
            }
        }
        return false;
    }

    static Optional<String> versionName(int protocol) {
        return ProtocolVersion.isRegistered(protocol)
                ? Optional.of(ProtocolVersion.getProtocol(protocol).getName())
                : Optional.empty();
    }
}
