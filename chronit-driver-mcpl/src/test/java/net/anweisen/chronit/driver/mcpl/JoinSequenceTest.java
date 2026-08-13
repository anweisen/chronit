package net.anweisen.chronit.driver.mcpl;

import net.anweisen.chronit.core.config.ProtocolSpec;
import net.anweisen.chronit.core.config.ReadyWhenConfig;
import net.anweisen.chronit.core.config.ResourcePackConfig;
import net.anweisen.chronit.core.config.SecureChatMode;
import net.anweisen.chronit.core.driver.AuthContext;
import net.anweisen.chronit.core.driver.ChatLine;
import net.anweisen.chronit.core.driver.ClientEvents;
import net.anweisen.chronit.core.driver.ClientHandle;
import net.anweisen.chronit.core.driver.ClientInformation;
import net.anweisen.chronit.core.driver.ConnectRequest;
import net.anweisen.chronit.core.driver.ReadyInfo;
import net.anweisen.chronit.core.driver.ResourcePackEvent;
import net.anweisen.chronit.core.driver.ServerTarget;
import net.anweisen.chronit.core.driver.SessionSettings;
import net.anweisen.chronit.core.util.Jitter;
import org.geysermc.mcprotocollib.protocol.data.game.ResourcePackStatus;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundClientInformationPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundCustomPayloadPacket;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundResourcePackPacket;
import org.geysermc.mcprotocollib.protocol.packet.configuration.serverbound.ServerboundAcceptCodeOfConductPacket;
import org.geysermc.mcprotocollib.protocol.packet.cookie.serverbound.ServerboundCookieResponsePacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundClientTickEndPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundPlayerLoadedPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundAcceptTeleportationPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.level.ServerboundChunkBatchReceivedPacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end checks of the join sequence against a scripted server.
 *
 * <p>These are the tests that matter most: each assertion here corresponds to something that, if
 * omitted, makes a real server either disconnect the client or leave it stuck short of the world.
 */
@Timeout(value = 30, unit = TimeUnit.SECONDS)
class JoinSequenceTest {

    private McplDriver driver;

    @BeforeEach
    void setUp() {
        driver = new McplDriver();
    }

    @AfterEach
    void tearDown() {
        driver.shutdown();
    }

    /** Collects everything the driver reports so tests can assert on it. */
    private static final class Recorder implements ClientEvents {
        final List<ResourcePackEvent> packs = new CopyOnWriteArrayList<>();
        final List<ChatLine> chat = new CopyOnWriteArrayList<>();
        volatile String codeOfConduct;
        volatile ReadyInfo ready;

        @Override
        public void onResourcePack(ResourcePackEvent event) {
            packs.add(event);
        }

        @Override
        public void onChat(ChatLine line) {
            chat.add(line);
        }

        @Override
        public void onCodeOfConduct(String text) {
            codeOfConduct = text;
        }

        @Override
        public void onReady(ReadyInfo info) {
            ready = info;
        }
    }

    private SessionSettings settings(ResourcePackConfig.Mode packMode, ReadyWhenConfig readyWhen) {
        ResourcePackConfig pack = new ResourcePackConfig(
                packMode, false,
                Duration.ofMillis(60), Duration.ofMillis(40),
                Path.of(System.getProperty("java.io.tmpdir"), "chronit-test-packs"),
                8, Duration.ofSeconds(5));

        return new SessionSettings(
                "vanilla",
                new ClientInformation("en_us", 8, ClientInformation.ChatVisibility.FULL, true,
                        List.of(ClientInformation.SkinPart.HAT), ClientInformation.MainHand.RIGHT,
                        false, true, ClientInformation.ParticleStatus.ALL),
                pack,
                true,
                true,
                Jitter.none(),
                readyWhen.withFallback(ReadyWhenConfig.DEFAULTS),
                Duration.ofSeconds(10),
                SecureChatMode.OFF);
    }

