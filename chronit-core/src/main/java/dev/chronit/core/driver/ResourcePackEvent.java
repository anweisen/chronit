package dev.chronit.core.driver;

import java.time.Duration;
import java.util.UUID;

/**
 * Reports each status the client sent for a pushed resource pack.
 *
 * <p>Emitted once per status so the log shows the full sequence a real client produces —
 * accepted, downloaded, then successfully loaded — which is exactly what a server operator
 * investigating a failed join wants to see.
 *
 * @param status    the status reported to the server
 * @param sizeBytes actual size when the pack was downloaded, otherwise -1
 * @param elapsed   time since the pack was pushed
 */
public record ResourcePackEvent(
        UUID id,
        String url,
        String hash,
        boolean required,
        String prompt,
        Status status,
        long sizeBytes,
        Duration elapsed) {

    /** Mirrors the wire enum; the driver maps it. */
    public enum Status {
        ACCEPTED,
        DOWNLOADED,
        SUCCESSFULLY_LOADED,
        DECLINED,
        FAILED_DOWNLOAD,
        INVALID_URL,
        FAILED_RELOAD,
        DISCARDED
    }
}
