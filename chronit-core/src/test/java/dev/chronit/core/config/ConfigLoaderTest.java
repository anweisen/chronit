package dev.chronit.core.config;

import dev.chronit.core.driver.ClientInformation;
import dev.chronit.core.driver.SlotClick;
import dev.chronit.core.driver.SessionSettings;
import dev.chronit.core.util.Redactor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConfigLoaderTest {

    private static final String MINIMAL = """
            accounts:
              - id: test
                auth: OFFLINE
                username: TestBot
            servers:
              - id: local
                host: 127.0.0.1
            jobs:
              - id: nightly
                cron: "0 20 * * *"
                visits:
                  - server: local
                    account: test
                    stayFor: 30m
            """;

    @AfterEach
    void clearSecrets() {
        Redactor.clear();
    }

    @Test
    void loadsMinimalConfig() {
        ChronitConfig config = new ConfigLoader(Map.of()).loadString(MINIMAL);

        assertEquals(1, config.accountsOrEmpty().size());
        assertEquals(AccountConfig.AuthMode.OFFLINE, config.account("test").orElseThrow().authOrDefault());
        assertEquals(25565, config.server("local").orElseThrow().portOrDefault());
        assertEquals(Duration.ofMinutes(30),
                config.job("nightly").orElseThrow().visits().getFirst().stayFor());
    }

    @Test
    void acceptsCompactAndIsoDurations() {
        ChronitConfig config = new ConfigLoader(Map.of()).loadString(MINIMAL + """
                defaults:
                  connectTimeout: PT45S
                  resourcePack:
                    downloadDelay: 2500ms
                    applyDelay: 1h30m
                """);

        DefaultsConfig defaults = config.effectiveDefaults();
        assertEquals(Duration.ofSeconds(45), defaults.connectTimeout());
        assertEquals(Duration.ofMillis(2500), defaults.resourcePack().downloadDelay());
        assertEquals(Duration.ofMinutes(90), defaults.resourcePack().applyDelay());
    }

    @Test
    void mergesServerOverridesOverDefaults() {
        ChronitConfig config = new ConfigLoader(Map.of()).loadString("""
                defaults:
                  brand: fabric
                  resourcePack:
                    mode: DOWNLOAD
                    maxSizeMb: 100
                  clientInformation:
                    viewDistance: 12
                accounts:
                  - id: test
                    auth: OFFLINE
                    username: TestBot
                servers:
                  - id: a
                    host: a.example.net
                  - id: b
                    host: b.example.net
                    brand: vanilla
                    resourcePack:
                      mode: FAKE
                jobs:
                  - id: j
                    cron: "0 20 * * *"
                    visits:
                      - { server: a, account: test }
                """);

        SessionSettings a = SessionSettings.resolve(config, config.server("a").orElseThrow());
        SessionSettings b = SessionSettings.resolve(config, config.server("b").orElseThrow());

        assertEquals("fabric", a.brand());
        assertEquals(ResourcePackConfig.Mode.DOWNLOAD, a.resourcePack().mode());

        // The server overrides only what it names; everything else still comes from defaults.
        assertEquals("vanilla", b.brand());
        assertEquals(ResourcePackConfig.Mode.FAKE, b.resourcePack().mode());
        assertEquals(100, b.resourcePack().maxSizeMb());
        assertEquals(12, b.clientInformation().viewDistance());
        assertEquals(ClientInformation.MainHand.RIGHT, b.clientInformation().mainHand());
    }

    @Test
    void interpolatesEnvironmentAndSecrets() throws Exception {
        Path secrets = Files.createTempFile("chronit-secrets", ".yml");
        Path config = Files.createTempFile("chronit", ".yml");
        try {
            Files.writeString(secrets, "survival_pw: hunter2hunter2\n");
            Files.writeString(config, """
                    secretsFile: %s
                    accounts:
                      - id: test
                        auth: OFFLINE
                        username: TestBot
                    servers:
                      - id: local
                        host: ${MC_HOST}
                        port: ${MC_PORT:-25577}
                    jobs:
                      - id: j
                        cron: "0 20 * * *"
                        visits:
                          - server: local
                            account: test
                            onReady:
                              - command: "login {{secrets.survival_pw}}"
                    """.formatted(secrets.toString().replace("\\", "\\\\")));

            ChronitConfig loaded = new ConfigLoader(Map.of("MC_HOST", "mc.example.net"))
                    .load(config);

            ServerConfig server = loaded.server("local").orElseThrow();
            assertEquals("mc.example.net", server.host());
            assertEquals(25577, server.portOrDefault(), "fallback should be used when unset");

            ActionConfig action = loaded.job("j").orElseThrow().visits().getFirst().onReady().getFirst();
            assertEquals("login hunter2hunter2", action.command());

            // The secret must be masked everywhere it later appears, including command text.
            assertEquals("login ***", Redactor.redact(action.command()));
        } finally {
            Files.deleteIfExists(secrets);
            Files.deleteIfExists(config);
        }
    }

    @Test
    void reportsUnsetEnvironmentVariable() {
        ConfigException error = assertThrows(ConfigException.class,
                () -> new ConfigLoader(Map.of()).loadString("""
                        accounts: [{id: t, auth: OFFLINE, username: T}]
                        servers: [{id: s, host: "${NOT_SET_ANYWHERE}"}]
                        jobs: [{id: j, cron: "0 20 * * *", visits: [{server: s, account: t}]}]
                        """));
        assertTrue(error.getMessage().contains("NOT_SET_ANYWHERE"), error.getMessage());
    }

    @Test
    void rejectsUnknownKeysRatherThanIgnoringThem() {
        ConfigException error = assertThrows(ConfigException.class,
                () -> new ConfigLoader(Map.of()).loadString("""
                        accounts: [{id: t, auth: OFFLINE, username: T}]
                        servers: [{id: s, host: h, prtocol: auto}]
                        jobs: [{id: j, cron: "0 20 * * *", visits: [{server: s, account: t}]}]
                        """));
        assertTrue(error.getMessage().contains("prtocol"), error.getMessage());
    }

    @Test
    void collectsEveryValidationProblem() {
        ConfigException error = assertThrows(ConfigException.class,
                () -> new ConfigLoader(Map.of()).loadString("""
                        accounts:
                          - id: dup
                            auth: OFFLINE
                            username: A
                          - id: dup
                            auth: OFFLINE
                            username: B
                        servers:
                          - id: s
                            host: h
                            port: 70000
                        jobs:
                          - id: j
                            cron: "not a cron"
                            visits:
                              - server: missing
                                account: dup
                                onReady:
                                  - command: "/warp home"
                        """));

        String message = error.getMessage();
        assertTrue(message.contains("duplicate account id"), message);
        assertTrue(message.contains("not a valid port"), message);
        assertTrue(message.contains("not a valid cron"), message);
        assertTrue(message.contains("no server with id 'missing'"), message);
        assertTrue(message.contains("drop the leading slash"), message);
    }

    @Test
    void rejectsActionWithNoOperation() {
        ConfigException error = assertThrows(ConfigException.class,
                () -> new ConfigLoader(Map.of()).loadString("""
                        accounts: [{id: t, auth: OFFLINE, username: T}]
                        servers: [{id: s, host: h}]
                        jobs:
                          - id: j
                            cron: "0 20 * * *"
                            visits:
                              - server: s
                                account: t
                                onReady:
                                  - delayAfter: 5s
                        """));
        assertTrue(error.getMessage().contains("needs one of"), error.getMessage());
    }

    @Test
    void parsesAMenuInteraction() {
        ChronitConfig config = new ConfigLoader(Map.of()).loadString("""
                accounts: [{id: t, auth: OFFLINE, username: T}]
                servers: [{id: s, host: h}]
                jobs:
                  - id: j
                    cron: "0 20 * * *"
                    visits:
                      - server: s
                        account: t
                        onReady:
                          - command: "rewards"
                            waitFor: { screen: "(?i)daily", timeout: 5s }
                          - click: { slot: 13 }
                          - click: { slot: 3, inventory: PLAYER, button: RIGHT, mode: SHIFT }
                          - closeScreen: true
                """);

        List<ActionConfig> actions = config.job("j").orElseThrow().visits().getFirst().onReady();
        assertEquals(ActionConfig.Kind.COMMAND, actions.get(0).kind());
        assertEquals(WaitForConfig.Subject.SCREEN, actions.get(0).waitFor().subject());
        assertEquals("(?i)daily", actions.get(0).waitFor().pattern());

        assertEquals(ActionConfig.Kind.CLICK, actions.get(1).kind());
        SlotClick simple = actions.get(1).click().toSlotClick();
        assertEquals(SlotClick.InventoryPart.CONTAINER, simple.part(), "the opened menu is the default");
        assertEquals(13, simple.slot());
        assertEquals(SlotClick.ClickButton.LEFT, simple.button());
        assertEquals(SlotClick.ClickMode.PICKUP, simple.mode());

        SlotClick detailed = actions.get(2).click().toSlotClick();
        assertEquals(SlotClick.InventoryPart.PLAYER, detailed.part());
        assertEquals(SlotClick.ClickButton.RIGHT, detailed.button());
        assertEquals(SlotClick.ClickMode.SHIFT, detailed.mode());

        assertEquals(ActionConfig.Kind.CLOSE_SCREEN, actions.get(3).kind());
    }

    @Test
    void rejectsAWaitForWithBothOrNeitherSubject() {
        ConfigException both = assertThrows(ConfigException.class,
                () -> new ConfigLoader(Map.of()).loadString("""
                        accounts: [{id: t, auth: OFFLINE, username: T}]
                        servers: [{id: s, host: h}]
                        jobs:
                          - id: j
                            cron: "0 20 * * *"
                            visits:
                              - server: s
                                account: t
                                onReady:
                                  - command: "x"
                                    waitFor: { chat: "a", screen: "b" }
                        """));
        assertTrue(both.getMessage().contains("only one of 'chat' or 'screen'"), both.getMessage());

        ConfigException neither = assertThrows(ConfigException.class,
                () -> new ConfigLoader(Map.of()).loadString("""
                        accounts: [{id: t, auth: OFFLINE, username: T}]
                        servers: [{id: s, host: h}]
                        jobs:
                          - id: j
                            cron: "0 20 * * *"
                            visits:
                              - server: s
                                account: t
                                onReady:
                                  - command: "x"
                                    waitFor: { timeout: 5s }
                        """));
        assertTrue(neither.getMessage().contains("needs 'chat' or 'screen'"), neither.getMessage());
    }

    @Test
    void rejectsAnImpossiblePlayerInventorySlot() {
        ConfigException error = assertThrows(ConfigException.class,
                () -> new ConfigLoader(Map.of()).loadString("""
                        accounts: [{id: t, auth: OFFLINE, username: T}]
                        servers: [{id: s, host: h}]
                        jobs:
                          - id: j
                            cron: "0 20 * * *"
                            visits:
                              - server: s
                                account: t
                                onReady:
                                  - click: { slot: 40, inventory: PLAYER }
                        """));
        assertTrue(error.getMessage().contains("outside the player inventory"), error.getMessage());
    }

    @Test
    void readsTheTokenRefreshSettings() {
        ChronitConfig config = new ConfigLoader(Map.of()).loadString(MINIMAL + """
                auth:
                  refreshOnStart: false
                  refreshInterval: 2h
                  refreshMargin: 45m
                """);

        AuthConfig auth = config.authOrDefaults();
        assertFalse(auth.refreshOnStartOrDefault());
        assertEquals(Duration.ofHours(2), auth.refreshIntervalOrDefault());
        assertEquals(Duration.ofHours(2).plusMinutes(45), auth.sweepHorizon());
        assertTrue(auth.isBackgroundRefreshEnabled());
    }

    @Test
    void refreshesOnASensibleScheduleWhenNothingIsConfigured() {
        AuthConfig auth = new ConfigLoader(Map.of()).loadString(MINIMAL).authOrDefaults();

        assertTrue(auth.refreshOnStartOrDefault());
        assertEquals(Duration.ofHours(6), auth.refreshIntervalOrDefault());
        assertTrue(auth.isBackgroundRefreshEnabled());
    }

    @Test
    void treatsAZeroRefreshIntervalAsTurningTheSweepOff() {
        AuthConfig auth = new ConfigLoader(Map.of()).loadString(MINIMAL + """
                auth:
                  refreshInterval: 0
                """).authOrDefaults();

        assertFalse(auth.isBackgroundRefreshEnabled());
    }

    @Test
    void rejectsARefreshIntervalTooShortToBeWorthIt() {
        ConfigException error = assertThrows(ConfigException.class,
                () -> new ConfigLoader(Map.of()).loadString(MINIMAL + """
                        auth:
                          refreshInterval: 30s
                        """));
        assertTrue(error.getMessage().contains("auth.refreshInterval"), error.getMessage());
    }

    @Test
    void requiresTokenWhenWebIsNotLoopback() {
        ConfigException error = assertThrows(ConfigException.class,
                () -> new ConfigLoader(Map.of()).loadString(MINIMAL + """
                        web:
                          enabled: true
                          bind: 0.0.0.0
                        """));
        assertTrue(error.getMessage().contains("web.token"), error.getMessage());
    }

    @Test
    void allowsLoopbackWebWithoutToken() {
        ChronitConfig config = new ConfigLoader(Map.of()).loadString(MINIMAL + """
                web:
                  enabled: true
                """);
        assertTrue(config.webOrDisabled().isEnabled());
        assertTrue(config.webOrDisabled().isLoopbackOnly());
        assertFalse(config.webOrDisabled().port() != null && config.webOrDisabled().port() == 0);
    }
}
