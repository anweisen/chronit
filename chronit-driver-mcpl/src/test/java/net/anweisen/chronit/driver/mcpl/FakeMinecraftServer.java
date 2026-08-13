package net.anweisen.chronit.driver.mcpl;

import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.geysermc.mcprotocollib.auth.GameProfile;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.network.server.NetworkServer;
import org.geysermc.mcprotocollib.protocol.MinecraftConstants;
import org.geysermc.mcprotocollib.protocol.MinecraftProtocol;
import org.geysermc.mcprotocollib.protocol.codec.MinecraftCodec;
import org.geysermc.mcprotocollib.protocol.data.ProtocolState;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.GameMode;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PlayerSpawnInfo;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerType;
import org.geysermc.mcprotocollib.protocol.data.game.item.ItemStack;
import org.geysermc.mcprotocollib.protocol.data.handshake.HandshakeIntent;
import org.geysermc.mcprotocollib.protocol.packet.handshake.serverbound.ClientIntentionPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundResourcePackPushPacket;
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundCodeOfConductPacket;
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundFinishConfigurationPacket;
import org.geysermc.mcprotocollib.protocol.packet.configuration.serverbound.ServerboundFinishConfigurationPacket;
import org.geysermc.mcprotocollib.protocol.packet.cookie.clientbound.ClientboundCookieRequestPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerClosePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundOpenScreenPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundChunkBatchFinishedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundChunkBatchStartPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.login.clientbound.ClientboundLoginFinishedPacket;
import org.geysermc.mcprotocollib.protocol.packet.login.serverbound.ServerboundHelloPacket;
import org.geysermc.mcprotocollib.protocol.packet.login.serverbound.ServerboundLoginAcknowledgedPacket;

import java.net.InetSocketAddress;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * A scripted Minecraft server used to test the client end to end without a real server.
 *
 * <p>The protocol library's own server listeners are switched off so that the exchange can be
 * driven precisely: the point of these tests is to assert that the client answers each gate in the
 * join sequence, which means the gates have to arrive in a controlled order. Everything the client
 * sends is recorded for assertions.
 *
 * <p>Runs in offline mode — no encryption, no session server — so the tests need no credentials
 * and no network.
 */
final class FakeMinecraftServer implements AutoCloseable {

    /** Every packet the client sent, in order. */
    final ConcurrentLinkedQueue<Packet> received = new ConcurrentLinkedQueue<>();

    private final CountDownLatch clientConnected = new CountDownLatch(1);
    private final NetworkServer server;
    private final Options options;
    private final int port;

    private volatile Session clientSession;

    /** When set, this command triggers a menu — the way a plugin responds to /rewards. */
    private volatile MenuTrigger menuTrigger;

    private record MenuTrigger(String command, int containerId, String title,
                               int containerSlots, int stateId) {
    }

    /** What the scripted server should demand of the client. */
    record Options(boolean sendCodeOfConduct,
                   boolean sendResourcePack,
                   boolean resourcePackRequired,
                   String resourcePackUrl,
                   String resourcePackHash,
                   boolean requestCookie,
                   boolean sendChunkBatch,
                   String readyChatMessage) {

        static Options everything() {
            return new Options(true, true, true,
                    "https://example.invalid/pack.zip",
                    "0123456789abcdef0123456789abcdef01234567",
                    true, true, null);
        }

        static Options bare() {
            return new Options(false, false, false, null, null, false, true, null);
        }
    }

    FakeMinecraftServer(Options options) {
        this.options = options;
        // The server exposes the address it was asked to bind to, not the one the OS assigned, so
        // binding to port 0 would leave us with no way to find the real port. Reserve one instead.
        this.port = freePort();
        this.server = new NetworkServer(new InetSocketAddress("127.0.0.1", port), () -> {
            MinecraftProtocol protocol = new MinecraftProtocol(MinecraftCodec.CODEC);
            // Drive the handshake by hand rather than through the library's built-in flow.
            protocol.setUseDefaultListeners(false);
            return protocol;
        });
        server.setGlobalFlag(MinecraftConstants.ENCRYPT_CONNECTION, false);
        server.setGlobalFlag(MinecraftConstants.SHOULD_AUTHENTICATE, false);
        server.setGlobalFlag(MinecraftConstants.AUTOMATIC_KEEP_ALIVE_MANAGEMENT, false);
        server.addListener(new org.geysermc.mcprotocollib.network.event.server.ServerAdapter() {
            @Override
            public void sessionAdded(org.geysermc.mcprotocollib.network.event.server.SessionAddedEvent event) {
                clientSession = event.getSession();
                event.getSession().addListener(new Script());
                clientConnected.countDown();
            }
        });
    }

    FakeMinecraftServer start() {
        server.bind(true);
        return this;
    }

    int port() {
        return port;
    }

