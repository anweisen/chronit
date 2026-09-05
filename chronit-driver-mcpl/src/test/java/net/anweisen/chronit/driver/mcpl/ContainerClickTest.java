package net.anweisen.chronit.driver.mcpl;

import net.anweisen.chronit.core.config.ProtocolSpec;
import net.anweisen.chronit.core.config.ReadyWhenConfig;
import net.anweisen.chronit.core.config.ResourcePackConfig;
import net.anweisen.chronit.core.config.SecureChatMode;
import net.anweisen.chronit.core.driver.AuthContext;
import net.anweisen.chronit.core.driver.ClientEvents;
import net.anweisen.chronit.core.driver.ClientHandle;
import net.anweisen.chronit.core.driver.ClientInformation;
import net.anweisen.chronit.core.driver.ConnectRequest;
import net.anweisen.chronit.core.driver.ContainerInfo;
import net.anweisen.chronit.core.driver.ServerTarget;
import net.anweisen.chronit.core.driver.SessionSettings;
import net.anweisen.chronit.core.driver.SlotClick;
import net.anweisen.chronit.core.util.Jitter;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ClickItemAction;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ContainerActionType;
import org.geysermc.mcprotocollib.protocol.data.game.inventory.ShiftClickItemAction;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClickPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClosePacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Clicking slots in a server-opened menu.
 *
 * <p>The menu is the interesting part: plugin menus are how a lot of daily-reward and shop
 * interactions work, and a click that names the wrong slot or echoes a stale state id either does
 * nothing or gets the window resynced out from under it.
 */
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class ContainerClickTest {

  private static final int MENU_ID = 3;
  private static final int MENU_SLOTS = 27;

  private McplDriver driver;

  @BeforeEach
  void setUp() {
    driver = new McplDriver();
  }

  @AfterEach
  void tearDown() {
    driver.shutdown();
  }

  private static final class Recorder implements ClientEvents {
    final List<ContainerInfo> screens = new CopyOnWriteArrayList<>();
    final List<Integer> closed = new CopyOnWriteArrayList<>();

    @Override
    public void onScreen(ContainerInfo info) {
      screens.add(info);
    }

    @Override
    public void onScreenClose(int containerId) {
      closed.add(containerId);
    }
  }

  private SessionSettings settings() {
    return new SessionSettings(
        "vanilla",
        new ClientInformation("en_us", 8, ClientInformation.ChatVisibility.FULL, true,
            List.of(ClientInformation.SkinPart.HAT), ClientInformation.MainHand.RIGHT,
            false, true, ClientInformation.ParticleStatus.ALL),
        new ResourcePackConfig(ResourcePackConfig.Mode.FAKE, false,
            Duration.ofMillis(20), Duration.ofMillis(10),
            Path.of(System.getProperty("java.io.tmpdir"), "chronit-test-packs"),
            8, Duration.ofSeconds(5)),
        true, true, Jitter.none(),
        new ReadyWhenConfig(true, 0, null, Duration.ZERO, Duration.ofSeconds(20))
            .withFallback(ReadyWhenConfig.DEFAULTS),
        Duration.ofSeconds(10),
        SecureChatMode.OFF);
  }

  private ClientHandle connect(FakeMinecraftServer server, Recorder recorder) throws Exception {
    ServerTarget target = new ServerTarget("127.0.0.1", server.port(), ProtocolSpec.AUTO, null);
    return driver.connect(new ConnectRequest(
        target, AuthContext.offline("TestBot"), settings(),
        McplDriver.NATIVE_PROTOCOL, false), recorder);
  }

  /** Waits for the client to send a packet of the given type, then returns them all. */
  private static <T extends org.geysermc.mcprotocollib.network.packet.Packet> List<T> awaitPackets(
      FakeMinecraftServer server, Class<T> type, int count) throws InterruptedException {
    for (int i = 0; i < 100 && server.packets(type).size() < count; i++) {
      Thread.sleep(50);
    }
    return server.packets(type);
  }

  @Test
  void clicksTheRequestedContainerSlotAndEchoesTheStateId() throws Exception {
    try (FakeMinecraftServer server = new FakeMinecraftServer(FakeMinecraftServer.Options.bare()).start()) {
      Recorder recorder = new Recorder();
      try (ClientHandle client = connect(server, recorder)) {
        client.whenReady().get(20, TimeUnit.SECONDS);

        int stateId = server.openMenu(MENU_ID, "Daily Rewards", MENU_SLOTS, 7);
        awaitScreen(client);

        client.clickSlot(SlotClick.container(13));

        List<ServerboundContainerClickPacket> clicks =
            awaitPackets(server, ServerboundContainerClickPacket.class, 1);
        assertEquals(1, clicks.size());

        ServerboundContainerClickPacket click = clicks.getFirst();
        assertEquals(MENU_ID, click.getContainerId(), "must click the window the server opened");
        assertEquals(13, click.getSlot(), "container slots are numbered from 0");
        assertEquals(stateId, click.getStateId(),
            "the last state id the server sent must be echoed, or the server resyncs");
        assertEquals(ContainerActionType.CLICK_ITEM, click.getAction());
        assertEquals(ClickItemAction.LEFT_CLICK, click.getParam());

        // We deliberately claim no prediction, leaving the server authoritative.
        assertTrue(click.getChangedSlots().isEmpty());
        assertEquals(null, click.getCarriedItem());
      }
    }
  }

  @Test
  void playerInventorySlotsAreOffsetPastTheMenu() throws Exception {
    try (FakeMinecraftServer server = new FakeMinecraftServer(FakeMinecraftServer.Options.bare()).start()) {
      Recorder recorder = new Recorder();
      try (ClientHandle client = connect(server, recorder)) {
        client.whenReady().get(20, TimeUnit.SECONDS);
        server.openMenu(MENU_ID, "Storage", MENU_SLOTS, 1);
        awaitScreen(client);

        // Hotbar slot 0 is player slot 27, which sits after the menu's own 27 slots.
        client.clickSlot(new SlotClick(SlotClick.InventoryPart.PLAYER, 27,
            SlotClick.ClickButton.LEFT, SlotClick.ClickMode.PICKUP));

        List<ServerboundContainerClickPacket> clicks =
            awaitPackets(server, ServerboundContainerClickPacket.class, 1);
        assertEquals(MENU_SLOTS + 27, clicks.getFirst().getSlot());
      }
    }
  }

  @Test
  void mapsButtonsAndModesOntoTheWireActions() throws Exception {
    try (FakeMinecraftServer server = new FakeMinecraftServer(FakeMinecraftServer.Options.bare()).start()) {
      Recorder recorder = new Recorder();
      try (ClientHandle client = connect(server, recorder)) {
        client.whenReady().get(20, TimeUnit.SECONDS);
        server.openMenu(MENU_ID, "Shop", MENU_SLOTS, 1);
        awaitScreen(client);

        client.clickSlot(new SlotClick(SlotClick.InventoryPart.CONTAINER, 0,
            SlotClick.ClickButton.RIGHT, SlotClick.ClickMode.PICKUP));
        client.clickSlot(new SlotClick(SlotClick.InventoryPart.CONTAINER, 1,
            SlotClick.ClickButton.LEFT, SlotClick.ClickMode.SHIFT));

        List<ServerboundContainerClickPacket> clicks =
            awaitPackets(server, ServerboundContainerClickPacket.class, 2);
        assertEquals(ContainerActionType.CLICK_ITEM, clicks.get(0).getAction());
        assertEquals(ClickItemAction.RIGHT_CLICK, clicks.get(0).getParam());
        assertEquals(ContainerActionType.SHIFT_CLICK_ITEM, clicks.get(1).getAction());
        assertEquals(ShiftClickItemAction.LEFT_CLICK, clicks.get(1).getParam());
      }
    }
  }

  @Test
  void reportsTheMenuOnlyAsReadyOnceItsContentsArrive() throws Exception {
    try (FakeMinecraftServer server = new FakeMinecraftServer(FakeMinecraftServer.Options.bare()).start()) {
      Recorder recorder = new Recorder();
      try (ClientHandle client = connect(server, recorder)) {
        client.whenReady().get(20, TimeUnit.SECONDS);
        server.openMenu(MENU_ID, "Daily Rewards", MENU_SLOTS, 4);
        awaitScreen(client);

        // Opened, then populated — two reports, and only the second can be clicked against.
        assertTrue(recorder.screens.size() >= 2,
            "expected an open and a populated report, saw " + recorder.screens.size());
        assertFalse(recorder.screens.getFirst().contentsReceived());
        assertFalse(recorder.screens.getFirst().knowsLayout());

        ContainerInfo populated = recorder.screens.getLast();
        assertTrue(populated.contentsReceived());
        assertEquals(MENU_SLOTS, populated.containerSlots(),
            "menu size is the total minus the player's own 36 slots");
        assertEquals("Daily Rewards", populated.title());
        assertEquals(MENU_ID, populated.containerId());
      }
    }
  }

  @Test
  void closingSendsTheClosePacketAndForgetsTheMenu() throws Exception {
    try (FakeMinecraftServer server = new FakeMinecraftServer(FakeMinecraftServer.Options.bare()).start()) {
      Recorder recorder = new Recorder();
      try (ClientHandle client = connect(server, recorder)) {
        client.whenReady().get(20, TimeUnit.SECONDS);
        server.openMenu(MENU_ID, "Shop", MENU_SLOTS, 1);
        awaitScreen(client);

        client.closeScreen();

        List<ServerboundContainerClosePacket> closes =
            awaitPackets(server, ServerboundContainerClosePacket.class, 1);
        assertEquals(MENU_ID, closes.getFirst().getContainerId());
        assertTrue(client.openContainer().isEmpty());
        assertTrue(recorder.closed.contains(MENU_ID));

        // Closing again is a no-op rather than a second packet.
        client.closeScreen();
        Thread.sleep(200);
        assertEquals(1, server.packets(ServerboundContainerClosePacket.class).size());
      }
    }
  }

  @Test
  void aServerClosingTheMenuIsNoticed() throws Exception {
    try (FakeMinecraftServer server = new FakeMinecraftServer(FakeMinecraftServer.Options.bare()).start()) {
      Recorder recorder = new Recorder();
      try (ClientHandle client = connect(server, recorder)) {
        client.whenReady().get(20, TimeUnit.SECONDS);
        server.openMenu(MENU_ID, "Shop", MENU_SLOTS, 1);
        awaitScreen(client);

        server.closeMenu(MENU_ID);
        for (int i = 0; i < 60 && client.openContainer().isPresent(); i++) {
          Thread.sleep(50);
        }
        assertTrue(client.openContainer().isEmpty(),
            "a server-side close must clear our idea of the open menu");
      }
    }
  }

  @Test
  void clickingWithNoMenuOpenSaysSoClearly() throws Exception {
    try (FakeMinecraftServer server = new FakeMinecraftServer(FakeMinecraftServer.Options.bare()).start()) {
      Recorder recorder = new Recorder();
      try (ClientHandle client = connect(server, recorder)) {
        client.whenReady().get(20, TimeUnit.SECONDS);

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> client.clickSlot(SlotClick.container(0)));
        assertTrue(error.getMessage().contains("waitFor.screen"),
            "the message should point at the fix: " + error.getMessage());
      }
    }
  }

  @Test
  void refusesASlotOutsideTheMenu() throws Exception {
    try (FakeMinecraftServer server = new FakeMinecraftServer(FakeMinecraftServer.Options.bare()).start()) {
      Recorder recorder = new Recorder();
      try (ClientHandle client = connect(server, recorder)) {
        client.whenReady().get(20, TimeUnit.SECONDS);
        server.openMenu(MENU_ID, "Small", 9, 1);
        awaitScreen(client);

        IllegalStateException error = assertThrows(IllegalStateException.class,
            () -> client.clickSlot(SlotClick.container(20)));
        assertTrue(error.getMessage().contains("9 slot"), error.getMessage());
        assertTrue(server.packets(ServerboundContainerClickPacket.class).isEmpty(),
            "an out-of-range slot must not reach the server");
      }
    }
  }

  /** Blocks until the client has both seen the window and been told its contents. */
  private static void awaitScreen(ClientHandle client) throws InterruptedException {
    for (int i = 0; i < 100; i++) {
      if (client.openContainer().filter(ContainerInfo::contentsReceived).isPresent()) {
        return;
      }
      Thread.sleep(50);
    }
    throw new AssertionError("The menu never became available to the client");
  }
}
