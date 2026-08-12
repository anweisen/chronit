package dev.chronit.core.auth;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermission;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Optional;
import java.util.Set;

/**
 * Persists a Microsoft session between runs.
 *
 * <p>What is stored is a refresh token that stays valid for roughly ninety days and can mint
 * Minecraft access tokens for the account — in other words, something worth protecting. The file is
 * written with owner-only permissions where the filesystem supports them, and encrypted at rest
 * when {@code CHRONIT_SECRET_KEY} is set.
 *
 * <p>Encryption is optional rather than mandatory because a container that must run unattended
 * needs the key present anyway; a deployment that keeps the key in a secrets manager gets real
 * protection from it, and one that does not is no worse off than with a plain file.
 */
public final class TokenStore {

    private static final Logger log = LoggerFactory.getLogger(TokenStore.class);

    private static final String ENV_KEY = "CHRONIT_SECRET_KEY";
    private static final String CIPHER = "AES/GCM/NoPadding";
    private static final int GCM_TAG_BITS = 128;
    private static final int IV_LENGTH = 12;
    private static final int FORMAT_VERSION = 1;

    private final byte[] key;

    public TokenStore() {
        this(System.getenv(ENV_KEY));
    }

    /** @param passphrase null or blank disables encryption */
    public TokenStore(String passphrase) {
        this.key = deriveKey(passphrase);
    }

    public boolean isEncrypting() {
        return key != null;
    }

    public Optional<JsonObject> read(Path file) {
        if (!Files.isReadable(file)) {
            return Optional.empty();
        }
        try {
            String text = Files.readString(file, StandardCharsets.UTF_8);
            JsonObject json = JsonParser.parseString(text).getAsJsonObject();

            if (!json.has("chronitEncrypted")) {
                if (key != null) {
                    log.info("Token file {} is not encrypted; it will be encrypted on next write", file);
                }
                return Optional.of(json);
            }
            if (key == null) {
                throw new IllegalStateException("Token file " + file + " is encrypted but "
                        + ENV_KEY + " is not set");
            }
            return Optional.of(decrypt(json));
        } catch (IOException | RuntimeException e) {
            log.warn("Could not read token file {}: {}", file, e.toString());
            return Optional.empty();
        }
    }

    public void write(Path file, JsonObject json) throws IOException {
        Path parent = file.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }

        String text = (key == null ? json : encrypt(json)).toString();

        // Write then move, so an interrupted write cannot leave a truncated token file behind —
        // which would mean an unnecessary interactive login.
        Path temp = Files.createTempFile(parent != null ? parent : Path.of("."), "token-", ".tmp");
        try {
            restrictPermissions(temp);
            Files.writeString(temp, text, StandardCharsets.UTF_8);
            Files.move(temp, file, java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            restrictPermissions(file);
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    /** Best effort: POSIX systems get owner-only, Windows keeps its inherited ACL. */
    private static void restrictPermissions(Path file) {
        try {
            Set<PosixFilePermission> ownerOnly = PosixFilePermissions.fromString("rw-------");
            Files.setPosixFilePermissions(file, ownerOnly);
        } catch (UnsupportedOperationException | IOException e) {
            log.trace("Could not restrict permissions on {}: {}", file, e.toString());
        }
    }

    private JsonObject encrypt(JsonObject plain) {
        try {
            byte[] iv = new byte[IV_LENGTH];
            new SecureRandom().nextBytes(iv);

            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            byte[] encrypted = cipher.doFinal(plain.toString().getBytes(StandardCharsets.UTF_8));

            JsonObject wrapper = new JsonObject();
            wrapper.addProperty("chronitEncrypted", FORMAT_VERSION);
            wrapper.addProperty("iv", Base64.getEncoder().encodeToString(iv));
            wrapper.addProperty("data", Base64.getEncoder().encodeToString(encrypted));
            return wrapper;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Could not encrypt the token file", e);
        }
    }

    private JsonObject decrypt(JsonObject wrapper) {
        try {
            byte[] iv = Base64.getDecoder().decode(wrapper.get("iv").getAsString());
            byte[] data = Base64.getDecoder().decode(wrapper.get("data").getAsString());

            Cipher cipher = Cipher.getInstance(CIPHER);
            cipher.init(Cipher.DECRYPT_MODE, new SecretKeySpec(key, "AES"),
                    new GCMParameterSpec(GCM_TAG_BITS, iv));
            String plain = new String(cipher.doFinal(data), StandardCharsets.UTF_8);
            return JsonParser.parseString(plain).getAsJsonObject();
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(
                    "Could not decrypt the token file — is " + ENV_KEY + " the same value it was written with?", e);
        }
    }

    private static byte[] deriveKey(String passphrase) {
        if (passphrase == null || passphrase.isBlank()) {
            return null;
        }
        try {
            // A plain digest rather than a slow KDF: the input is a machine-generated secret from
            // the environment, not a human-chosen password, so there is nothing to brute force.
            return MessageDigest.getInstance("SHA-256").digest(passphrase.getBytes(StandardCharsets.UTF_8));
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("SHA-256 is required by the platform", e);
        }
    }
}
