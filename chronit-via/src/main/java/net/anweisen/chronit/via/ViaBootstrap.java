package net.anweisen.chronit.via;

import com.viaversion.viaversion.api.Via;
import com.viaversion.viaversion.api.connection.UserConnection;
import com.viaversion.viaversion.api.protocol.version.ProtocolVersion;
import com.viaversion.viaversion.api.protocol.version.VersionProvider;
import io.netty.channel.Channel;
import io.netty.util.AttributeKey;
import net.raphimc.vialoader.ViaLoader;
import net.raphimc.vialoader.impl.platform.ViaBackwardsPlatformImpl;
import net.raphimc.vialoader.impl.viaversion.VLLoader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * One-time initialisation of the translation stack.
 *
 * <p>ViaVersion is a process-wide singleton, so this happens once and lazily — a deployment that
 * never touches a server needing translation pays nothing for having the module installed.
 */
final class ViaBootstrap {

    private static final Logger log = LoggerFactory.getLogger(ViaBootstrap.class);

    /**
     * The version each connection is translating to.
     *
     * <p>Kept per channel rather than globally because different servers in one schedule can need
     * different versions, and visits can overlap.
     */
    static final AttributeKey<ProtocolVersion> TARGET_VERSION =
            AttributeKey.valueOf("chronit-target-version");

    private static volatile boolean initialised;

    private ViaBootstrap() {
    }

    static void ensureInitialised() {
        if (initialised) {
            return;
        }
        synchronized (ViaBootstrap.class) {
            if (initialised) {
                return;
            }
            ViaLoader.init(null, new VLLoader() {
                @Override
                public void load() {
                    super.load();
                    // Client-side translation: the "server version" is whatever the target server
                    // runs, which is decided per connection.
                    Via.getManager().getProviders().use(VersionProvider.class, new PerChannelVersionProvider());
                }
            }, null, null, ViaBackwardsPlatformImpl::new);

            initialised = true;
            log.info("Protocol translation ready — can reach Minecraft {} to {}",
                    ProtocolVersion.getProtocols().getFirst().getName(),
                    ProtocolVersion.getProtocols().getLast().getName());
        }
    }

    /** Reads the target version off the channel the connection belongs to. */
    private static final class PerChannelVersionProvider implements VersionProvider {

        @Override
        public ProtocolVersion getClosestServerProtocol(UserConnection connection) {
            Channel channel = connection.getChannel();
            ProtocolVersion target = channel != null ? channel.attr(TARGET_VERSION).get() : null;
            if (target != null) {
                return target;
            }
            // No target recorded means this connection was not set up for translation; speaking our
            // own version is the correct no-op.
            return connection.getProtocolInfo().protocolVersion();
        }
    }
}
