package dev.chronit.core.auth;

import com.google.gson.JsonObject;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TokenStoreTest {

    @TempDir
    Path directory;

    @Test
    void roundTripsWithoutAKey() throws IOException {
        TokenStore store = new TokenStore(null);
        Path file = directory.resolve("tokens/main.json");

        store.write(file, session("hunter2"));

        assertFalse(store.isEncrypting());
        assertEquals("hunter2", store.read(file).orElseThrow().get("refreshToken").getAsString());
        // Plain, so it is greppable and portable between installs that do not set a key.
        assertTrue(Files.readString(file).contains("hunter2"));
    }

    @Test
    void roundTripsEncrypted() throws IOException {
        TokenStore store = new TokenStore("a-machine-generated-secret");
        Path file = directory.resolve("main.json");

        store.write(file, session("hunter2"));

        assertTrue(store.isEncrypting());
        assertFalse(Files.readString(file).contains("hunter2"));
        assertEquals("hunter2", store.read(file).orElseThrow().get("refreshToken").getAsString());
    }

    @Test
    void reportsNoSessionWhenThereIsNoFile() {
        assertEquals(Optional.empty(), new TokenStore(null).read(directory.resolve("absent.json")));
    }

    /**
     * The important one. Reporting a wrong key as "no stored session" sends the operator to
     * {@code chronit login}, and that login overwrites a session that was fine all along.
     */
    @Test
    void refusesToPassOffAnUndecryptableFileAsAMissingOne() throws IOException {
        Path file = directory.resolve("main.json");
        new TokenStore("the-original-key").write(file, session("hunter2"));

        TokenStoreException error = assertThrows(TokenStoreException.class,
                () -> new TokenStore("a-different-key").read(file));

        assertTrue(error.getMessage().contains("CHRONIT_SECRET_KEY"), error.getMessage());
    }

    @Test
    void refusesToReadAnEncryptedFileWithNoKeyAtAll() throws IOException {
        Path file = directory.resolve("main.json");
        new TokenStore("the-original-key").write(file, session("hunter2"));

        assertThrows(TokenStoreException.class, () -> new TokenStore(null).read(file));
    }

    @Test
    void refusesToReadAFileThatIsNotJson() throws IOException {
        Path file = directory.resolve("main.json");
        Files.writeString(file, "this is not a session");

        assertThrows(TokenStoreException.class, () -> new TokenStore(null).read(file));
    }

    /** A zero-length file is what a full disk leaves behind; there is nothing in it to lose. */
    @Test
    void treatsAnEmptyFileAsNoSession() throws IOException {
        Path file = directory.resolve("main.json");
        Files.writeString(file, "");

        assertEquals(Optional.empty(), new TokenStore(null).read(file));
    }

    @Test
    void replacesAnExistingSessionInPlace() throws IOException {
        TokenStore store = new TokenStore(null);
        Path file = directory.resolve("main.json");

        store.write(file, session("first"));
        store.write(file, session("second"));

        assertEquals("second", store.read(file).orElseThrow().get("refreshToken").getAsString());
        // The temporary file used for the atomic replace must not be left behind.
        try (var entries = Files.list(directory)) {
            assertEquals(1, entries.count());
        }
    }

    @Test
    void readsAPlainFileWrittenBeforeAKeyWasConfigured() throws IOException {
        Path file = directory.resolve("main.json");
        new TokenStore(null).write(file, session("hunter2"));

        TokenStore encrypting = new TokenStore("a-key-added-later");
        assertEquals("hunter2", encrypting.read(file).orElseThrow().get("refreshToken").getAsString());

        encrypting.write(file, session("hunter2"));
        assertFalse(Files.readString(file).contains("hunter2"));
    }

    private static JsonObject session(String refreshToken) {
        JsonObject json = new JsonObject();
        json.addProperty("refreshToken", refreshToken);
        return json;
    }
}
