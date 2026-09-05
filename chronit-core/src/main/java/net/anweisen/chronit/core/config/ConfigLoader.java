package net.anweisen.chronit.core.config;

import com.fasterxml.jackson.core.JsonParser;
import com.fasterxml.jackson.databind.DeserializationContext;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.deser.std.StdDeserializer;
import com.fasterxml.jackson.databind.module.SimpleModule;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import net.anweisen.chronit.core.util.Durations;
import net.anweisen.chronit.core.util.Redactor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Reads {@code chronit.yml}, substitutes placeholders, and validates the result.
 *
 * <p>Unknown properties are an error rather than being ignored: a mistyped key in a scheduling tool
 * fails silently at 3am otherwise.
 */
public final class ConfigLoader {

  private static final Logger log = LoggerFactory.getLogger(ConfigLoader.class);

  /** Environment variables with this prefix become secrets, lowercased and with the prefix removed. */
  private static final String SECRET_ENV_PREFIX = "CHRONIT_SECRET_";

  private final Map<String, String> environment;

  public ConfigLoader() {
    this(System.getenv());
  }

  /** Visible for tests, which supply a fixed environment. */
  public ConfigLoader(Map<String, String> environment) {
    this.environment = environment;
  }

  public static ObjectMapper mapper() {
    ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
    mapper.registerModule(new JavaTimeModule());
    mapper.registerModule(durationModule());
    mapper.enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    mapper.enable(DeserializationFeature.ACCEPT_SINGLE_VALUE_AS_ARRAY);
    mapper.enable(DeserializationFeature.READ_ENUMS_USING_TO_STRING);
    return mapper;
  }

  /**
   * Accepts the compact duration forms as well as ISO-8601, so a config can say {@code 30m}
   * instead of {@code PT30M}.
   */
  private static SimpleModule durationModule() {
    SimpleModule module = new SimpleModule();
    module.addDeserializer(Duration.class, new StdDeserializer<>(Duration.class) {
      @Override
      public Duration deserialize(JsonParser parser, DeserializationContext context) throws IOException {
        String text = parser.getValueAsString();
        try {
          return Durations.parse(text);
        } catch (IllegalArgumentException | java.time.format.DateTimeParseException e) {
          throw new IOException(
              "'" + text + "' is not a duration (try 30s, 5m, 1h30m or PT30M)", e);
        }
      }
    });
    return module;
  }

  public ChronitConfig load(Path file) {
    if (!Files.isReadable(file)) {
      throw new ConfigException("Configuration file not found or unreadable: " + file);
    }

    ObjectMapper mapper = mapper();
    JsonNode root;
    try {
      root = mapper.readTree(Files.readString(file));
    } catch (IOException e) {
      throw new ConfigException("Could not parse " + file + ": " + e.getMessage(), e);
    }
    if (root == null || root.isNull() || root.isMissingNode()) {
      throw new ConfigException("Configuration file is empty: " + file);
    }

    List<String> missingEnv = new ArrayList<>();
    root = Interpolator.resolveEnv(root, environment, missingEnv);
    if (!missingEnv.isEmpty()) {
      throw new ConfigException(
          "Unset environment variables referenced in " + file,
          missingEnv.stream()
              .distinct()
              .map(name -> "${" + name + "} — set it, or give a fallback as ${"
                  + name + ":-value}")
              .toList());
    }

    // Secrets are only looked up when the config actually references some, so a missing
    // secrets file is not an error for configs that do not use one.
    List<String> referenced = Interpolator.referencedSecrets(root);
    if (!referenced.isEmpty()) {
      Map<String, String> secrets = loadSecrets(secretsFileOf(root, file), mapper);
      List<String> missingSecrets = new ArrayList<>();
      root = Interpolator.resolveSecrets(root, secrets, missingSecrets);
      if (!missingSecrets.isEmpty()) {
        throw new ConfigException(
            "Secrets referenced but not defined",
            missingSecrets.stream()
                .distinct()
                .map(name -> "{{secrets." + name + "}} — add it to the secrets file, "
                    + "or set " + SECRET_ENV_PREFIX + name.toUpperCase(Locale.ROOT))
                .toList());
      }
      secrets.values().forEach(Redactor::register);
    }

    ChronitConfig config;
    try {
      config = mapper.treeToValue(root, ChronitConfig.class);
    } catch (IOException e) {
      throw new ConfigException("Invalid configuration in " + file + ": " + rootMessage(e), e);
    }

    ConfigValidator.validate(config);
    log.debug("Loaded configuration from {}: {} account(s), {} server(s), {} job(s)",
        file, config.accountsOrEmpty().size(), config.serversOrEmpty().size(),
        config.jobsOrEmpty().size());
    return config;
  }

  private Path secretsFileOf(JsonNode root, Path configFile) {
    JsonNode node = root.get("secretsFile");
    if (node == null || node.isNull()) {
      return null;
    }
    Path path = Path.of(node.asText());
    return path.isAbsolute() || configFile.getParent() == null
        ? path
        : configFile.getParent().resolve(path);
  }

  private Map<String, String> loadSecrets(Path secretsFile, ObjectMapper mapper) {
    Map<String, String> secrets = new LinkedHashMap<>();

    if (secretsFile != null) {
      if (!Files.isReadable(secretsFile)) {
        throw new ConfigException("secretsFile not found or unreadable: " + secretsFile);
      }
      try {
        JsonNode node = mapper.readTree(Files.readString(secretsFile));
        if (node != null && node.isObject()) {
          node.properties().forEach(entry ->
              secrets.put(entry.getKey(), entry.getValue().asText()));
        }
      } catch (IOException e) {
        throw new ConfigException("Could not parse secretsFile " + secretsFile + ": " + e.getMessage(), e);
      }
    }

    // Environment wins, so a compose file or orchestrator secret can override the file.
    environment.forEach((key, value) -> {
      if (key.startsWith(SECRET_ENV_PREFIX)) {
        secrets.put(key.substring(SECRET_ENV_PREFIX.length()).toLowerCase(Locale.ROOT), value);
      }
    });
    return secrets;
  }

  /** Jackson wraps the useful message several causes deep; surface the innermost one. */
  private static String rootMessage(Throwable error) {
    Throwable current = error;
    while (current.getCause() != null && current.getCause() != current) {
      current = current.getCause();
    }
    String message = current.getMessage();
    return message != null ? message : error.toString();
  }

  /** Convenience for tests and tools that already hold the YAML text. */
  public ChronitConfig loadString(String yaml) {
    try {
      Path temp = Files.createTempFile("chronit-config", ".yml");
      try {
        Files.writeString(temp, yaml);
        return load(temp);
      } finally {
        Files.deleteIfExists(temp);
      }
    } catch (IOException e) {
      throw new UncheckedIOException(e);
    }
  }
}
