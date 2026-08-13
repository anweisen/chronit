package net.anweisen.chronit.driver.mcpl;

import net.anweisen.chronit.core.config.ResourcePackConfig;
import net.anweisen.chronit.core.driver.ClientEvents;
import net.anweisen.chronit.core.driver.ResourcePackEvent;
import net.anweisen.chronit.core.util.Jitter;
import org.geysermc.mcprotocollib.network.Session;
import org.geysermc.mcprotocollib.protocol.data.game.ResourcePackStatus;
import org.geysermc.mcprotocollib.protocol.packet.common.serverbound.ServerboundResourcePackPacket;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Answers server-pushed resource packs the way a real client does.
 *
 * <p>A vanilla client reports three statuses in sequence — accepted, then downloaded, then
 * successfully loaded — separated by however long the download and reload actually took. Servers
 * with {@code require-resource-pack=true} disconnect a client that declines or reports a failure,
 * and some anti-bot plugins additionally compare the elapsed time against the pack size, so the
 * gaps matter as much as the statuses.
 *
 * <p>All waiting happens on a scheduler rather than inline: the network thread must not be blocked,
 * and each pack's own statuses stay ordered because every step schedules the next.
 */
final class ResourcePackResponder {

    private static final Logger log = LoggerFactory.getLogger(ResourcePackResponder.class);

    private final Session session;
    private final ResourcePackConfig config;
    private final Jitter jitter;
    private final ClientEvents events;
    private final ScheduledExecutorService scheduler;
    private final ExecutorService io;
    private final PackDownloader downloader;

    private final Map<UUID, Pack> active = new ConcurrentHashMap<>();
    private final Runnable onSettled;

    ResourcePackResponder(Session session,
                          ResourcePackConfig config,
                          Jitter jitter,
                          ClientEvents events,
                          ScheduledExecutorService scheduler,
                          ExecutorService io,
                          Runnable onSettled) {
        this.session = session;
        this.config = config;
        this.jitter = jitter;
        this.events = events;
        this.scheduler = scheduler;
        this.io = io;
        this.onSettled = onSettled;
        this.downloader = config.mode() == ResourcePackConfig.Mode.DOWNLOAD
                ? new PackDownloader(config.cacheDir(), config.maxSizeMb(), config.httpTimeout())
                : null;
    }

    /**
     * Whether any pack is still mid-sequence.
     *
     * <p>Used to hold back the readiness signal: a client that has not finished loading a pack has
     * not finished joining, and plugins that gate on pack status would drop commands sent in that
     * window.
     */
    boolean hasPending() {
        return !active.isEmpty();
    }

    private record Pack(UUID id, String url, String hash, boolean required, String prompt, Instant pushedAt) {
    }

    void onPush(UUID id, String url, String hash, boolean required, String prompt) {
        Pack pack = new Pack(id, url, hash, required, prompt, Instant.now());
        active.put(id, pack);

        log.info("Server pushed resource pack {} ({}), required={}, mode={}",
                shortId(id), url, required, config.mode());
        if (prompt != null && !prompt.isBlank()) {
            log.debug("Resource pack prompt: {}", prompt);
        }

        switch (config.mode()) {
            case DECLINE -> {
                if (required) {
                    log.warn("Declining a required resource pack — the server will almost certainly "
                            + "close the connection. Set resourcePack.mode to FAKE or DOWNLOAD to accept it.");
                }
                report(pack, ResourcePackStatus.DECLINED, -1);
                finish(id);
            }
            case FAKE -> {
                report(pack, ResourcePackStatus.ACCEPTED, -1);
                scheduler.schedule(() -> {
                    report(pack, ResourcePackStatus.DOWNLOADED, -1);
                    scheduleLoaded(pack, -1);
                }, delayMillis(config.downloadDelay()), TimeUnit.MILLISECONDS);
            }
            case DOWNLOAD -> {
                report(pack, ResourcePackStatus.ACCEPTED, -1);
                io.execute(() -> runDownload(pack));
            }
        }
    }

