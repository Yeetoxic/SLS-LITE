package net.slimelabs.slslite.blueprint;

import java.time.Duration;
import java.util.Map;

/** Opt-in bounded recovery policy for persistent non-lobby managed instances. */
public record BlueprintCrashRecoveryPolicy(
    boolean enabled,
    int maxAttempts,
    Duration initialBackoff,
    Duration maximumBackoff,
    Duration stableAfter) {

  private static final String NAMESPACE = "sls-lite";
  private static final int MAX_ATTEMPTS_LIMIT = 100;
  private static final int MAX_DELAY_SECONDS = 86_400;

  public BlueprintCrashRecoveryPolicy {
    if (maxAttempts < 0 || maxAttempts > MAX_ATTEMPTS_LIMIT) {
      throw new IllegalArgumentException("restart max attempts must be between 0 and 100");
    }
    requirePositiveBounded(initialBackoff, "restart initial backoff");
    requirePositiveBounded(maximumBackoff, "restart maximum backoff");
    requirePositiveBounded(stableAfter, "restart stable-after delay");
    if (maximumBackoff.compareTo(initialBackoff) < 0) {
      throw new IllegalArgumentException(
          "restart maximum backoff must not be less than initial backoff");
    }
    if (!enabled && maxAttempts != 0) {
      throw new IllegalArgumentException("disabled restart policy must have zero attempts");
    }
  }

  public static BlueprintCrashRecoveryPolicy from(Blueprint blueprint) {
    Map<String, Object> annotations = blueprint.annotations();
    boolean enabled = booleanValue(annotations, "restart-on-crash", false);
    int attempts = integerValue(annotations, "restart-max-attempts", 3, 0, MAX_ATTEMPTS_LIMIT);
    int initial =
        integerValue(annotations, "restart-initial-backoff-seconds", 5, 1, MAX_DELAY_SECONDS);
    int maximum =
        integerValue(annotations, "restart-max-backoff-seconds", 60, 1, MAX_DELAY_SECONDS);
    int stable =
        integerValue(annotations, "restart-stable-after-seconds", 120, 1, MAX_DELAY_SECONDS);
    return new BlueprintCrashRecoveryPolicy(
        enabled,
        enabled ? attempts : 0,
        Duration.ofSeconds(initial),
        Duration.ofSeconds(maximum),
        Duration.ofSeconds(stable));
  }

  public boolean exhausted(int attempts) {
    return attempts >= maxAttempts;
  }

  public Duration backoff(int attempt) {
    if (attempt <= 0) {
      throw new IllegalArgumentException("restart attempt must be positive");
    }
    long delay = initialBackoff.toSeconds();
    for (int index = 1; index < attempt; index++) {
      delay = Math.min(maximumBackoff.toSeconds(), Math.multiplyExact(delay, 2));
    }
    return Duration.ofSeconds(delay);
  }

  private static boolean booleanValue(
      Map<String, Object> annotations, String key, boolean defaultValue) {
    Object value = value(annotations, key);
    if (value == null) {
      return defaultValue;
    }
    if (!(value instanceof Boolean result)) {
      throw new IllegalArgumentException(
          "annotation '" + NAMESPACE + "." + key + "' must be true or false");
    }
    return result;
  }

  private static int integerValue(
      Map<String, Object> annotations, String key, int defaultValue, int minimum, int maximum) {
    Object value = value(annotations, key);
    if (value == null) {
      return defaultValue;
    }
    if (!(value instanceof Number number)
        || number.doubleValue() != number.intValue()
        || number.intValue() < minimum
        || number.intValue() > maximum) {
      throw new IllegalArgumentException(
          "annotation '"
              + NAMESPACE
              + "."
              + key
              + "' must be an integer from "
              + minimum
              + " through "
              + maximum);
    }
    return number.intValue();
  }

  private static Object value(Map<String, Object> annotations, String key) {
    Object flattened = annotations.get(NAMESPACE + "." + key);
    if (flattened != null) {
      return flattened;
    }
    Object namespace = annotations.get(NAMESPACE);
    if (namespace == null) {
      return null;
    }
    if (!(namespace instanceof Map<?, ?> values)) {
      throw new IllegalArgumentException(
          "annotation namespace '" + NAMESPACE + "' must be an object");
    }
    return values.get(key);
  }

  private static void requirePositiveBounded(Duration value, String label) {
    java.util.Objects.requireNonNull(value, label);
    if (value.isZero()
        || value.isNegative()
        || value.compareTo(Duration.ofSeconds(MAX_DELAY_SECONDS)) > 0) {
      throw new IllegalArgumentException(label + " must be between 1 and 86400 seconds");
    }
  }
}
