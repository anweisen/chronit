package net.anweisen.chronit.core.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Substitutes {@code ${ENV}} and {@code {{secrets.key}}} placeholders in string values.
 *
 * <p>Runs over the parsed YAML tree rather than the raw text, so a substituted value containing
 * a colon or a newline cannot change the document structure.
 */
public final class Interpolator {

  /** {@code ${NAME}} or {@code ${NAME:-fallback}}. */
  private static final Pattern ENV = Pattern.compile("\\$\\{([A-Za-z_][A-Za-z0-9_]*)(?::-([^}]*))?}");

  /** {@code {{secrets.name}}}. */
  private static final Pattern SECRET = Pattern.compile("\\{\\{\\s*secrets\\.([A-Za-z0-9_.-]+)\\s*}}");

  private Interpolator() {
  }

  /**
   * Replaces environment placeholders throughout the tree.
   *
   * @param unresolved collects names that had neither a value nor a fallback
   */
  public static JsonNode resolveEnv(JsonNode root, Map<String, String> environment, List<String> unresolved) {
    return walk(root, text -> replace(ENV, text, matcher -> {
      String name = matcher.group(1);
      String fallback = matcher.group(2);
      String value = environment.get(name);
      if (value != null) {
        return value;
      }
      if (fallback != null) {
        return fallback;
      }
      unresolved.add(name);
      return matcher.group(0);
    }));
  }

  /**
   * Replaces secret placeholders throughout the tree.
   *
   * @param unresolved collects names missing from {@code secrets}
   */
  public static JsonNode resolveSecrets(JsonNode root, Map<String, String> secrets, List<String> unresolved) {
    return walk(root, text -> replace(SECRET, text, matcher -> {
      String name = matcher.group(1);
      String value = secrets.get(name);
      if (value == null) {
        unresolved.add(name);
        return matcher.group(0);
      }
      return value;
    }));
  }

  /** Names of the secrets referenced anywhere in the tree. */
  public static List<String> referencedSecrets(JsonNode root) {
    List<String> names = new ArrayList<>();
    walk(root, text -> {
      Matcher matcher = SECRET.matcher(text);
      while (matcher.find()) {
        names.add(matcher.group(1));
      }
      return text;
    });
    return names;
  }

  private static String replace(Pattern pattern, String text, Function<Matcher, String> replacer) {
    if (text.indexOf('$') < 0 && text.indexOf('{') < 0) {
      return text;
    }
    Matcher matcher = pattern.matcher(text);
    StringBuilder out = new StringBuilder();
    while (matcher.find()) {
      matcher.appendReplacement(out, Matcher.quoteReplacement(replacer.apply(matcher)));
    }
    matcher.appendTail(out);
    return out.toString();
  }

  private static JsonNode walk(JsonNode node, Function<String, String> transform) {
    if (node instanceof ObjectNode object) {
      // Snapshot the names first: set() during iteration would otherwise mutate the map
      // being walked.
      List<String> fields = object.properties().stream().map(Map.Entry::getKey).toList();
      for (String field : fields) {
        object.set(field, walk(object.get(field), transform));
      }
      return object;
    }
    if (node instanceof ArrayNode array) {
      for (int i = 0; i < array.size(); i++) {
        array.set(i, walk(array.get(i), transform));
      }
      return array;
    }
    if (node instanceof TextNode text) {
      String original = text.textValue();
      String replaced = transform.apply(original);
      return replaced.equals(original) ? text : TextNode.valueOf(replaced);
    }
    return node;
  }
}
