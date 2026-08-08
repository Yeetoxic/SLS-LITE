package net.slimelabs.slslite.config;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class YamlValues {

  private YamlValues() {}

  public static Map<String, Object> asMap(Object value, String key, Path path)
      throws ConfigurationException {
    if (!(value instanceof Map<?, ?> rawMap)) {
      throw error(path, "'" + key + "' must be an object");
    }

    Map<String, Object> result = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
      if (!(entry.getKey() instanceof String stringKey)) {
        throw error(path, "'" + key + "' contains a non-string key");
      }
      result.put(stringKey, entry.getValue());
    }
    return result;
  }

  public static Map<String, Object> optionalMap(Map<String, Object> parent, String key, Path path)
      throws ConfigurationException {
    return optionalMap(parent, key, "", path);
  }

  public static Map<String, Object> optionalMap(
      Map<String, Object> parent, String key, String section, Path path)
      throws ConfigurationException {
    if (!parent.containsKey(key)) {
      return Map.of();
    }
    String qualified = section.isBlank() ? key : section + "." + key;
    return asMap(parent.get(key), qualified, path);
  }

  public static String requiredString(Map<String, Object> values, String key, Path path)
      throws ConfigurationException {
    Object value = values.get(key);
    if (value == null || value.toString().isBlank()) {
      throw error(path, "missing required value '" + key + "'");
    }
    return value.toString().trim();
  }

  public static String optionalString(
      Map<String, Object> values, String key, String defaultValue, Path path)
      throws ConfigurationException {
    Object value = values.get(key);
    if (value == null) {
      return defaultValue;
    }
    if (!(value instanceof String stringValue) || stringValue.isBlank()) {
      throw error(path, "'" + key + "' must be a non-blank string");
    }
    return stringValue.trim();
  }

  public static int optionalPositiveInt(
      Map<String, Object> values, String key, int defaultValue, Path path)
      throws ConfigurationException {
    Object value = values.get(key);
    if (value == null) {
      return defaultValue;
    }
    if (!(value instanceof Number number)
        || number.intValue() <= 0
        || number.doubleValue() != number.intValue()) {
      throw error(path, "'" + key + "' must be a positive integer");
    }
    return number.intValue();
  }

  public static int optionalNonNegativeInt(
      Map<String, Object> values, String key, int defaultValue, Path path)
      throws ConfigurationException {
    Object value = values.get(key);
    if (value == null) {
      return defaultValue;
    }
    if (!(value instanceof Number number)
        || number.intValue() < 0
        || number.doubleValue() != number.intValue()) {
      throw error(path, "'" + key + "' must be a non-negative integer");
    }
    return number.intValue();
  }

  public static int optionalMinusOneOrPositiveInt(
      Map<String, Object> values, String key, int defaultValue, Path path)
      throws ConfigurationException {
    Object value = values.get(key);
    if (value == null) {
      return defaultValue;
    }
    if (!(value instanceof Number number)
        || number.doubleValue() != number.intValue()
        || (number.intValue() != -1 && number.intValue() <= 0)) {
      throw error(path, "'" + key + "' must be -1 or a positive protocol number");
    }
    return number.intValue();
  }

  public static boolean optionalBoolean(
      Map<String, Object> values, String key, boolean defaultValue, Path path)
      throws ConfigurationException {
    return optionalBoolean(values, key, "", defaultValue, path);
  }

  public static boolean optionalBoolean(
      Map<String, Object> values, String key, String section, boolean defaultValue, Path path)
      throws ConfigurationException {
    Object value = values.get(key);
    if (value == null) {
      return defaultValue;
    }
    if (!(value instanceof Boolean booleanValue)) {
      String qualified = section == null || section.isBlank() ? key : section + "." + key;
      throw error(path, "'" + qualified + "' must be true or false");
    }
    return booleanValue;
  }

  public static List<String> optionalStringList(
      Map<String, Object> values, String key, List<String> defaultValue, Path path)
      throws ConfigurationException {
    Object value = values.get(key);
    if (value == null) {
      return List.copyOf(defaultValue);
    }
    if (!(value instanceof List<?> rawList)) {
      throw error(path, "'" + key + "' must be a list of strings");
    }

    List<String> result = new ArrayList<>();
    for (Object item : rawList) {
      if (!(item instanceof String stringItem) || stringItem.isBlank()) {
        throw error(path, "'" + key + "' must contain only non-blank strings");
      }
      result.add(stringItem);
    }
    return List.copyOf(result);
  }

  public static void requireOnlyKeys(
      Map<String, Object> values, String section, Path path, String... allowedKeys)
      throws ConfigurationException {
    Set<String> allowed = Set.of(allowedKeys);
    for (String key : values.keySet()) {
      if (!allowed.contains(key)) {
        throw error(path, unknownKeyMessage(section, key, allowed));
      }
    }
  }

  public static String unknownKeyMessage(String section, String key, Set<String> allowed) {
    String qualified = section.isBlank() ? key : section + "." + key;
    String nearest = null;
    int nearestDistance = Integer.MAX_VALUE;
    for (String candidate : allowed) {
      int distance = editDistance(key, candidate);
      if (distance < nearestDistance) {
        nearest = candidate;
        nearestDistance = distance;
      }
    }
    String message = "unknown key '" + qualified + "'";
    if (nearest != null && nearestDistance <= Math.max(2, key.length() / 3)) {
      String qualifiedNearest = section.isBlank() ? nearest : section + "." + nearest;
      message += "; did you mean '" + qualifiedNearest + "'?";
    }
    return message;
  }

  private static int editDistance(String left, String right) {
    int[] previous = new int[right.length() + 1];
    for (int index = 0; index <= right.length(); index++) {
      previous[index] = index;
    }
    for (int leftIndex = 1; leftIndex <= left.length(); leftIndex++) {
      int[] current = new int[right.length() + 1];
      current[0] = leftIndex;
      for (int rightIndex = 1; rightIndex <= right.length(); rightIndex++) {
        int substitution =
            previous[rightIndex - 1]
                + (left.charAt(leftIndex - 1) == right.charAt(rightIndex - 1) ? 0 : 1);
        current[rightIndex] =
            Math.min(Math.min(current[rightIndex - 1] + 1, previous[rightIndex] + 1), substitution);
      }
      previous = current;
    }
    return previous[right.length()];
  }

  public static ConfigurationException error(Path path, String message) {
    return new ConfigurationException(path + ": " + message);
  }
}
