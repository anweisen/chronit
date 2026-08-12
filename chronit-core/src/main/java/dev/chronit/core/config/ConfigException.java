package dev.chronit.core.config;

import java.util.List;

/** Configuration could not be read or is invalid. Carries every problem found, not just the first. */
public class ConfigException extends RuntimeException {

    private final List<String> problems;

    public ConfigException(String message) {
        this(message, List.of());
    }

    public ConfigException(String message, Throwable cause) {
        super(message, cause);
        this.problems = List.of();
    }

    public ConfigException(String message, List<String> problems) {
        super(problems.isEmpty() ? message : message + "\n  - " + String.join("\n  - ", problems));
        this.problems = List.copyOf(problems);
    }

    public List<String> problems() {
        return problems;
    }
}
