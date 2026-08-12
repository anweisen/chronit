package dev.chronit.driver.mcpl;

import dev.chronit.core.config.ProxyConfig;
import dev.chronit.core.driver.AuthContext;
import dev.chronit.core.driver.ClientEvents;
import dev.chronit.core.driver.ClientHandle;
import dev.chronit.core.driver.ConnectRequest;
import dev.chronit.core.driver.DriverException;
import dev.chronit.core.driver.MinecraftClientDriver;
import dev.chronit.core.driver.ServerStatus;
import dev.chronit.core.driver.ServerTarget;
import org.geysermc.mcprotocollib.auth.GameProfile;
import org.geysermc.mcprotocollib.network.BuiltinFlags;
import org.geysermc.mcprotocollib.network.ProxyInfo;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.factory.ClientNetworkSessionFactory;
import org.geysermc.mcprotocollib.network.netty.DefaultPacketHandlerExecutor;
import org.geysermc.mcprotocollib.network.session.ClientNetworkSession;
import org.geysermc.mcprotocollib.protocol.MinecraftConstants;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftCodec;
import org.geysermc.mcprotocollib.protocol.data.status.PlayerInfo;
import org.geysermc.mcprotocollib.protocol.data.status.ServerStatusInfo;
import org.geysermc.mcprotocollib.protocol.data.status.VersionInfo;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.net.InetSocketAddress;
import java.time.Duration;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Client driver built on MCProtocolLib.
 *
 * <p>Speaks one protocol version, the one the bundled library was built for. That is a property of
 * the library rather than a choice made here: it ships a single packet codec, and the packet
 * classes encode that version's field layouts, so remapping packet ids alone could not reach
 * another version. Other versions are reached through a {@link PipelineCustomizer}, which is
 * optional and absent by default.
 */
public final class McplDriver implements MinecraftClientDriver {

    private static final Logger log = LoggerFactory.getLogger(McplDriver.class);

    public static final int NATIVE_PROTOCOL = MinecraftCodec.CODEC.getProtocolVersion();
    public static final String NATIVE_VERSION = MinecraftCodec.CODEC.getMinecraftVersion();

    /** Timing for pack delays, the tick loop and readiness deadlines. Small: these tasks are tiny. */
    private final ScheduledExecutorService scheduler;

    /** Blocking work — resource pack downloads — kept off the scheduler so ticks stay punctual. */
    private final ExecutorService io;

    public McplDriver() {
        this.scheduler = Executors.newScheduledThreadPool(2, daemonFactory("chronit-tick"));
        this.io = Executors.newCachedThreadPool(daemonFactory("chronit-io"));
    }

    @Override
    public String id() {
        return "mcpl-" + NATIVE_VERSION;
    }

    @Override
    public String nativeVersionName() {
        return NATIVE_VERSION;
    }

    @Override
    public int nativeProtocol() {
        return NATIVE_PROTOCOL;
    }

    @Override
    public ServerStatus ping(ServerTarget target, Duration timeout) throws DriverException {
        MinecraftProtocol protocol = new MinecraftProtocol();
        ClientNetworkSession session = ClientNetworkSessionFactory.factory()
                .setAddress(target.host(), target.port())
                .setProtocol(protocol)
                .setProxy(toProxyInfo(target.proxy()))
                .create();

        CompletableFuture<ServerStatusInfo> info = new CompletableFuture<>();
        AtomicLong latencyMillis = new AtomicLong(-1);

        session.setFlag(BuiltinFlags.CLIENT_CONNECT_TIMEOUT, (int) Math.max(1, timeout.toSeconds()));
        session.setFlag(MinecraftConstants.SERVER_INFO_HANDLER_KEY,
                (ignored, status) -> info.complete(status));
        session.setFlag(MinecraftConstants.SERVER_PING_TIME_HANDLER_KEY,
                (ignored, millis) -> latencyMillis.set(millis));
        session.addListener(new SessionAdapter() {
            @Override
            public void disconnected(DisconnectedEvent event) {
                // The library closes the connection once the ping round-trip finishes, so this is
                // the normal ending; it only matters when the status never arrived.
                info.completeExceptionally(new DriverException(
                        "Ping failed: " + Components.plain(event.getReason()), event.getCause()));
            }
        });

        try {
            session.connect(false);
            ServerStatusInfo status = info.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
            VersionInfo version = status.getVersionInfo();
            PlayerInfo players = status.getPlayerInfo();

            return new ServerStatus(
                    version != null ? version.getVersionName() : "unknown",
                    version != null ? version.getProtocolVersion() : -1,
                    players != null ? players.getOnlinePlayers() : -1,
                    players != null ? players.getMaxPlayers() : -1,
                    Components.plain(status.getDescription()),
                    Duration.ofMillis(Math.max(0, latencyMillis.get())));
        } catch (TimeoutException e) {
            throw new DriverException("No status response from " + target.address()
                    + " within " + dev.chronit.core.util.Durations.format(timeout), e);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw cause instanceof DriverException driverError
                    ? driverError
                    : new DriverException("Could not ping " + target.address() + ": " + cause.getMessage(), cause);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new DriverException("Interrupted while pinging " + target.address(), e);
        } finally {
            if (session.isConnected()) {
                session.disconnect("done");
            }
        }
    }

