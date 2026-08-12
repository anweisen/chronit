package dev.chronit.driver.mcpl;

import dev.chronit.core.config.ReadyWhenConfig;
import dev.chronit.core.driver.ChatLine;
import dev.chronit.core.driver.ClientEvents;
import dev.chronit.core.driver.ClientHandle;
import dev.chronit.core.driver.ClientInformation;
import dev.chronit.core.driver.ConnectRequest;
import dev.chronit.core.driver.DisconnectInfo;
import dev.chronit.core.driver.Phase;
import dev.chronit.core.driver.ReadyInfo;
import dev.chronit.core.driver.ServerTarget;
import dev.chronit.core.driver.SessionSettings;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.ConnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.network.session.ClientNetworkSession;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement;
import org.geysermc.mcprotocollib.protocol.data.game.setting.ChatVisibility;
import org.geysermc.mcprotocollib.protocol.data.game.setting.ParticleStatus;
import org.geysermc.mcprotocollib.protocol.data.game.setting.SkinPart;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundCustomPayloadPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundPingPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundResourcePackPopPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundResourcePackPushPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.clientbound.ClientboundStoreCookiePacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundClientInformationPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundCustomPayloadPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundPongPacket;
import org.geysermc.mcprotocollib.protocol.packet.configuration.clientbound.ClientboundCodeOfConductPacket;
import org.geysermc.mcprotocollib.protocol.packet.configuration.serverbound.ServerboundAcceptCodeOfConductPacket;
import org.geysermc.mcprotocollib.protocol.packet.cookie.clientbound.ClientboundCookieRequestPacket;
import org.geysermc.mcprotocollib.protocol.packet.cookie.serverbound.ServerboundCookieResponsePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundDisguisedChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundLoginPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundPlayerChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundStartConfigurationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.ClientboundSystemChatPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundChunkBatchFinishedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundChunkBatchStartPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLevelChunkWithLightPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundClientTickEndPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundPlayerLoadedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundChunkBatchReceivedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerPosRotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.player.ServerboundMovePlayerStatusOnlyPacket;
import org.geysermc.mcprotocollib.protocol.packet.login.clientbound.ClientboundLoginFinishedPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.regex.Pattern;

/**
 * One live connection, from the handshake through to leaving the world.
 *
 * <p>The protocol library handles the handshake, encryption, compression, keep-alives and the
 * login/configuration/play transitions. Everything a server additionally demands before it will let
 * a client into the world is handled here: the code of conduct gate, resource packs, cookie
 * requests, client settings, teleport confirmation and chunk batch pacing. Skipping any of them
 * ends the connection or stalls the join indefinitely.
 */
final class McplSession implements ClientHandle {

    private static final Logger log = LoggerFactory.getLogger(McplSession.class);

    private static final Key BRAND_CHANNEL = Key.key("minecraft", "brand");

    /** A client tick is 50ms; the tick-end packet is expected once per tick. */
    private static final long TICK_MILLIS = 50L;

    /**
     * A stationary vanilla client still reports its state every second so the server knows it is
     * alive and has not desynchronised.
     */
    private static final int IDLE_STATUS_INTERVAL_TICKS = 20;

    /** The server clamps the requested chunk rate to this range. */
    private static final float MIN_CHUNKS_PER_TICK = 0.01f;
    private static final float MAX_CHUNKS_PER_TICK = 64.0f;

    private final ClientNetworkSession session;
    private final ConnectRequest request;
    private final SessionSettings settings;
    private final ClientEvents events;
    private final ScheduledExecutorService scheduler;

    private final CompletableFuture<ReadyInfo> ready = new CompletableFuture<>();
    private final CompletableFuture<DisconnectInfo> closed = new CompletableFuture<>();

    private final ResourcePackResponder packs;
    private final ChatState chat;

    /** Cookies the server asked us to keep. Proxies use these to recognise a returning client. */
    private final Map<Key, byte[]> cookies = new ConcurrentHashMap<>();

    private final Pattern readyPattern;
    private final AtomicInteger chunksReceived = new AtomicInteger();
    private final AtomicBoolean readyLatch = new AtomicBoolean();
    private final AtomicBoolean closing = new AtomicBoolean();

    private volatile Phase phase = Phase.CONNECTING;
    private volatile boolean gotJoinPacket;
    private volatile boolean teleportConfirmed;
    private volatile boolean chatMatched;
    private volatile int entityId = -1;
    private volatile String dimension = "unknown";
    private volatile String closeReason;

    private volatile double x;
    private volatile double y;
    private volatile double z;
    private volatile float yaw;
    private volatile float pitch;

