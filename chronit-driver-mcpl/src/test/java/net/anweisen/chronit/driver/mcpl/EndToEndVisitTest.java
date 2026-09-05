package net.anweisen.chronit.driver.mcpl;

import net.anweisen.chronit.core.auth.AccountManager;
import net.anweisen.chronit.core.auth.TokenStore;
import net.anweisen.chronit.core.config.ChronitConfig;
import net.anweisen.chronit.core.config.ConfigLoader;
import net.anweisen.chronit.core.run.Orchestrator;
import net.anweisen.chronit.core.state.RunRecord;
import net.anweisen.chronit.core.util.Redactor;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClickPacket;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.inventory.ServerboundContainerClosePacket;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Exercises the whole stack — configuration, orchestration, command sequencing, the protocol
 * driver — against the scripted server.
 *
 * <p>Uses an offline account, so nothing here needs credentials or network access.
 */
@Timeout(value = 90, unit = TimeUnit.SECONDS)
class EndToEndVisitTest {

  @AfterEach
  void clearSecrets() {
    Redactor.clear();
  }

  /** Escapes a filesystem path for embedding in a double-quoted YAML scalar. */
  private static String yamlPath(Path path) {
    return path.toString().replace("\\", "\\\\");
  }

  @Test
  void runsAConfiguredVisitFromEndToEnd(@TempDir Path stateDir) throws Exception {
    try (FakeMinecraftServer server =
                 new FakeMinecraftServer(FakeMinecraftServer.Options.everything()).start()) {

      // The password arrives the way a container would supply it, which also proves the
      // secret is substituted into the command and masked in output.
      ChronitConfig config = new ConfigLoader(Map.of("CHRONIT_SECRET_PASSWORD", "s3cret-password"))
          .loadString("""
                  stateDir: "%s"
                  defaults:
                    jitter: 0
                    resourcePack:
                      mode: FAKE
                      downloadDelay: 50ms
                      applyDelay: 30ms
                    readyWhen:
                      settle: 100ms
                      timeout: 30s
                  accounts:
                    - id: bot
                      auth: OFFLINE
                      username: ChronitBot
                  servers:
                    - id: local
                      host: 127.0.0.1
                      port: %d
                  jobs:
                    - id: checkin
                      cron: "0 20 * * *"
                      visits:
                        - server: local
                          account: bot
                          stayFor: 1s
                          onReady:
                            - command: "login {{secrets.password}}"
                              delayAfter: 100ms
                            - command: "warp daily"
                              delayAfter: 100ms
                          onLeave:
                            - command: "logout"
                  """.formatted(yamlPath(stateDir), server.port()));

      McplDriver driver = new McplDriver();
      try {
        Orchestrator orchestrator = new Orchestrator(
            config, driver, new AccountManager(stateDir, new TokenStore(null)));

        RunRecord record = orchestrator.runJob(config.job("checkin").orElseThrow(), "test")
            .orElseThrow();

        RunRecord.VisitRecord visit = record.visits().getFirst();
        assertTrue(record.succeeded(), "visit should succeed, got: " + visit.detail());
        assertEquals("local", visit.serverId());
        assertEquals(2, visit.actionsRun(), "onReady actions are what the count reports");
        assertEquals(McplDriver.NATIVE_PROTOCOL, visit.protocolVersion());
        assertFalse(visit.translated());

        assertEquals(List.of("login s3cret-password", "warp daily", "logout"),
            server.packets(ServerboundChatCommandPacket.class).stream()
                .map(ServerboundChatCommandPacket::getCommand).toList(),
            "onReady then onLeave commands should arrive in order, secret substituted");

        assertEquals("login ***", Redactor.redact("login s3cret-password"),
            "the password must be masked wherever it is logged");

        // The history is what an operator reads the morning after; it must persist.
        assertTrue(orchestrator.history().file().toFile().isFile());
        assertEquals(1, orchestrator.history().recent(10).size());
      } finally {
        driver.shutdown();
      }
    }
  }