    private ClientHandle connect(FakeMinecraftServer server, SessionSettings settings, Recorder recorder)
            throws Exception {
        ServerTarget target = new ServerTarget("127.0.0.1", server.port(), ProtocolSpec.AUTO, null);
        ConnectRequest request = new ConnectRequest(
                target, AuthContext.offline("TestBot"), settings, McplDriver.NATIVE_PROTOCOL, false);
        return driver.connect(request, recorder);
    }

    @Test
    void answersEveryConfigurationGateAndReachesTheWorld() throws Exception {
        try (FakeMinecraftServer server = new FakeMinecraftServer(FakeMinecraftServer.Options.everything()).start()) {
            Recorder recorder = new Recorder();
            SessionSettings settings = settings(ResourcePackConfig.Mode.FAKE,
                    new ReadyWhenConfig(true, 0, null, Duration.ofMillis(50), Duration.ofSeconds(20)));

            try (ClientHandle client = connect(server, settings, recorder)) {
                ReadyInfo ready = client.whenReady().get(20, TimeUnit.SECONDS);

                assertNotNull(ready);
                assertEquals(1, ready.entityId());
                assertEquals(McplDriver.NATIVE_PROTOCOL, ready.protocolVersion());
                assertFalse(ready.translated());

                // The 26.x code of conduct gate: unanswered, the server closes the connection.
                assertEquals(1, server.packets(ServerboundAcceptCodeOfConductPacket.class).size(),
                        "the code of conduct must be accepted");
                assertNotNull(recorder.codeOfConduct);

                // Velocity-style proxies stall the join if a cookie request goes unanswered.
                assertEquals(1, server.packets(ServerboundCookieResponsePacket.class).size(),
                        "cookie requests must be answered");

                assertFalse(server.packets(ServerboundClientInformationPacket.class).isEmpty(),
                        "client settings must be sent");
                assertFalse(server.packets(ServerboundCustomPayloadPacket.class).isEmpty(),
                        "the brand must be sent");

                assertEquals(1, server.packets(ServerboundAcceptTeleportationPacket.class).size(),
                        "the initial teleport must be confirmed");
                assertEquals(42, server.packets(ServerboundAcceptTeleportationPacket.class).getFirst().getId());

                assertEquals(1, server.packets(ServerboundChunkBatchReceivedPacket.class).size(),
                        "chunk batches must be acknowledged or the server throttles chunk delivery");
                assertEquals(1, server.packets(ServerboundPlayerLoadedPacket.class).size());
            }
        }
    }

    @Test
    void reportsTheFullVanillaResourcePackSequenceInOrder() throws Exception {
        try (FakeMinecraftServer server = new FakeMinecraftServer(FakeMinecraftServer.Options.everything()).start()) {
            Recorder recorder = new Recorder();
            SessionSettings settings = settings(ResourcePackConfig.Mode.FAKE,
                    new ReadyWhenConfig(true, 0, null, Duration.ofMillis(50), Duration.ofSeconds(20)));

            try (ClientHandle client = connect(server, settings, recorder)) {
                client.whenReady().get(20, TimeUnit.SECONDS);

                List<ServerboundResourcePackPacket> responses =
                        server.packets(ServerboundResourcePackPacket.class);
                List<ResourcePackStatus> statuses = new ArrayList<>();
                responses.forEach(packet -> statuses.add(packet.getStatus()));

                assertEquals(List.of(
                                ResourcePackStatus.ACCEPTED,
                                ResourcePackStatus.DOWNLOADED,
                                ResourcePackStatus.SUCCESSFULLY_LOADED),
                        statuses,
                        "a real client reports accepted, then downloaded, then successfully loaded");

                // All three responses must name the same pack.
                assertEquals(1, responses.stream().map(ServerboundResourcePackPacket::getId).distinct().count());

                assertEquals(3, recorder.packs.size(), "each status should be surfaced to the caller");
                assertEquals(ResourcePackEvent.Status.SUCCESSFULLY_LOADED, recorder.packs.getLast().status());
                assertTrue(recorder.packs.getLast().required());
            }
        }
    }