    private volatile long batchStartedAtNanos;
    private volatile ScheduledFuture<?> tickTask;
    private volatile ScheduledFuture<?> readyTimeoutTask;

    private final Instant startedAt = Instant.now();

    McplSession(ClientNetworkSession session,
                ConnectRequest request,
                ClientEvents events,
                ScheduledExecutorService scheduler,
                ExecutorService io) {
        this.session = session;
        this.request = request;
        this.settings = request.settings();
        this.events = events;
        this.scheduler = scheduler;
        this.readyPattern = settings.readyWhen().chat() != null
                ? Pattern.compile(settings.readyWhen().chat())
                : null;
        this.packs = new ResourcePackResponder(
                session, settings.resourcePack(), settings.jitter(), events, scheduler, io,
                this::evaluateReadiness);
        this.chat = new ChatState(session, request.auth(), settings.secureChat());

        session.addListener(new Listener());
    }

    void start() {
        setPhase(Phase.CONNECTING);
        armReadyTimeout();
        // Non-blocking: failures arrive as a disconnect event rather than an exception here.
        session.connect(false);
    }

    // ---------------------------------------------------------------- ClientHandle

    @Override
    public ServerTarget target() {
        return request.target();
    }

    @Override
    public CompletableFuture<ReadyInfo> whenReady() {
        return ready;
    }

    @Override
    public CompletableFuture<DisconnectInfo> whenClosed() {
        return closed;
    }

    @Override
    public void sendCommand(String command) {
        requireInWorld("command");
        chat.sendCommand(command);
    }

    @Override
    public void sendChat(String message) {
        requireInWorld("chat message");
        chat.sendChat(message);
    }

    @Override
    public boolean isConnected() {
        return session.isConnected();
    }

    @Override
    public Phase phase() {
        return phase;
    }

    @Override
    public void disconnect(String reason) {
        if (closing.compareAndSet(false, true)) {
            closeReason = reason;
            setPhase(Phase.LEAVING);
            // Acknowledge anything outstanding before going, as a real client would on quit.
            chat.flushAcknowledgements();
            session.disconnect(Component.text(reason == null ? "Disconnecting" : reason));
        }
    }

    @Override
    public void close() {
        disconnect(closeReason != null ? closeReason : "Session closed");
    }

    private void requireInWorld(String what) {
        if (phase != Phase.IN_WORLD) {
            throw new IllegalStateException(
                    "Cannot send a " + what + " while the session is " + phase
                            + "; wait for whenReady() to complete");
        }
    }

    // ---------------------------------------------------------------- join sequence

    /**
     * Announces who we are: the client settings a real client sends on entering configuration, and
     * the brand. Sent again after joining, matching vanilla, because a server that moved us back
     * through configuration will have discarded the first copy.
     */
    private void sendClientIdentity() {
        ClientInformation info = settings.clientInformation();
        session.send(new ServerboundClientInformationPacket(
                info.locale(),
                info.viewDistance(),
                mapChatVisibility(info.chatVisibility()),
                info.chatColors(),
                mapSkinParts(info.skinParts()),
                info.mainHand() == ClientInformation.MainHand.LEFT
                        ? org.geysermc.mcprotocollib.protocol.data.game.entity.player.HandPreference.LEFT_HAND
                        : org.geysermc.mcprotocollib.protocol.data.game.entity.player.HandPreference.RIGHT_HAND,
                info.textFiltering(),
                info.allowServerListings(),
                mapParticleStatus(info.particleStatus())));

        session.send(new ServerboundCustomPayloadPacket(BRAND_CHANNEL, encodeBrand(settings.brand())));
    }

    /** The brand payload is a length-prefixed UTF-8 string. */
    private static byte[] encodeBrand(String brand) {
        byte[] text = brand.getBytes(StandardCharsets.UTF_8);
        ByteArrayOutputStream out = new ByteArrayOutputStream(text.length + 5);
        int length = text.length;
        while ((length & ~0x7F) != 0) {
            out.write((length & 0x7F) | 0x80);
            length >>>= 7;
        }
        out.write(length);
        out.writeBytes(text);
        return out.toByteArray();
    }

    private void onCodeOfConduct(String text) {
        if (!settings.acceptCodeOfConduct()) {
            log.warn("Server requires accepting a code of conduct but acceptCodeOfConduct is false; "
                    + "the connection will be closed by the server");
            events.onCodeOfConduct(text);
            return;
        }
        log.info("Accepting the server's code of conduct ({} characters)", text == null ? 0 : text.length());
        if (text != null && !text.isBlank()) {
            log.debug("Code of conduct text: {}", text);
        }
        session.send(ServerboundAcceptCodeOfConductPacket.INSTANCE);
        events.onCodeOfConduct(text);
    }

