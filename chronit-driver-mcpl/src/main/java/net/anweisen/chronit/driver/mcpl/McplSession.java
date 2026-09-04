package net.anweisen.chronit.driver.mcpl;

import net.anweisen.chronit.core.config.ReadyWhenConfig;
import net.anweisen.chronit.core.driver.ChatLine;
import net.anweisen.chronit.core.driver.ClientEvents;
import net.anweisen.chronit.core.driver.ClientHandle;
import net.anweisen.chronit.core.driver.ClientInformation;
import net.anweisen.chronit.core.driver.ConnectRequest;
import net.anweisen.chronit.core.driver.ContainerInfo;
import net.anweisen.chronit.core.driver.DisconnectInfo;
import net.anweisen.chronit.core.driver.Phase;
import net.anweisen.chronit.core.driver.ReadyInfo;
import net.anweisen.chronit.core.driver.ServerTarget;
import net.anweisen.chronit.core.driver.SessionSettings;
import net.anweisen.chronit.core.driver.SlotClick;
import net.anweisen.chronit.core.util.Durations;
import net.kyori.adventure.key.Key;
import net.kyori.adventure.text.Component;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.network.event.session.ConnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.DisconnectedEvent;
import org.geysermc.mcprotocollib.network.event.session.SessionAdapter;
import org.geysermc.mcprotocollib.network.packet.Packet;
import org.geysermc.mcprotocollib.network.session.ClientNetworkSession;
import org.geysermc.mcprotocollib.protocol.data.game.entity.player.PositionElement;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.DropItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ShiftClickItemAction;
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
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerClosePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetContentPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundContainerSetSlotPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.inventory.ClientboundOpenScreenPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.entity.player.ClientboundPlayerPositionPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundChunkBatchFinishedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundChunkBatchStartPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.clientbound.level.ClientboundLevelChunkWithLightPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundClientTickEndPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundPlayerLoadedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClickPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClosePacket;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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

    /** Container id 0 is the player's own inventory, which is never an "opened" window. */
    private static final int PLAYER_INVENTORY_CONTAINER_ID = 0;

    /**
     * How long a click will wait for a freshly opened window to be populated. A server sends the
     * open packet and the contents in quick succession; clicking in between does nothing.
     */
    private static final Duration CONTENTS_GRACE = Duration.ofSeconds(1);

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

    /**
     * The container the server has opened, or null. Replaced wholesale rather than mutated so a
     * reader always sees a consistent snapshot.
     */
    private final AtomicReference<OpenContainer> container =
            new AtomicReference<>();

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
    public Optional<ContainerInfo> openContainer() {
        return Optional.ofNullable(container.get()).map(OpenContainer::toInfo);
    }

    @Override
    public void clickSlot(SlotClick click) {
        requireInWorld("inventory click");

        OpenContainer open = awaitContents();
        if (open == null) {
            throw new IllegalStateException("No container is open — a click needs a menu, so send "
                    + "the command that opens it first and wait for it with waitFor.screen");
        }

        int slot = resolveSlot(open, click);
        ContainerActionType action = actionTypeFor(click.mode());

        session.send(new ServerboundContainerClickPacket(
                open.containerId(),
                // Echoing the last state id the server sent is how it detects a desynchronised
                // client. Getting it wrong costs a resync, not a rejected click.
                open.stateId(),
                slot,
                action,
                actionParamFor(click),
                // The remaining two fields are the client's *prediction* of the result: what ends
                // up on the cursor, and which slots change. Sending nothing predicted leaves the
                // server authoritative — it applies the click and resyncs if its own outcome
                // differs. For a plugin menu, which cancels the event and repaints anyway, that is
                // both correct and the only honest option: a real prediction would mean hashing
                // item data components, and a wrong hash is worse than no claim at all.
                null,
                Map.of()));

        log.debug("Clicked slot {} ({}) in container {} at state {}",
                slot, click.describe(), open.containerId(), open.stateId());
    }

    @Override
    public void closeScreen() {
        OpenContainer open = container.getAndSet(null);
        if (open == null) {
            return;
        }
        if (session.isConnected()) {
            session.send(new ServerboundContainerClosePacket(open.containerId()));
        }
        log.debug("Closed container {}", open.containerId());
        events.onScreenClose(open.containerId());
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
            // A real client closes whatever window it had open before quitting, and a server that
            // still thinks we are in a menu can hold the session open or refuse the next join.
            closeScreen();
            // Acknowledge anything outstanding before going, as a real client would on quit.
            chat.flushAcknowledgements();
            session.disconnect(Component.text(reason == null ? "Disconnecting" : reason));
        }
    }

    @Override
    public void close() {
        disconnect(closeReason != null ? closeReason : "Session closed");
    }

    // ---------------------------------------------------------------- containers

    /** A container the server has opened for us. */
    private record OpenContainer(int containerId, String type, String title,
                                 int stateId, int containerSlots, boolean contentsReceived) {

        ContainerInfo toInfo() {
            return new ContainerInfo(containerId, type, title, containerSlots, contentsReceived);
        }
    }

    private void onOpenScreen(ClientboundOpenScreenPacket packet) {
        OpenContainer open = new OpenContainer(
                packet.getContainerId(),
                String.valueOf(packet.getType()),
                Components.plain(packet.getTitle()),
                0,
                -1,
                false);
        container.set(open);
        log.info("Server opened menu {}", open.toInfo().describe());
        events.onScreen(open.toInfo());
    }

    /**
     * Records the contents of the open window.
     *
     * <p>The slot count is what makes addressing possible: the window numbers its own slots first
     * and the player's inventory after them, so the boundary is the total minus the 36 slots a
     * player always has.
     */
    private void onContainerContents(ClientboundContainerSetContentPacket packet) {
        if (packet.getContainerId() == PLAYER_INVENTORY_CONTAINER_ID) {
            return;
        }
        OpenContainer current = container.get();
        if (current == null || current.containerId() != packet.getContainerId()) {
            return;
        }

        int containerSlots = Math.max(0, packet.getItems().length - ContainerInfo.PLAYER_INVENTORY_SLOTS);
        OpenContainer updated = new OpenContainer(current.containerId(), current.type(), current.title(),
                packet.getStateId(), containerSlots, true);
        container.set(updated);

        log.debug("Menu {} populated: {} container slot(s), state {}",
                updated.containerId(), containerSlots, packet.getStateId());
        events.onScreen(updated.toInfo());
    }

    private void onContainerSlot(ClientboundContainerSetSlotPacket packet) {
        OpenContainer current = container.get();
        if (current == null || current.containerId() != packet.getContainerId()) {
            return;
        }
        // Single-slot updates carry the newest state id, which the next click has to echo.
        container.set(new OpenContainer(current.containerId(), current.type(), current.title(),
                packet.getStateId(), current.containerSlots(), current.contentsReceived()));
    }

    private void onContainerClosed(int containerId) {
        OpenContainer current = container.get();
        if (current != null && current.containerId() == containerId) {
            container.set(null);
            log.debug("Server closed menu {}", containerId);
            events.onScreenClose(containerId);
        }
    }

    /**
     * Returns the open container once its contents have arrived, waiting briefly if they have not.
     *
     * <p>Called from the action runner's thread rather than a network thread, so a short block is
     * safe.
     */
    private OpenContainer awaitContents() {
        long deadline = System.nanoTime() + CONTENTS_GRACE.toNanos();
        OpenContainer open = container.get();
        while (open != null && !open.contentsReceived() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(20);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return open;
            }
            open = container.get();
        }
        if (open != null && !open.contentsReceived()) {
            log.warn("Menu {} has not sent its contents after {}; clicking anyway",
                    open.containerId(), Durations.format(CONTENTS_GRACE));
        }
        return open;
    }

    /** Maps a slot within one half of the window onto the window's single continuous range. */
    private static int resolveSlot(OpenContainer open, SlotClick click) {
        return switch (click.part()) {
            case CONTAINER -> {
                if (open.containerSlots() >= 0 && click.slot() >= open.containerSlots()) {
                    throw new IllegalStateException("Slot " + click.slot() + " is outside this menu, "
                            + "which has " + open.containerSlots() + " slot(s) (0-"
                            + (open.containerSlots() - 1) + ")");
                }
                yield click.slot();
            }
            case PLAYER -> {
                if (open.containerSlots() < 0) {
                    throw new IllegalStateException("Cannot address a player inventory slot until the "
                            + "menu reports its contents — its size is what says where the player "
                            + "inventory begins");
                }
                yield open.containerSlots() + click.slot();
            }
        };
    }

    private static ContainerActionType actionTypeFor(SlotClick.ClickMode mode) {
        return switch (mode) {
            case PICKUP -> ContainerActionType.CLICK_ITEM;
            case SHIFT -> ContainerActionType.SHIFT_CLICK_ITEM;
            case DROP -> ContainerActionType.DROP_ITEM;
        };
    }

    private static ContainerAction actionParamFor(SlotClick click) {
        boolean left = click.button() == SlotClick.ClickButton.LEFT;
        return switch (click.mode()) {
            case PICKUP -> left ? ClickItemAction.LEFT_CLICK : ClickItemAction.RIGHT_CLICK;
            case SHIFT -> left ? ShiftClickItemAction.LEFT_CLICK : ShiftClickItemAction.RIGHT_CLICK;
            // Left drops one item, right drops the whole stack — matching the vanilla bindings.
            case DROP -> left ? DropItemAction.DROP_FROM_SELECTED : DropItemAction.DROP_SELECTED_STACK;
        };
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
                Durations.format(info.timeToReady()),
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
                    + Durations.format(timeout) + " (stopped at " + phase + ")";
            log.warn("{} — giving up on {}", message, request.target().address());
            ready.completeExceptionally(new TimeoutException(message));
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

                // --- containers
                case ClientboundOpenScreenPacket open -> onOpenScreen(open);
                case ClientboundContainerSetContentPacket contents -> onContainerContents(contents);
                case ClientboundContainerSetSlotPacket slot -> onContainerSlot(slot);
                case ClientboundContainerClosePacket closed -> onContainerClosed(closed.getContainerId());

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