  /**
   * The whole menu interaction as a user would configure it: a command opens a plugin menu, the
   * sequence waits for it by title, clicks a slot, and closes it.
   */
  @Test
  void runsAMenuInteractionFromConfiguration(@TempDir Path stateDir) throws Exception {
    try (FakeMinecraftServer server = new FakeMinecraftServer(FakeMinecraftServer.Options.bare()).start()) {

      // Opens the menu when the client sends the command that asks for it, which is what a
      // plugin does and what makes the waitFor meaningful.
      server.openMenuOnCommand("rewards", 5, "Daily Rewards", 27, 11);

      ChronitConfig config = new ConfigLoader(Map.of()).loadString("""
              stateDir: "%s"
              defaults:
                jitter: 0
                readyWhen:
                  settle: 50ms
                  timeout: 30s
              accounts:
                - id: bot
                  auth: OFFLINE
                  username: ChronitBot
              servers:
                - id: local
                  host: 127.0.0.1
                  port: %d
              jobs:
                - id: rewards
                  cron: "0 20 * * *"
                  visits:
                    - server: local
                      account: bot
                      onReady:
                        - command: "rewards"
                          waitFor:
                            screen: "(?i)daily rewards"
                            timeout: 10s
                            onTimeout: FAIL
                        - click: { slot: 13 }
                          delayAfter: 100ms
                        - closeScreen: true
              """.formatted(yamlPath(stateDir), server.port()));

      McplDriver driver = new McplDriver();
      try {
        Orchestrator orchestrator = new Orchestrator(
            config, driver, new AccountManager(stateDir, new TokenStore(null)));
        RunRecord record = orchestrator.runJob(config.job("rewards").orElseThrow(), "test")
            .orElseThrow();

        RunRecord.VisitRecord visit = record.visits().getFirst();
        assertTrue(record.succeeded(), "visit should succeed, got: " + visit.detail());
        assertEquals(3, visit.actionsRun(), "command, click and close all count");

        List<ServerboundContainerClickPacket> clicks =
            server.packets(ServerboundContainerClickPacket.class);
        assertEquals(1, clicks.size(), "exactly one click should have been sent");
        assertEquals(5, clicks.getFirst().getContainerId());
        assertEquals(13, clicks.getFirst().getSlot());
        assertEquals(11, clicks.getFirst().getStateId(),
            "the click must echo the state id the menu was populated with");

        assertEquals(1, server.packets(ServerboundContainerClosePacket.class).size(),
            "the menu should be closed once, by the closeScreen action");
      } finally {
        driver.shutdown();
      }
    }
  }

  /** A menu that never opens must fail the visit rather than clicking into nothing. */
  @Test
  void failsTheVisitWhenTheMenuNeverOpens(@TempDir Path stateDir) throws Exception {
    try (FakeMinecraftServer server = new FakeMinecraftServer(FakeMinecraftServer.Options.bare()).start()) {
      ChronitConfig config = new ConfigLoader(Map.of()).loadString("""
              stateDir: "%s"
              defaults:
                jitter: 0
                readyWhen:
                  settle: 50ms
                  timeout: 30s
                onFail:
                  retries: 0
              accounts:
                - id: bot
                  auth: OFFLINE
                  username: ChronitBot
              servers:
                - id: local
                  host: 127.0.0.1
                  port: %d
              jobs:
                - id: rewards
                  cron: "0 20 * * *"
                  visits:
                    - server: local
                      account: bot
                      onReady:
                        - command: "rewards"
                          waitFor:
                            screen: "(?i)daily rewards"
                            timeout: 1s
                            onTimeout: FAIL
                        - click: { slot: 13 }
              """.formatted(yamlPath(stateDir), server.port()));

      McplDriver driver = new McplDriver();
      try {
        Orchestrator orchestrator = new Orchestrator(
            config, driver, new AccountManager(stateDir, new TokenStore(null)));
        RunRecord record = orchestrator.runJob(config.job("rewards").orElseThrow(), "test")
            .orElseThrow();

        assertFalse(record.succeeded());
        assertTrue(record.visits().getFirst().detail().contains("menu"),
            "the failure should name what it waited for: "
                + record.visits().getFirst().detail());
        assertTrue(server.packets(ServerboundContainerClickPacket.class).isEmpty(),
            "no click should be sent when the menu never appeared");
      } finally {
        driver.shutdown();
      }
    }
  }

  @Test
  void failedVisitIsRecordedRatherThanThrowing(@TempDir Path stateDir) throws Exception {
    // Port 1 is not listening, so the connection is refused immediately.
    ChronitConfig config = new ConfigLoader(Map.of()).loadString("""
            stateDir: "%s"
            defaults:
              readyWhen:
                timeout: 3s
              onFail:
                retries: 0
            accounts:
              - id: bot
                auth: OFFLINE
                username: ChronitBot
            servers:
              - id: dead
                host: 127.0.0.1
                port: 1
            jobs:
              - id: doomed
                cron: "0 20 * * *"
                visits:
                  - server: dead
                    account: bot
            """.formatted(yamlPath(stateDir)));

    McplDriver driver = new McplDriver();
    try {
      Orchestrator orchestrator = new Orchestrator(
          config, driver, new AccountManager(stateDir, new TokenStore(null)));
      RunRecord record = orchestrator.runJob(config.job("doomed").orElseThrow(), "test")
          .orElseThrow();

      assertEquals(1, record.visits().size());
      assertFalse(record.succeeded(), "a refused connection must be recorded as a failure");
      assertFalse(record.visits().getFirst().detail() == null
              || record.visits().getFirst().detail().isBlank(),
          "the failure should say why");
    } finally {
      driver.shutdown();
    }
  }
}
