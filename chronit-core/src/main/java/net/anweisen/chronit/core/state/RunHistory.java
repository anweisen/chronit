package net.anweisen.chronit.core.state;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;

/**
 * Append-only record of what ran and how it went.
 *
 * <p>One JSON object per line: appending cannot corrupt earlier entries, the file stays readable
 * with ordinary shell tools, and tailing it works. A bounded window is kept in memory for the web
 * interface so rendering the dashboard does not mean re-reading the whole file.
 */
public final class RunHistory {

    private static final Logger log = LoggerFactory.getLogger(RunHistory.class);

    private static final int IN_MEMORY_LIMIT = 200;

    private final Path file;
    private final ObjectMapper mapper;
    private final Deque<RunRecord> recent = new ArrayDeque<>();

    public RunHistory(Path stateDir) {
        this.file = stateDir.resolve("state").resolve("history.jsonl");
        this.mapper = new ObjectMapper()
                .registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        loadRecent();
    }

    public synchronized void append(RunRecord record) {
        recent.addLast(record);
        while (recent.size() > IN_MEMORY_LIMIT) {
            recent.removeFirst();
        }
        try {
            Path parent = file.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
            }
            String line = mapper.writeValueAsString(record) + System.lineSeparator();
            Files.writeString(file, line, StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException e) {
            // History is diagnostics, not correctness — never let it fail a run.
            log.warn("Could not append to the run history at {}: {}", file, e.toString());
        }
    }

    /** Most recent first. */
    public synchronized List<RunRecord> recent(int limit) {
        return recent.stream()
                .collect(java.util.stream.Collectors.collectingAndThen(
                        java.util.stream.Collectors.toList(),
                        list -> {
                            java.util.Collections.reverse(list);
                            return list.stream().limit(limit).toList();
                        }));
    }

    public Path file() {
        return file;
    }

    private void loadRecent() {
        if (!Files.isReadable(file)) {
            return;
        }
        try (var lines = Files.lines(file, StandardCharsets.UTF_8)) {
            lines.forEach(line -> {
                if (line.isBlank()) {
                    return;
                }
                try {
                    recent.addLast(mapper.readValue(line, RunRecord.class));
                    if (recent.size() > IN_MEMORY_LIMIT) {
                        recent.removeFirst();
                    }
                } catch (IOException e) {
                    // A partially written final line from a hard kill; skipping it is correct.
                    log.debug("Skipping unreadable history line: {}", e.toString());
                }
            });
        } catch (IOException e) {
            log.warn("Could not read the run history at {}: {}", file, e.toString());
        }
    }
}
