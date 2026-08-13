package net.anweisen.chronit.core.util;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class RedactorTest {

    @AfterEach
    void reset() {
        Redactor.clear();
    }

    @Test
    void masksRegisteredSecretsAnywhereInTheText() {
        Redactor.register("hunter2hunter2");
        assertEquals("Sending /login ***", Redactor.redact("Sending /login hunter2hunter2"));
        assertEquals("*** and *** again", Redactor.redact("hunter2hunter2 and hunter2hunter2 again"));
    }

    @Test
    void leavesUnrelatedTextAlone() {
        Redactor.register("hunter2hunter2");
        assertEquals("nothing to see", Redactor.redact("nothing to see"));
    }

    @Test
    void ignoresValuesTooShortToBeWorthMasking() {
        // Masking a two-character value would replace it everywhere it happens to appear and make
        // the log unreadable without protecting anything meaningful.
        Redactor.register("ab");
        assertEquals("a table of abbreviations", Redactor.redact("a table of abbreviations"));
    }

    @Test
    void toleratesNullAndEmpty() {
        Redactor.register(null);
        Redactor.register("");
        assertEquals(0, Redactor.size());
        assertEquals(null, Redactor.redact(null));
        assertEquals("", Redactor.redact(""));
    }

    @Test
    void masksSecretsEmbeddedInExceptionText() {
        Redactor.register("s3cret-password");
        String message = "Command failed: /login s3cret-password (server said no)";
        assertFalse(Redactor.redact(message).contains("s3cret-password"));
    }
}