    @Override
    public ClientHandle connect(ConnectRequest request, ClientEvents events) throws DriverException {
        AuthContext auth = request.auth();
        if (auth.online() && (auth.accessToken() == null || auth.accessToken().isBlank())) {
            throw new DriverException("Account " + auth.username()
                    + " has no access token; run 'chronit login' for it first");
        }

        MinecraftProtocol protocol = new MinecraftProtocol(
                MinecraftCodec.CODEC,
                new GameProfile(auth.uuid(), auth.username()),
                auth.online() ? auth.accessToken() : null);

        ClientNetworkSession session = createSession(request, protocol);

        session.setFlag(BuiltinFlags.CLIENT_CONNECT_TIMEOUT,
                (int) Math.max(1, request.settings().connectTimeout().toSeconds()));
        session.setFlag(MinecraftConstants.AUTOMATIC_KEEP_ALIVE_MANAGEMENT, true);
        session.setFlag(MinecraftConstants.SEND_BLANK_KNOWN_PACKS_RESPONSE, true);
        session.setFlag(MinecraftConstants.FOLLOW_TRANSFERS, request.settings().followTransfers());

        log.info("Connecting to {} as {} ({}, protocol {}{})",
                request.target().address(), auth.username(),
                auth.online() ? "Microsoft account" : "offline mode",
                request.protocolVersion(),
                request.translated() ? ", translated" : "");

        McplSession handle = new McplSession(session, request, events, scheduler, io);
        handle.start();
        return handle;
    }

    private ClientNetworkSession createSession(ConnectRequest request, MinecraftProtocol protocol)
            throws DriverException {
        InetSocketAddress address = InetSocketAddress.createUnresolved(
                request.target().host(), request.target().port());
        ProxyInfo proxy = toProxyInfo(request.target().proxy());

        if (!request.translated() || request.protocolVersion() == NATIVE_PROTOCOL) {
            return ClientNetworkSessionFactory.factory()
                    .setRemoteSocketAddress(address)
                    .setProtocol(protocol)
                    .setProxy(proxy)
                    .create();
        }

        Optional<PipelineCustomizer> customizer =
                PipelineCustomizer.forTarget(NATIVE_PROTOCOL, request.protocolVersion());
        if (customizer.isEmpty()) {
            throw new DriverException(
                    "Server " + request.target().address() + " needs protocol " + request.protocolVersion()
                            + " but this build only speaks " + NATIVE_PROTOCOL + " (Minecraft " + NATIVE_VERSION
                            + "). Build with the 'via' profile, or use the -via image, to add protocol translation.");
        }

        log.debug("Translating {} -> {} using {}", NATIVE_PROTOCOL, request.protocolVersion(),
                customizer.get().id());
        return new TranslatingClientSession(
                address, protocol, DefaultPacketHandlerExecutor.createExecutor(), null, proxy,
                customizer.get(), NATIVE_PROTOCOL, request.protocolVersion(), request.target());
    }

    @Override
    public void shutdown() {
        scheduler.shutdownNow();
        io.shutdownNow();
    }

    private static ProxyInfo toProxyInfo(ProxyConfig proxy) {
        if (proxy == null) {
            return null;
        }
        ProxyInfo.Type type = switch (proxy.type()) {
            case SOCKS4 -> ProxyInfo.Type.SOCKS4;
            case SOCKS5 -> ProxyInfo.Type.SOCKS5;
            case HTTP -> ProxyInfo.Type.HTTP;
        };
        return new ProxyInfo(type, proxy.host(), proxy.port(), proxy.username(), proxy.password());
    }

    private static ThreadFactory daemonFactory(String prefix) {
        AtomicLong counter = new AtomicLong();
        return runnable -> {
            Thread thread = new Thread(runnable, prefix + "-" + counter.incrementAndGet());
            thread.setDaemon(true);
            return thread;
        };
    }
}