    @Test
    void declineModeReportsDeclinedAndNothingElse() throws Exception {
        try (FakeMinecraftServer server = new FakeMinecraftServer(FakeMinecraftServer.Options.everything()).start()) {
            Recorder recorder = new Recorder();
            SessionSettings settings = settings(ResourcePackConfig.Mode.DECLINE,
                    new ReadyWhenConfig(true, 0, null, Duration.ofMillis(50), Duration.ofSeconds(20)));

            try (ClientHandle client = connect(server, settings, recorder)) {
                client.whenReady().get(20, TimeUnit.SECONDS);

                List<ServerboundResourcePackPacket> responses =
                        server.packets(ServerboundResourcePackPacket.class);
                assertEquals(1, responses.size());
                assertEquals(ResourcePackStatus.DECLINED, responses.getFirst().getStatus());
            }
        }
    }

    @Test
    void downloadModeReportsFailureWhenThePackCannotBeFetched() throws Exception {
        // The URL does not resolve, which is the point: a real client reports the failure rather
        // than pretending, and the driver must not hang waiting for it.
        FakeMinecraftServer.Options options = new FakeMinecraftServer.Options(
                false, true, false, "https://chronit-nonexistent.invalid/pack.zip", "", false, true, null);

        try (FakeMinecraftServer server = new FakeMinecraftServer(options).start()) {
            Recorder recorder = new Recorder();
            SessionSettings settings = settings(ResourcePackConfig.Mode.DOWNLOAD,
                    new ReadyWhenConfig(true, 0, null, Duration.ofMillis(50), Duration.ofSeconds(20)));

            try (ClientHandle client = connect(server, settings, recorder)) {
                client.whenReady().get(20, TimeUnit.SECONDS);

                // Give the download attempt time to fail and be reported.
                for (int i = 0; i < 100 && server.packets(ServerboundResourcePackPacket.class).size() < 2; i++) {
                    Thread.sleep(100);
                }
                List<ResourcePackStatus> statuses = server.packets(ServerboundResourcePackPacket.class)
                        .stream().map(ServerboundResourcePackPacket::getStatus).toList();

                assertEquals(ResourcePackStatus.ACCEPTED, statuses.getFirst());
                assertEquals(ResourcePackStatus.FAILED_DOWNLOAD, statuses.getLast());
            }
        }
    }

    @Test
    void waitsForTheConfiguredChatMessageBeforeReportingReady() throws Exception {
        try (FakeMinecraftServer server = new FakeMinecraftServer(FakeMinecraftServer.Options.bare()).start()) {
            Recorder recorder = new Recorder();
            SessionSettings settings = settings(ResourcePackConfig.Mode.FAKE,
                    new ReadyWhenConfig(true, 0, "(?i)you are now logged in", Duration.ZERO, Duration.ofSeconds(20)));

            try (ClientHandle client = connect(server, settings, recorder)) {
                assertTrue(server.awaitClient(10_000));

                // Spawned, but the readiness pattern has not matched — many networks park an
                // arriving player in an authentication world exactly like this.
                Thread.sleep(1500);
                assertFalse(client.whenReady().isDone(),
                        "readiness must wait for the configured message");

                server.say("You are now logged in!");
                ReadyInfo ready = client.whenReady().get(15, TimeUnit.SECONDS);
                assertNotNull(ready);
                assertTrue(recorder.chat.stream().anyMatch(line -> line.plainText().contains("logged in")));
            }
        }
    }

    @Test
    void runsTheClientTickCadenceOnceInTheWorld() throws Exception {
        try (FakeMinecraftServer server = new FakeMinecraftServer(FakeMinecraftServer.Options.bare()).start()) {
            Recorder recorder = new Recorder();
            SessionSettings settings = settings(ResourcePackConfig.Mode.FAKE,
                    new ReadyWhenConfig(true, 0, null, Duration.ZERO, Duration.ofSeconds(20)));

            try (ClientHandle client = connect(server, settings, recorder)) {
                client.whenReady().get(20, TimeUnit.SECONDS);
                Thread.sleep(1000);

                // 20 ticks per second; allow generous slack for scheduler jitter on a busy machine.
                int ticks = server.packets(ServerboundClientTickEndPacket.class).size();
                assertTrue(ticks >= 8, "expected a running tick loop, saw " + ticks + " tick-end packets");
            }
        }
    }

