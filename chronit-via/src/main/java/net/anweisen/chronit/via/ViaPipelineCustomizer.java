package net.anweisen.chronit.via;

import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.connection.UserConnectionImpl;
import com.viaversion.viaversion.protocol.ProtocolPipelineImpl;
import net.anweisen.chronit.core.driver.ServerTarget;
import net.anweisen.chronit.driver.mcpl.PipelineCustomizer;
import io.netty.channel.Channel;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Inserts ViaVersion into a connection so the native client can talk to an older server.
 *
 * <p>The client above this point is unchanged — it still speaks its own version — and the
 * translation happens on the wire. That is what makes the arrangement modular: nothing in the
 * driver or the orchestrator knows this is happening beyond the fact that it asked for a different
 * protocol number.
 */
public final class ViaPipelineCustomizer implements PipelineCustomizer {

    private static final Logger log = LoggerFactory.getLogger(ViaPipelineCustomizer.class);

    @Override
    public String id() {
        return "viaversion";
    }

    @Override
    public boolean canTranslate(int nativeProtocol, int targetProtocol) {
        ViaBootstrap.ensureInitialised();
        return ViaVersions.canTranslate(nativeProtocol, targetProtocol);
    }

    @Override
    public void customize(Channel channel, int nativeProtocol, int targetProtocol, ServerTarget target) {
        ViaBootstrap.ensureInitialised();

        ProtocolVersion clientVersion = ProtocolVersion.getProtocol(nativeProtocol);
        ProtocolVersion serverVersion = ProtocolVersion.getProtocol(targetProtocol);

        // The version provider reads this back for every packet, so it has to be set before the
        // pipeline is built.
        channel.attr(ViaBootstrap.TARGET_VERSION).set(serverVersion);

        UserConnectionImpl connection = new UserConnectionImpl(channel, true);
        connection.getProtocolInfo().setProtocolVersion(clientVersion);
        connection.getProtocolInfo().setServerProtocolVersion(serverVersion);
        new ProtocolPipelineImpl(connection);

        channel.pipeline().addLast(new ChronitVLPipeline(connection, serverVersion));

        log.debug("Translating {} -> {} for {}",
                clientVersion.getName(), serverVersion.getName(), target.address());
    }
}
