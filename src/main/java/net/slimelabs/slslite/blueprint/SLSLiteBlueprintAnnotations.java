package net.slimelabs.slslite.blueprint;

import java.math.BigInteger;
import java.time.Duration;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalInt;

public final class SLSLiteBlueprintAnnotations {

  public static final String NAMESPACE = "sls-lite";
  public static final String KEEP_ALIVE = "keep-alive";
  public static final String STOP_WHEN_EMPTY = "stop-when-empty";
  public static final String IDLE_SHUTDOWN_SECONDS = "idle-shutdown-seconds";
  public static final String QUEUE_TIMEOUT_SECONDS = "queue-timeout-seconds";
  public static final String MAX_PLAYERS = "max-players";

  private SLSLiteBlueprintAnnotations() {}

  public static OptionalInt maxPlayers(Map<String, Object> annotations) {
    return positiveInteger(annotations, MAX_PLAYERS);
  }

  static boolean booleanValue(Map<String, Object> annotations, String key, boolean defaultValue) {
    Object value = value(annotations, key);
    if (value == null) {
      return defaultValue;
    }
    if (!(value instanceof Boolean result)) {
      throw new IllegalArgumentException(path(key) + " must be true or false");
    }
    return result;
  }

  static int integerValue(
      Map<String, Object> annotations, String key, int defaultValue, int minimum, int maximum) {
    return optionalInteger(annotations, key, minimum, maximum).orElse(defaultValue);
  }

  static OptionalInt optionalInteger(
      Map<String, Object> annotations, String key, int minimum, int maximum) {
    Object value = value(annotations, key);
    if (value == null) {
      return OptionalInt.empty();
    }
    if (!(value instanceof Number number)
        || number.doubleValue() != number.longValue()
        || number.longValue() < minimum
        || number.longValue() > maximum) {
      throw new IllegalArgumentException(
          path(key) + " must be an integer from " + minimum + " through " + maximum);
    }
    return OptionalInt.of(number.intValue());
  }

  static Optional<Duration> duration(
      Map<String, Object> annotations, String key, int maximumSeconds) {
    Object value = value(annotations, key);
    if (value == null) {
      return Optional.empty();
    }
    if (!(value instanceof Number number)
        || number.doubleValue() != number.longValue()
        || number.longValue() <= 0
        || number.longValue() > maximumSeconds) {
      throw new IllegalArgumentException(
          path(key) + " must be an integer between 1 and " + maximumSeconds);
    }
    return Optional.of(Duration.ofSeconds(number.longValue()));
  }

  private static Object value(Map<String, Object> annotations, String key) {
    Object flattened = annotations.get(NAMESPACE + "." + key);
    if (flattened != null) {
      return flattened;
    }
    return namespace(annotations).get(key);
  }

  private static Map<?, ?> namespace(Map<String, Object> annotations) {
    Object value = annotations.get(NAMESPACE);
    if (value == null) {
      return Map.of();
    }
    if (!(value instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException("annotations.sls-lite must be an object");
    }
    return map;
  }

  private static OptionalInt positiveInteger(Map<String, Object> annotations, String key) {
    Object value = value(annotations, key);
    if (value == null) {
      return OptionalInt.empty();
    }
    BigInteger parsed;
    if (value instanceof Byte
        || value instanceof Short
        || value instanceof Integer
        || value instanceof Long) {
      parsed = BigInteger.valueOf(((Number) value).longValue());
    } else if (value instanceof BigInteger integer) {
      parsed = integer;
    } else {
      throw new IllegalArgumentException(path(key) + " must be a positive integer");
    }
    if (parsed.signum() <= 0 || parsed.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) > 0) {
      throw new IllegalArgumentException(path(key) + " must be between 1 and " + Integer.MAX_VALUE);
    }
    return OptionalInt.of(parsed.intValue());
  }

  private static String path(String key) {
    return "annotations." + NAMESPACE + "." + key;
  }
}