    private static int freePort() {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            socket.setReuseAddress(true);
            return socket.getLocalPort();
        } catch (java.io.IOException e) {
            throw new IllegalStateException("Could not reserve a port for the test server", e);
        }
    }

    boolean awaitClient(long timeoutMillis) throws InterruptedException {
        return clientConnected.await(timeoutMillis, TimeUnit.MILLISECONDS);
    }

    /**
     * Opens a menu and populates it, the way a plugin does: the window first, the contents a
     * moment later.
     *
     * @param containerSlots slots belonging to the menu itself, before the player's own inventory
     * @return the state id sent with the contents, which a click must echo back
     */
    int openMenu(int containerId, String title, int containerSlots, int stateId) {
        Session session = clientSession;
        if (session == null || !session.isConnected()) {
            throw new IllegalStateException("No client connected");
        }
        // The Component overload rather than the deprecated String one: besides being current,
        // the deprecated signature mentions a Lombok annotation that is not on the classpath, and
        // javac fails trying to render the deprecation warning.
        session.send(new ClientboundOpenScreenPacket(
                containerId, ContainerType.GENERIC_9X3, Component.text(title)));

        // A window always carries the player's 36 slots after its own.
        ItemStack[] items = new ItemStack[containerSlots + 36];
        for (int i = 0; i < containerSlots; i++) {
            items[i] = new ItemStack(1, 1);
        }
        session.send(new ClientboundContainerSetContentPacket(containerId, stateId, items, null));
        return stateId;
    }

    /**
     * Opens the given menu when the client sends {@code command}, which is how a plugin actually
     * behaves and what makes a waitFor on the menu meaningful rather than a race against a timer.
     */
    void openMenuOnCommand(String command, int containerId, String title, int containerSlots, int stateId) {
        this.menuTrigger = new MenuTrigger(command, containerId, title, containerSlots, stateId);
    }

    /** Tells the client the server closed the menu. */
    void closeMenu(int containerId) {
        Session session = clientSession;
        if (session != null && session.isConnected()) {
            session.send(new ClientboundContainerClosePacket(containerId));
        }
    }

    /** Sends a system chat message, for exercising waitFor and readiness patterns. */
    void say(String message) {
        Session session = clientSession;
        if (session != null && session.isConnected()) {
            session.send(new ClientboundSystemChatPacket(Component.text(message), false));
        }
    }

    /** Packets of a given type that the client sent, in order. */
    <T extends Packet> List<T> packets(Class<T> type) {
        return received.stream().filter(type::isInstance).map(type::cast).toList();
    }

    @Override
    public void close() {
        try {
            server.close(true);
        } catch (RuntimeException e) {
            // The library's server iterates its session list while shutting down, and the client
            // disconnecting at the same moment can leave a null in it. That is a race in test
            // teardown, after every assertion has already run, so failing the test over it would
            // only produce noise.
            System.err.println("Ignoring error while shutting down the test server: " + e);
        }
    }

    /**
     * Walks the client through handshake, login, configuration and into the world, sending whichever
     * gates the test asked for.
     */
    private final class Script extends SessionAdapter {

        @Override
        public void packetReceived(Session session, Packet packet) {
            received.add(packet);
            MinecraftProtocol protocol = session.getPacketProtocol();

            if (packet instanceof ClientIntentionPacket intention) {
                // Without the library's default listeners this transition is ours to make;
                // until it happens the next packet cannot be decoded.
                ProtocolState next = intention.getIntent() == HandshakeIntent.STATUS
                        ? ProtocolState.STATUS : ProtocolState.LOGIN;
                session.switchInboundState(() -> protocol.setInboundState(next));
                protocol.setOutboundState(next);
                return;
            }

            if (packet instanceof ServerboundHelloPacket hello) {
                // Straight to success: offline mode, so no encryption exchange and no compression.
                GameProfile profile = new GameProfile(hello.getProfileId(), hello.getUsername());
                session.send(new ClientboundLoginFinishedPacket(profile, UUID.randomUUID()));
                return;
            }

            if (packet instanceof ServerboundLoginAcknowledgedPacket) {
                session.switchInboundState(() -> protocol.setInboundState(ProtocolState.CONFIGURATION));
                protocol.setOutboundState(ProtocolState.CONFIGURATION);
                sendConfigurationGates(session);
                return;
            }

            MenuTrigger trigger = menuTrigger;
            if (trigger != null
                    && packet instanceof org.geysermc.mcprotocollib.protocol.packet.ingame
                    .serverbound.ServerboundChatCommandPacket command
                    && command.getCommand().equals(trigger.command())) {
                openMenu(trigger.containerId(), trigger.title(),
                        trigger.containerSlots(), trigger.stateId());
                return;
            }

            if (packet instanceof ServerboundFinishConfigurationPacket) {
                session.switchInboundState(() -> protocol.setInboundState(ProtocolState.GAME));
                protocol.setOutboundState(ProtocolState.GAME);
                enterWorld(session);
            }
        }

        private void sendConfigurationGates(Session session) {
            if (options.sendCodeOfConduct()) {
                session.send(new ClientboundCodeOfConductPacket(
                        "Be excellent to each other. No griefing."));
            }
            if (options.requestCookie()) {
                session.send(new ClientboundCookieRequestPacket(Key.key("chronit", "test_cookie")));
            }
            if (options.sendResourcePack()) {
                session.send(new ClientboundResourcePackPushPacket(
                        UUID.randomUUID(),
                        options.resourcePackUrl(),
                        options.resourcePackHash(),
                        options.resourcePackRequired(),
                        Component.text("This server requires a resource pack")));
            }
            session.send(new ClientboundFinishConfigurationPacket());
        }

        private void enterWorld(Session session) {
            session.send(new ClientboundLoginPacket(
                    1,
                    false,
                    new Key[]{Key.key("minecraft", "overworld")},
                    20,
                    8,
                    8,
                    false,
                    true,
                    false,
                    new PlayerSpawnInfo(0, Key.key("minecraft", "overworld"), 0L,
                            GameMode.SURVIVAL, null, false, false, null, 0, 63),
                    false,
                    false));

            // The initial teleport: the client must confirm it before it counts as in the world.
            session.send(new ClientboundPlayerPositionPacket(42, 8.5d, 64.0d, 8.5d, 0, 0, 0, 0f, 0f));

            if (options.sendChunkBatch()) {
                session.send(new ClientboundChunkBatchStartPacket());
                session.send(new ClientboundChunkBatchFinishedPacket(1));
            }
            if (options.readyChatMessage() != null) {
                session.send(new ClientboundSystemChatPacket(
                        Component.text(options.readyChatMessage()), false));
            }
        }
    }
}