    private void onJoin(ClientboundLoginPacket packet) {
        entityId = packet.getEntityId();
        dimension = packet.getCommonPlayerSpawnInfo() != null
                ? String.valueOf(packet.getCommonPlayerSpawnInfo().getWorldName())
                : "unknown";
        gotJoinPacket = true;
        setPhase(Phase.JOINING);

        log.debug("Joined as entity {} in {} (server enforces secure chat: {})",
                entityId, dimension, packet.isEnforcesSecureChat());

        sendClientIdentity();
        if (!chat.establish()) {
            disconnect("No usable chat certificate and secureChat is set to 'on'");
            return;
        }
        evaluateReadiness();
    }

    /**
     * Confirms a teleport and echoes the resulting position.
     *
     * <p>The first confirmed teleport is the dependable signal that the server considers us
     * present: it arrives after the world is prepared, whereas entering the play state only means
     * configuration finished.
     */
    private void onTeleport(ClientboundPlayerPositionPacket packet) {
        List<PositionElement> relative = packet.getRelatives();

        x = relative.contains(PositionElement.X) ? x + packet.getPosition().getX() : packet.getPosition().getX();
        y = relative.contains(PositionElement.Y) ? y + packet.getPosition().getY() : packet.getPosition().getY();
        z = relative.contains(PositionElement.Z) ? z + packet.getPosition().getZ() : packet.getPosition().getZ();
        yaw = relative.contains(PositionElement.Y_ROT) ? yaw + packet.getYRot() : packet.getYRot();
        pitch = relative.contains(PositionElement.X_ROT) ? pitch + packet.getXRot() : packet.getXRot();

        session.send(new ServerboundAcceptTeleportationPacket(packet.getId()));
        session.send(new ServerboundMovePlayerPosRotPacket(true, false, x, y, z, yaw, pitch));

        if (!teleportConfirmed) {
            teleportConfirmed = true;
            log.debug("Confirmed initial teleport to {}, {}, {}",
                    Math.round(x), Math.round(y), Math.round(z));
        }
        evaluateReadiness();
    }

    /**
     * Tells the server how fast we can take chunks.
     *
     * <p>Not answering leaves the server's throttle at its starting value, so chunks trickle in and
     * a readiness condition that waits for them never completes. The rate is derived from how long
     * the batch actually took, the way a real client measures it.
     */
    private void onChunkBatchFinished(ClientboundChunkBatchFinishedPacket packet) {
        long elapsedNanos = Math.max(1L, System.nanoTime() - batchStartedAtNanos);
        float perTick = packet.getBatchSize() <= 0
                ? MAX_CHUNKS_PER_TICK
                : (float) (packet.getBatchSize() / (elapsedNanos / 1_000_000_000.0d) / 20.0d);
        float clamped = Math.clamp(perTick, MIN_CHUNKS_PER_TICK, MAX_CHUNKS_PER_TICK);

        session.send(new ServerboundChunkBatchReceivedPacket(clamped));
        log.trace("Acknowledged chunk batch of {}, requesting {} chunks/tick", packet.getBatchSize(), clamped);
    }

    private void onChatLine(ChatLine line) {
        events.onChat(line);
        if (readyPattern != null && !chatMatched && readyPattern.matcher(line.plainText()).find()) {
            chatMatched = true;
            log.debug("Readiness chat pattern matched: {}", line.plainText());
            evaluateReadiness();
        }
    }

    /**
     * Checks every configured readiness condition and, once they hold, starts ticking and completes
     * {@link #whenReady()} after the settle delay.
     */
    private void evaluateReadiness() {
        if (ready.isDone() || readyLatch.get()) {
            return;
        }
        ReadyWhenConfig when = settings.readyWhen();

        if (Boolean.TRUE.equals(when.spawn()) && !(gotJoinPacket && teleportConfirmed)) {
            return;
        }
        if (when.minChunks() != null && chunksReceived.get() < when.minChunks()) {
            return;
        }
        if (readyPattern != null && !chatMatched) {
            return;
        }
        // A pack still mid-sequence means the join is not finished. Only the initial readiness is
        // gated this way; a pack pushed later, while already in the world, does not undo it.
        if (packs.hasPending()) {
            return;
        }
        if (!readyLatch.compareAndSet(false, true)) {
            return;
        }

        // Vanilla reports that loading finished, then starts its normal tick cadence. Both begin
        // before the settle delay so the settle window looks like an ordinary idle client.
        session.send(ServerboundPlayerLoadedPacket.INSTANCE);
        startTicking();

        Duration settle = when.settle() == null ? Duration.ZERO : settings.jitter().apply(when.settle());
        scheduler.schedule(this::completeReady, Math.max(0L, settle.toMillis()), TimeUnit.MILLISECONDS);
    }

