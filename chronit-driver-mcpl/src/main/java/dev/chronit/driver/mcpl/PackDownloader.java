package dev.chronit.driver.mcpl;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.DigestOutputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.HexFormat;
import java.util.Locale;

/**
 * Fetches a resource pack so that the reported timings correspond to a real download.
 *
 * <p>A pack is identified by the SHA-1 the server supplies, which is also the cache key: the same
 * pack is downloaded once and reused across every later visit.
 */
final class PackDownloader {

    private static final Logger log = LoggerFactory.getLogger(PackDownloader.class);

    /** Read in modest chunks so a hostile URL cannot force a huge allocation up front. */
    private static final int BUFFER_SIZE = 64 * 1024;

    private final HttpClient http;
    private final Path cacheDir;
    private final long maxBytes;
    private final Duration timeout;

    PackDownloader(Path cacheDir, int maxSizeMb, Duration timeout) {
        this.cacheDir = cacheDir;
        this.maxBytes = (long) maxSizeMb * 1024L * 1024L;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(15))
                // Servers commonly host packs behind a redirect to object storage.
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();
    }

    /** Outcome of a download attempt. */
    record Result(boolean success, long sizeBytes, String actualHash, boolean hashMatched, String failure) {

        static Result failed(String reason) {
            return new Result(false, -1, null, false, reason);
        }
    }

    Result download(String url, String expectedHash) {
        if (url == null || url.isBlank()) {
            return Result.failed("empty url");
        }
        URI uri;
        try {
            uri = URI.create(url);
        } catch (IllegalArgumentException e) {
            return Result.failed("malformed url");
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("http") && !scheme.equals("https")) {
            return Result.failed("unsupported url scheme '" + scheme + "'");
        }

        Path cached = cachePath(expectedHash);
        if (cached != null && Files.isReadable(cached)) {
            try {
                long size = Files.size(cached);
                log.debug("Resource pack {} already cached ({} bytes)", shortHash(expectedHash), size);
                return new Result(true, size, expectedHash, true, null);
            } catch (IOException e) {
                // Fall through and re-download.
                log.debug("Could not stat cached pack {}: {}", cached, e.toString());
            }
        }

        try {
            HttpRequest request = HttpRequest.newBuilder(uri)
                    .timeout(timeout)
                    .header("User-Agent", "Minecraft Java/" + McplDriver.NATIVE_VERSION)
                    .GET()
                    .build();
            HttpResponse<InputStream> response =
                    http.send(request, HttpResponse.BodyHandlers.ofInputStream());

            if (response.statusCode() / 100 != 2) {
                return Result.failed("HTTP " + response.statusCode());
            }

            long declared = response.headers().firstValueAsLong("content-length").orElse(-1L);
            if (declared > maxBytes) {
                return Result.failed("pack is " + declared + " bytes, over the configured limit");
            }

            Path temp = Files.createTempFile(ensureCacheDir(), "pack-", ".tmp");
            long written;
            String actualHash;
            try (InputStream in = response.body()) {
                MessageDigest digest = MessageDigest.getInstance("SHA-1");
                try (OutputStream fileOut = Files.newOutputStream(temp);
                     DigestOutputStream out = new DigestOutputStream(fileOut, digest)) {
                    written = copyBounded(in, out);
                }
                actualHash = HexFormat.of().formatHex(digest.digest());
            } catch (NoSuchAlgorithmException e) {
                throw new IllegalStateException("SHA-1 is required by the platform", e);
            } catch (SizeLimitExceededException e) {
                Files.deleteIfExists(temp);
                return Result.failed("pack exceeded the configured size limit");
            }

            boolean matched = expectedHash == null || expectedHash.isBlank()
                    || expectedHash.equalsIgnoreCase(actualHash);

            Path destination = cachePath(matched ? expectedHash : actualHash);
            if (destination != null) {
                Files.move(temp, destination, StandardCopyOption.REPLACE_EXISTING);
            } else {
                Files.deleteIfExists(temp);
            }
            return new Result(true, written, actualHash, matched, null);
        } catch (IOException e) {
            return Result.failed(e.getClass().getSimpleName() + ": " + e.getMessage());
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.failed("interrupted");
        }
    }

    private long copyBounded(InputStream in, OutputStream out) throws IOException {
        byte[] buffer = new byte[BUFFER_SIZE];
        long total = 0;
        int read;
        while ((read = in.read(buffer)) >= 0) {
            total += read;
            if (total > maxBytes) {
                throw new SizeLimitExceededException();
            }
            out.write(buffer, 0, read);
        }
        return total;
    }

    private Path ensureCacheDir() throws IOException {
        Files.createDirectories(cacheDir);
        return cacheDir;
    }

    private Path cachePath(String hash) {
        if (hash == null || !hash.matches("[0-9a-fA-F]{40}")) {
            return null;
        }
        try {
            return ensureCacheDir().resolve(hash.toLowerCase(Locale.ROOT) + ".pack");
        } catch (IOException e) {
            log.warn("Resource pack cache directory {} is unusable: {}", cacheDir, e.toString());
            return null;
        }
    }

    private static String shortHash(String hash) {
        return hash == null || hash.length() < 8 ? String.valueOf(hash) : hash.substring(0, 8);
    }

    /** Signals that a response body grew past the configured limit mid-stream. */
    private static final class SizeLimitExceededException extends IOException {
    }
}
