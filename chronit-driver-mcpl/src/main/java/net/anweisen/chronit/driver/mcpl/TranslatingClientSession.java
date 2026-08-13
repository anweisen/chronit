package net.anweisen.chronit.driver.mcpl;

import net.anweisen.chronit.core.driver.ServerTarget;
import io.netty.channel.Channel;
import io.netty.channel.ChannelHandler;
import org.checkerframework.checker.nullness.qual.NonNull;
import org.geysermc.mcprotocollib.network.ProxyInfo;
import org.geysermc.mcprotocollib.network.helper.NettyHelper;
import org.geysermc.mcprotocollib.network.netty.MinecraftChannelInitializer;
import org.geysermc.mcprotocollib.network.session.ClientNetworkSession;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;

import java.net.SocketAddress;
import java.util.concurrent.Executor;

/**
 * A client session that lets a {@link PipelineCustomizer} extend the channel.
 *
 * <p>The library builds its pipeline in {@code getChannelHandler()}, which is protected and
 * overridable. This repeats that setup and then hands the finished channel to the customizer, which
 * is the point at which a translation layer can wrap the codec.
 */
final class TranslatingClientSession extends ClientNetworkSession {

    private final PipelineCustomizer customizer;
    private final int nativeProtocol;
    private final int targetProtocol;
    private final ServerTarget target;

    TranslatingClientSession(SocketAddress remoteAddress,
                             MinecraftProtocol protocol,
                             Executor packetHandlerExecutor,
                             SocketAddress bindAddress,
                             ProxyInfo proxy,
                             PipelineCustomizer customizer,
                             int nativeProtocol,
                             int targetProtocol,
                             ServerTarget target) {
        super(remoteAddress, protocol, packetHandlerExecutor, bindAddress, proxy);
        this.customizer = customizer;
        this.nativeProtocol = nativeProtocol;
        this.targetProtocol = targetProtocol;
        this.target = target;
    }

    @Override
    protected ChannelHandler getChannelHandler() {
        return new MinecraftChannelInitializer<>(channel -> {
            MinecraftProtocol protocol = getPacketProtocol();
            protocol.newClientSession(TranslatingClientSession.this);
            return TranslatingClientSession.this;
        }, true) {
            @Override
            public void initChannel(@NonNull Channel channel) throws Exception {
                NettyHelper.addProxy(getProxy(), channel.pipeline());
                NettyHelper.initializeHAProxySupport(TranslatingClientSession.this, channel);

                super.initChannel(channel);

                // After the standard handlers exist, so the customizer can position itself
                // relative to them.
                customizer.customize(channel, nativeProtocol, targetProtocol, target);
            }
        };
    }
}