    private void completeReady() {
        if (ready.isDone()) {
            return;
        }
        cancel(readyTimeoutTask);
        setPhase(Phase.IN_WORLD);

        ReadyInfo info = new ReadyInfo(
                entityId,
                dimension,
                chunksReceived.get(),
                request.protocolVersion(),
                request.translated(),
                Duration.between(startedAt, Instant.now()));

        log.info("In the world at {} after {} ({} chunks)",
                request.target().address(),
                dev.chronit.core.util.Durations.format(info.timeToReady()),
                info.chunksReceived());

        ready.complete(info);
        events.onReady(info);
    }

    private void armReadyTimeout() {
        Duration timeout = settings.readyWhen().timeout();
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            return;
        }
        readyTimeoutTask = scheduler.schedule(() -> {
            if (ready.isDone()) {
                return;
            }
            String message = "Did not reach the world within "
                    + dev.chronit.core.util.Durations.format(timeout) + " (stopped at " + phase + ")";
            log.warn("{} — giving up on {}", message, request.target().address());
            ready.completeExceptionally(new java.util.concurrent.TimeoutException(message));
            closeReason = message;
            closing.set(true);
            session.disconnect(Component.text("Timed out joining"));
        }, timeout.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Runs the client's tick cadence for as long as we are connected.
     *
     * <p>Deliberately imitates a player standing still rather than wandering: the tick-end packet
     * every tick and a state report every second are exactly what a stationary vanilla client
     * sends. Fabricated movement would have to satisfy server-side physics checks to be an
     * improvement rather than a liability.
     */
    private void startTicking() {
        AtomicInteger tick = new AtomicInteger();
        tickTask = scheduler.scheduleAtFixedRate(() -> {
            if (!session.isConnected() || closing.get()) {
                return;
            }
            try {
                int current = tick.incrementAndGet();
                if (current % IDLE_STATUS_INTERVAL_TICKS == 0) {
                    session.send(new ServerboundMovePlayerStatusOnlyPacket(true, false));
                }
                session.send(ServerboundClientTickEndPacket.INSTANCE);
            } catch (RuntimeException e) {
                log.debug("Tick loop stopped: {}", e.toString());
                cancel(tickTask);
            }
        }, TICK_MILLIS, TICK_MILLIS, TimeUnit.MILLISECONDS);
    }

    private void setPhase(Phase next) {
        if (phase != next) {
            phase = next;
            events.onPhase(next);
        }
    }

    private void onDisconnected(DisconnectedEvent event) {
        cancel(tickTask);
        cancel(readyTimeoutTask);
        setPhase(Phase.CLOSED);

        String reason = Components.plain(event.getReason());
        DisconnectInfo info;
        if (closing.get()) {
            info = DisconnectInfo.clientClosed(closeReason != null ? closeReason : reason);
        } else if (event.getCause() != null) {
            // The library reports transport failures with a generic translation key and the real
            // exception attached, so the exception is the useful half.
            info = DisconnectInfo.fromCause(event.getCause());
        } else {
            info = DisconnectInfo.fromKick(reason, null);
        }

        if (!ready.isDone()) {
            ready.completeExceptionally(new java.io.IOException(
                    "Session ended before reaching the world — " + info.describe(), info.cause()));
        }
        closed.complete(info);
        events.onDisconnect(info);
    }

    private static void cancel(ScheduledFuture<?> task) {
        if (task != null) {
            task.cancel(false);
        }
    }

    // ---------------------------------------------------------------- mapping to wire enums

    private static ChatVisibility mapChatVisibility(ClientInformation.ChatVisibility visibility) {
        return switch (visibility) {
            case FULL -> ChatVisibility.FULL;
            case SYSTEM -> ChatVisibility.SYSTEM;
            case HIDDEN -> ChatVisibility.HIDDEN;
        };
    }

    private static ParticleStatus mapParticleStatus(ClientInformation.ParticleStatus status) {
        return switch (status) {
            case ALL -> ParticleStatus.ALL;
            case DECREASED -> ParticleStatus.DECREASED;
            case MINIMAL -> ParticleStatus.MINIMAL;
        };
    }

    private static List<SkinPart> mapSkinParts(List<ClientInformation.SkinPart> parts) {
        Set<SkinPart> mapped = EnumSet.noneOf(SkinPart.class);
        for (ClientInformation.SkinPart part : parts) {
            mapped.add(switch (part) {
                case CAPE -> SkinPart.CAPE;
                case JACKET -> SkinPart.JACKET;
                case LEFT_SLEEVE -> SkinPart.LEFT_SLEEVE;
                case RIGHT_SLEEVE -> SkinPart.RIGHT_SLEEVE;
                case LEFT_PANTS_LEG -> SkinPart.LEFT_PANTS_LEG;
                case RIGHT_PANTS_LEG -> SkinPart.RIGHT_PANTS_LEG;
                case HAT -> SkinPart.HAT;
            });
        }
        return List.copyOf(mapped);
    }

    // ---------------------------------------------------------------- packet dispatch

    /**
     * Registered after the library's own client listener, so by the time a packet reaches here the
     * library has already performed its part of the exchange — the protocol state has been switched
     * and any acknowledgement it owns has been sent.
     */
    private final class Listener extends SessionAdapter {

        @Override
        public void connected(ConnectedEvent event) {
            setPhase(Phase.LOGIN);
            log.debug("Connected to {}, logging in as {}",
                    request.target().address(), request.auth().username());
        }

        @Override
        public void packetReceived(Session session, Packet packet) {
            try {
                dispatch(packet);
            } catch (RuntimeException e) {
                // One malformed packet must not take down a session that is otherwise healthy.
                log.warn("Error handling {}: {}", packet.getClass().getSimpleName(), e.toString(), e);
            }
        }

        private void dispatch(Packet packet) {
            switch (packet) {
                // --- login -> configuration
                case ClientboundLoginFinishedPacket ignored -> {
                    setPhase(Phase.CONFIGURATION);
                    sendClientIdentity();
                }

                // --- configuration gates
                case ClientboundCodeOfConductPacket coc -> onCodeOfConduct(coc.getCodeOfConduct());
                case ClientboundResourcePackPushPacket push -> packs.onPush(
                        push.getId(), push.getUrl(), push.getHash(), push.isRequired(),
                        Components.plain(push.getPrompt()));
                // A null id means "drop every pack", which is why onPop tolerates one.
                case ClientboundResourcePackPopPacket pop -> packs.onPop(pop.getId());

                // --- common to configuration and play
                case ClientboundPingPacket ping -> session.send(new ServerboundPongPacket(ping.getId()));
                case ClientboundStoreCookiePacket store -> cookies.put(store.getKey(), store.getPayload());
                case ClientboundCookieRequestPacket cookie -> session.send(
                        new ServerboundCookieResponsePacket(cookie.getKey(), cookies.get(cookie.getKey())));
                case ClientboundCustomPayloadPacket ignored -> {
                    // Brand and plugin channels; nothing to answer, and answering unknown channels
                    // would be more conspicuous than staying quiet.
                }

                // --- play
                case ClientboundLoginPacket join -> onJoin(join);
                case ClientboundStartConfigurationPacket ignored -> setPhase(Phase.CONFIGURATION);
                case ClientboundPlayerPositionPacket position -> onTeleport(position);
                case ClientboundChunkBatchStartPacket ignored -> batchStartedAtNanos = System.nanoTime();
                case ClientboundChunkBatchFinishedPacket finished -> onChunkBatchFinished(finished);
                case ClientboundLevelChunkWithLightPacket ignored -> {
                    chunksReceived.incrementAndGet();
                    evaluateReadiness();
                }

                // --- chat
                case ClientboundSystemChatPacket system -> onChatLine(ChatLine.of(
                        system.isOverlay() ? ChatLine.Source.ACTION_BAR : ChatLine.Source.SYSTEM,
                        Components.plain(system.getContent()),
                        Components.json(system.getContent())));
                case ClientboundPlayerChatPacket player -> {
                    chat.onSignedMessageReceived();
                    String text = player.getUnsignedContent() != null
                            ? Components.plain(player.getUnsignedContent())
                            : player.getContent();
                    onChatLine(new ChatLine(ChatLine.Source.PLAYER, text,
                            Components.json(player.getUnsignedContent()), Instant.now()));
                }
                case ClientboundDisguisedChatPacket disguised -> onChatLine(ChatLine.of(
                        ChatLine.Source.DISGUISED,
                        Components.plain(disguised.getMessage()),
                        Components.json(disguised.getMessage())));

                default -> {
                    // Everything else — registries, tags, entity and world updates — is length
                    // prefixed and safely ignored. A check-in bot has no use for world state.
                }
            }
        }

        @Override
        public void disconnected(DisconnectedEvent event) {
            onDisconnected(event);
        }
    }

    /** Visible for the driver's logging. */
    UUID accountId() {
        return request.auth().uuid();
    }
}