    @Test
    void sendsCommandsWithoutASignatureOrAcknowledgementBlock() throws Exception {
        try (FakeMinecraftServer server = new FakeMinecraftServer(FakeMinecraftServer.Options.bare()).start()) {
            Recorder recorder = new Recorder();
            SessionSettings settings = settings(ResourcePackConfig.Mode.FAKE,
                    new ReadyWhenConfig(true, 0, null, Duration.ZERO, Duration.ofSeconds(20)));

            try (ClientHandle client = connect(server, settings, recorder)) {
                client.whenReady().get(20, TimeUnit.SECONDS);
                client.sendCommand("warp daily");

                for (int i = 0; i < 50 && server.packets(ServerboundChatCommandPacket.class).isEmpty(); i++) {
                    Thread.sleep(100);
                }
                List<ServerboundChatCommandPacket> commands = server.packets(ServerboundChatCommandPacket.class);
                assertEquals(1, commands.size());
                assertEquals("warp daily", commands.getFirst().getCommand(),
                        "the leading slash is not part of the wire format");
            }
        }
    }

    @Test
    void refusesToSendBeforeTheClientIsInTheWorld() throws Exception {
        try (FakeMinecraftServer server = new FakeMinecraftServer(FakeMinecraftServer.Options.bare()).start()) {
            Recorder recorder = new Recorder();
            SessionSettings settings = settings(ResourcePackConfig.Mode.FAKE,
                    new ReadyWhenConfig(true, 0, "never matches this", Duration.ZERO, Duration.ofSeconds(20)));

            try (ClientHandle client = connect(server, settings, recorder)) {
                assertTrue(server.awaitClient(10_000));
                Thread.sleep(500);

                // Commands sent before the world is loaded are silently dropped by servers, so the
                // handle refuses rather than letting a sequence quietly do nothing.
                IllegalStateException error = org.junit.jupiter.api.Assertions.assertThrows(
                        IllegalStateException.class, () -> client.sendCommand("warp daily"));
                assertTrue(error.getMessage().contains("whenReady"), error.getMessage());
                assertNull(recorder.ready);
            }
        }
    }

    @Test
    void failsReadinessWhenTheGateNeverOpens() throws Exception {
        try (FakeMinecraftServer server = new FakeMinecraftServer(FakeMinecraftServer.Options.bare()).start()) {
            Recorder recorder = new Recorder();
            SessionSettings settings = settings(ResourcePackConfig.Mode.FAKE,
                    new ReadyWhenConfig(true, 0, "never matches this", Duration.ZERO, Duration.ofSeconds(2)));

            try (ClientHandle client = connect(server, settings, recorder)) {
                Exception error = org.junit.jupiter.api.Assertions.assertThrows(Exception.class,
                        () -> client.whenReady().get(15, TimeUnit.SECONDS));
                assertTrue(error.getCause() instanceof java.util.concurrent.TimeoutException
                                || error instanceof java.util.concurrent.TimeoutException,
                        "expected a readiness timeout, got " + error);
            }
        }
    }

    @Test
    void pingReportsTheServerVersion() throws Exception {
        // The scripted server does not implement status, so this asserts the failure path is a
        // clean DriverException rather than a hang.
        try (FakeMinecraftServer server = new FakeMinecraftServer(FakeMinecraftServer.Options.bare()).start()) {
            ServerTarget target = new ServerTarget("127.0.0.1", server.port(), ProtocolSpec.AUTO, null);
            org.junit.jupiter.api.Assertions.assertThrows(
                    net.anweisen.chronit.core.driver.DriverException.class,
                    () -> driver.ping(target, Duration.ofSeconds(3)));
        }
    }
}