    /** The server withdrew a pack. No response is expected; just stop tracking it. */
    void onPop(UUID id) {
        if (id == null) {
            active.clear();
            log.debug("Server cleared all resource packs");
            onSettled.run();
        } else if (active.remove(id) != null) {
            log.debug("Server removed resource pack {}", shortId(id));
            finish(null);
        }
    }

    /** Drops a pack from tracking and lets the session re-check whether it can now be ready. */
    private void finish(UUID id) {
        if (id != null) {
            active.remove(id);
        }
        onSettled.run();
    }

    private void runDownload(Pack pack) {
        PackDownloader.Result result = downloader.download(pack.url(), pack.hash());

        if (!result.success()) {
            log.warn("Resource pack {} could not be downloaded ({}); reporting failure to the server",
                    shortId(pack.id()), result.failure());
            report(pack, ResourcePackStatus.FAILED_DOWNLOAD, -1);
            finish(pack.id());
            return;
        }

        if (!result.hashMatched()) {
            // A mismatch is common in practice: operators update a pack and forget to update the
            // hash in server.properties. A real client would fail here, but failing is what gets
            // us kicked, so by default we carry on and only shout about it.
            if (Boolean.TRUE.equals(config.strict())) {
                log.warn("Resource pack {} hash mismatch (expected {}, got {}); strict mode is on, "
                                + "reporting failure", shortId(pack.id()), pack.hash(), result.actualHash());
                report(pack, ResourcePackStatus.FAILED_DOWNLOAD, result.sizeBytes());
                finish(pack.id());
                return;
            }
            log.warn("Resource pack {} hash mismatch (expected {}, got {}); accepting anyway",
                    shortId(pack.id()), pack.hash(), result.actualHash());
        }

        log.info("Resource pack {} downloaded, {} bytes in {}",
                shortId(pack.id()), result.sizeBytes(),
                net.anweisen.chronit.core.util.Durations.format(Duration.between(pack.pushedAt(), Instant.now())));

        report(pack, ResourcePackStatus.DOWNLOADED, result.sizeBytes());
        scheduleLoaded(pack, result.sizeBytes());
    }

    private void scheduleLoaded(Pack pack, long sizeBytes) {
        scheduler.schedule(() -> {
            report(pack, ResourcePackStatus.SUCCESSFULLY_LOADED, sizeBytes);
            finish(pack.id());
        }, delayMillis(config.applyDelay()), TimeUnit.MILLISECONDS);
    }

    private void report(Pack pack, ResourcePackStatus status, long sizeBytes) {
        if (!session.isConnected()) {
            log.debug("Not reporting {} for pack {}: session already closed", status, shortId(pack.id()));
            return;
        }
        session.send(new ServerboundResourcePackPacket(pack.id(), status));
        log.debug("Reported resource pack {} as {}", shortId(pack.id()), status);

        events.onResourcePack(new ResourcePackEvent(
                pack.id(), pack.url(), pack.hash(), pack.required(), pack.prompt(),
                map(status), sizeBytes, Duration.between(pack.pushedAt(), Instant.now())));
    }

    private long delayMillis(Duration base) {
        Duration delay = jitter.apply(base);
        return Math.max(0L, delay.toMillis());
    }

    private static ResourcePackEvent.Status map(ResourcePackStatus status) {
        return switch (status) {
            case SUCCESSFULLY_LOADED -> ResourcePackEvent.Status.SUCCESSFULLY_LOADED;
            case DECLINED -> ResourcePackEvent.Status.DECLINED;
            case FAILED_DOWNLOAD -> ResourcePackEvent.Status.FAILED_DOWNLOAD;
            case ACCEPTED -> ResourcePackEvent.Status.ACCEPTED;
            case DOWNLOADED -> ResourcePackEvent.Status.DOWNLOADED;
            case INVALID_URL -> ResourcePackEvent.Status.INVALID_URL;
            case FAILED_RELOAD -> ResourcePackEvent.Status.FAILED_RELOAD;
            case DISCARDED -> ResourcePackEvent.Status.DISCARDED;
        };
    }

    private static String shortId(UUID id) {
        String text = id.toString();
        return text.substring(0, 8);
    }
}
