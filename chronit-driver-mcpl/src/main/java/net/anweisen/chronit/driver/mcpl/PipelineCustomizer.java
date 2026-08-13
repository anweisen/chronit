package net.anweisen.chronit.driver.mcpl;

import net.anweisen.chronit.core.driver.ServerTarget;
import io.netty.channel.Channel;

import java.util.Optional;
import java.util.ServiceLoader;

/**
 * Hook for a translation layer to insert itself into the connection.
 *
 * <p>Separate from {@code TranslationProvider} in core on purpose: this one deals in Netty
 * channels, which core must not know about. An implementation typically provides both — the core
 * one so the orchestrator can plan, this one so the driver can execute.
 *
 * <p>Discovered through {@link ServiceLoader}, so the driver has no compile-time reference to any
 * implementation and works normally when none is installed.
 */
public interface PipelineCustomizer {

    String id();

    /** Whether a client speaking {@code nativeProtocol} can be translated to {@code targetProtocol}. */
    boolean canTranslate(int nativeProtocol, int targetProtocol);

    /**
     * Called once per connection, after the standard Minecraft handlers are in the pipeline and
     * before anything is written.
     */
    void customize(Channel channel, int nativeProtocol, int targetProtocol, ServerTarget target);

    /** The first installed customizer that can reach {@code targetProtocol}. */
    static Optional<PipelineCustomizer> forTarget(int nativeProtocol, int targetProtocol) {
        return ServiceLoader.load(PipelineCustomizer.class).stream()
                .map(ServiceLoader.Provider::get)
                .filter(customizer -> customizer.canTranslate(nativeProtocol, targetProtocol))
                .findFirst();
    }
}
