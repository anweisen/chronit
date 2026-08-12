package dev.chronit.core.run;

import dev.chronit.core.config.ProtocolSpec;
import dev.chronit.core.driver.DriverException;
import dev.chronit.core.driver.MinecraftClientDriver;
import dev.chronit.core.driver.ServerStatus;
import dev.chronit.core.driver.ServerTarget;
import dev.chronit.core.driver.TranslationProvider;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides which protocol version to speak to a server.
 *
 * <p>The interesting case is {@code auto}. It is tempting to ping the server and speak whatever it
 * reports, but that is wrong: a server running a version-compatibility plugin advertises its own
 * native version while accepting a wide range of client versions, so translating down to the
 * advertised version would be unnecessary and occasionally worse. Conversely a ping cannot prove a
 * server will accept the newest protocol.
 *
 * <p>So {@code auto} tries the native version first — which is what the large majority of public
 * servers accept — and only after a rejection that names the version does it ping, work out what
 * the server actually is, and retry through a translation layer. The successful choice is then
 * remembered so the detour happens once rather than on every scheduled run.
 */
public final class ProtocolResolver {

    private static final Logger log = LoggerFactory.getLogger(ProtocolResolver.class);

    private static final Duration PING_TIMEOUT = Duration.ofSeconds(10);

    private final MinecraftClientDriver driver;
    private final List<TranslationProvider> providers;

    /** Remembers what worked, keyed by host:port. */
    private final Map<String, Plan> learned = new ConcurrentHashMap<>();

    public ProtocolResolver(MinecraftClientDriver driver) {
        this(driver, TranslationProvider.discover());
    }

    ProtocolResolver(MinecraftClientDriver driver, List<TranslationProvider> providers) {
        this.driver = driver;
        this.providers = providers;
        if (!providers.isEmpty()) {
            log.debug("Protocol translation available via {}",
                    providers.stream().map(TranslationProvider::id).toList());
        }
    }

    /** @param note human-readable explanation for logs */
    public record Plan(int protocolVersion, boolean translated, String note) {
    }

    public boolean hasTranslation() {
        return !providers.isEmpty();
    }

    /** Works out the first attempt for a target. */
    public Plan plan(ServerTarget target) throws DriverException {
        Plan remembered = learned.get(target.address());
        if (remembered != null) {
            return remembered;
        }

        return switch (target.protocol()) {
            case ProtocolSpec.Auto ignored -> new Plan(driver.nativeProtocol(), false,
                    "native protocol " + driver.nativeProtocol() + " (Minecraft " + driver.nativeVersionName() + ")");

            case ProtocolSpec.Exact exact -> exact.protocol() == driver.nativeProtocol()
                    ? new Plan(driver.nativeProtocol(), false, "native protocol " + driver.nativeProtocol())
                    : translatedPlan(exact.protocol(), "protocol " + exact.protocol());

            case ProtocolSpec.Named named -> {
                if (named.version().equalsIgnoreCase(driver.nativeVersionName())) {
                    yield new Plan(driver.nativeProtocol(), false,
                            "native protocol " + driver.nativeProtocol());
                }
                OptionalInt resolved = resolveName(named.version());
                if (resolved.isEmpty()) {
                    throw new DriverException("Cannot resolve Minecraft version '" + named.version() + "'. "
                            + (providers.isEmpty()
                            ? "Version names need the ViaVersion module; either use the -via image, or set "
                            + "protocol to the numeric protocol id instead."
                            : "No installed translation layer recognises that version name."));
                }
                yield translatedPlan(resolved.getAsInt(), "Minecraft " + named.version());
            }
        };
    }

    /**
     * Called after a connection was rejected over the version.
     *
     * @return a translated plan to retry with, or empty when nothing better is available
     */
    public Optional<Plan> replan(ServerTarget target) {
        if (providers.isEmpty()) {
            log.warn("{} rejected protocol {}. This build speaks only Minecraft {}; use the -via image "
                            + "to add protocol translation.",
                    target.address(), driver.nativeProtocol(), driver.nativeVersionName());
            return Optional.empty();
        }

        ServerStatus status;
        try {
            status = driver.ping(target, PING_TIMEOUT);
        } catch (DriverException e) {
            log.warn("{} rejected our version and did not answer a status ping either ({}); "
                    + "cannot work out what to translate to", target.address(), e.getMessage());
            return Optional.empty();
        }

        if (status.protocolVersion() <= 0 || status.protocolVersion() == driver.nativeProtocol()) {
            log.warn("{} rejected protocol {} but reports the same version itself; the rejection was "
                    + "probably not about the protocol", target.address(), driver.nativeProtocol());
            return Optional.empty();
        }

        Optional<TranslationProvider> provider = providers.stream()
                .filter(p -> p.canTranslate(driver.nativeProtocol(), status.protocolVersion()))
                .findFirst();
        if (provider.isEmpty()) {
            log.warn("{} runs protocol {} ({}), which no installed translation layer can reach",
                    target.address(), status.protocolVersion(), status.versionName());
            return Optional.empty();
        }

        Plan plan = new Plan(status.protocolVersion(), true,
                "translated to protocol " + status.protocolVersion() + " (" + status.versionName()
                        + ") via " + provider.get().id());
        log.info("{} rejected protocol {}; retrying {}",
                target.address(), driver.nativeProtocol(), plan.note());
        return Optional.of(plan);
    }

    /** Records a plan that produced a successful join, so later runs skip the discovery. */
    public void remember(ServerTarget target, Plan plan) {
        learned.put(target.address(), plan);
    }

    public void forget(ServerTarget target) {
        learned.remove(target.address());
    }

    private Plan translatedPlan(int protocol, String description) throws DriverException {
        Optional<TranslationProvider> provider = providers.stream()
                .filter(p -> p.canTranslate(driver.nativeProtocol(), protocol))
                .findFirst();
        if (provider.isEmpty()) {
            throw new DriverException("Reaching " + description + " needs protocol translation, but "
                    + (providers.isEmpty()
                    ? "none is installed. Use the -via image, or build with the 'via' Maven profile."
                    : "no installed translation layer supports it."));
        }
        return new Plan(protocol, true, "translated to " + description + " via " + provider.get().id());
    }

    private OptionalInt resolveName(String versionName) {
        for (TranslationProvider provider : providers) {
            OptionalInt resolved = provider.resolveVersionName(versionName);
            if (resolved.isPresent()) {
                return resolved;
            }
        }
        return OptionalInt.empty();
    }
}
