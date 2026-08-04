package net.slimelabs.slslite.api;

import java.util.List;
import java.util.Map;

/**
 * Immutable bounded annotation values owned by one extension namespace.
 *
 * @param namespace normalized extension namespace
 * @param values deeply immutable JSON-like annotation values
 */
public record NamespacedAnnotations(String namespace, Map<String, Object> values) {

  private static final int MAX_DEPTH = 16;
  private static final int MAX_COLLECTION_ENTRIES = 256;
  private static final int MAX_TOTAL_VALUES = 4_096;
  private static final int MAX_STRING_CHARACTERS = 4_096;

  public NamespacedAnnotations {
    namespace = validateNamespace(namespace);
    values = immutableValues(values);
  }

  static Map<String, Object> immutableValues(Map<String, Object> values) {
    if (java.util.Objects.requireNonNull(values, "values").size() > MAX_COLLECTION_ENTRIES) {
      throw new IllegalArgumentException("Annotation namespace exceeds 256 entries");
    }
    java.util.LinkedHashMap<String, Object> copied = new java.util.LinkedHashMap<>();
    int[] budget = {0};
    values.forEach((key, value) -> copied.put(validateKey(key), copy(value, 0, budget)));
    return java.util.Collections.unmodifiableMap(copied);
  }

  private static Object copy(Object value, int depth, int[] budget) {
    if (depth > MAX_DEPTH || ++budget[0] > MAX_TOTAL_VALUES) {
      throw new IllegalArgumentException("Annotation values exceed the public API bound");
    }
    if (value == null || value instanceof Boolean || immutableNumber(value)) {
      return value;
    }
    if (value instanceof String string) {
      if (string.length() > MAX_STRING_CHARACTERS) {
        throw new IllegalArgumentException("Annotation string exceeds 4096 characters");
      }
      return string;
    }
    if (value instanceof Map<?, ?> map) {
      if (map.size() > MAX_COLLECTION_ENTRIES) {
        throw new IllegalArgumentException("Annotation map exceeds 256 entries");
      }
      java.util.LinkedHashMap<String, Object> copied = new java.util.LinkedHashMap<>();
      map.forEach((key, nested) -> copied.put(validateKey(key), copy(nested, depth + 1, budget)));
      return java.util.Collections.unmodifiableMap(copied);
    }
    if (value instanceof List<?> list) {
      if (list.size() > MAX_COLLECTION_ENTRIES) {
        throw new IllegalArgumentException("Annotation list exceeds 256 entries");
      }
      return list.stream().map(item -> copy(item, depth + 1, budget)).toList();
    }
    throw new IllegalArgumentException(
        "Unsupported public annotation value type: " + value.getClass().getSimpleName());
  }

  private static boolean immutableNumber(Object value) {
    return value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long
        || value instanceof Float
        || value instanceof Double
        || value instanceof java.math.BigInteger
        || value instanceof java.math.BigDecimal;
  }

  private static String validateNamespace(String value) {
    if (value == null) {
      throw new IllegalArgumentException("namespace must not be null");
    }
    String normalized = value.strip().toLowerCase(java.util.Locale.ROOT);
    if (!normalized.matches("[a-z][a-z0-9._-]{0,63}")) {
      throw new IllegalArgumentException("Invalid extension annotation namespace");
    }
    return normalized;
  }

  private static String validateKey(Object value) {
    if (!(value instanceof String key) || key.isBlank() || key.length() > 128) {
      throw new IllegalArgumentException(
          "Annotation keys must be non-blank strings of 128 characters or fewer");
    }
    return key;
  }
}
