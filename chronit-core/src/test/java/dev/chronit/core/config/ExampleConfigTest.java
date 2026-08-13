package dev.chronit.core.config;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Keeps the shipped example honest.
 *
 * <p>It is the first thing anyone copies, so a key that has been renamed or a value the validator
 * rejects is worse there than anywhere else — {@code FAIL_ON_UNKNOWN_PROPERTIES} means a stale key
 * does not merely get ignored, it stops the process starting.
 */
class ExampleConfigTest {

    private static final Path EXAMPLE = Path.of("..", "config", "chronit.example.yml");

    @Test
    void theShippedExampleStillLoads() throws IOException {
        ChronitConfig config = load();

        assertEquals(2, config.accountsOrEmpty().size());
        assertTrue(config.server("survival").isPresent());
        assertTrue(config.job("nightly").isPresent(), "jobs: " + config.jobsOrEmpty());
    }

    @Test
    void theExampleDocumentsTheRealRefreshDefaults() throws IOException {
        AuthConfig example = load().authOrDefaults();

        // The block is written out in full as documentation, so it has to agree with the code it
        // claims to be showing.
        assertEquals(AuthConfig.DEFAULTS.refreshOnStart(), example.refreshOnStart());
        assertEquals(AuthConfig.DEFAULTS.refreshInterval(), example.refreshInterval());
        assertEquals(AuthConfig.DEFAULTS.refreshMargin(), example.refreshMargin());
        assertEquals(Duration.ofHours(6).plusMinutes(30), example.sweepHorizon());
    }

    /**
     * Loads it with the one line removed that points at a path only a deployment has, and the
     * secret it references supplied from the environment instead.
     */
    private ChronitConfig load() throws IOException {
        String yaml = Files.readString(EXAMPLE).lines()
                .filter(line -> !line.startsWith("secretsFile:"))
                .reduce(new StringBuilder(), (out, line) -> out.append(line).append('\n'),
                        StringBuilder::append)
                .toString();

        return new ConfigLoader(Map.of("CHRONIT_SECRET_SURVIVAL_PASSWORD", "not-a-real-password"))
                .loadString(yaml);
    }
}
