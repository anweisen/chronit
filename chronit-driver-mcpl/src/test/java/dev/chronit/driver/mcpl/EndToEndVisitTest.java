package dev.chronit.driver.mcpl;

import dev.chronit.core.auth.AccountManager;
import dev.chronit.core.auth.TokenStore;
import dev.chronit.core.config.ChronitConfig;
import dev.chronit.core.config.ConfigLoader;
import dev.chronit.core.run.Orchestrator;
import dev.chronit.core.state.RunRecord;
import dev.chronit.core.util.Redactor;
import org.geysermc.mcprotocollib.protocol.packet.ingame.serverbound.ServerboundChatCommandPacket;
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
