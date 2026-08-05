package net.slimelabs.slslite.blueprint;

import java.time.Duration;
import java.util.Map;

public record BlueprintQueuePolicy(Duration timeout) {

  public static final String NAMESPACE = "sls-lite";
  public static final String QUEUE_TIMEOUT_SECONDS = "queue-timeout-seconds";

  public BlueprintQueuePolicy {
    if (timeout == null || timeout.isNegative()) {
      throw new IllegalArgumentException("queue timeout must not be negative");
    }
  }

  public static BlueprintQueuePolicy from(Blueprint blueprint, Duration hostDefault) {
    java.util.Objects.requireNonNull(blueprint, "blueprint");
    java.util.Objects.requireNonNull(hostDefault, "hostDefault");
    if (hostDefault.isZero() || hostDefault.isNegative()) {
      throw new IllegalArgumentException("host queue timeout must be positive");
    }
    Object value = annotation(blueprint.annotations());
    if (value == null) {
      return new BlueprintQueuePolicy(hostDefault);
    }
    if (!(value instanceof Number number)
        || number.longValue() < 0
        || number.longValue() > Integer.MAX_VALUE
        || number.doubleValue() != number.longValue()) {
      throw new IllegalArgumentException(
          "annotation 'sls-lite.queue-timeout-seconds' must be a non-negative integer");
    }
    return new BlueprintQueuePolicy(Duration.ofSeconds(number.longValue()));
  }

  public boolean expires() {
    return !timeout.isZero();
  }

  private static Object annotation(Map<String, Object> annotations) {
    Object flattened = annotations.get(NAMESPACE + "." + QUEUE_TIMEOUT_SECONDS);
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
    return values.get(QUEUE_TIMEOUT_SECONDS);
  }
}
