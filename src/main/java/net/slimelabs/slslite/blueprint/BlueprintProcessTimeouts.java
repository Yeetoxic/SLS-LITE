package net.slimelabs.slslite.blueprint;

import java.time.Duration;
import java.util.Map;
import java.util.Optional;

public record BlueprintProcessTimeouts(
    Optional<Duration> startupTimeout, Optional<Duration> stopTimeout) {

  private static final int MAXIMUM_STARTUP_SECONDS = 3600;
  private static final int MAXIMUM_STOP_SECONDS = 600;

  public static BlueprintProcessTimeouts from(Blueprint blueprint) {
    java.util.Objects.requireNonNull(blueprint, "blueprint");
    return fromAnnotations(blueprint.annotations());
  }

  public BlueprintProcessTimeouts {
    startupTimeout = java.util.Objects.requireNonNull(startupTimeout, "startupTimeout");
    stopTimeout = java.util.Objects.requireNonNull(stopTimeout, "stopTimeout");
  }

  static BlueprintProcessTimeouts fromAnnotations(Map<String, Object> configured) {
    Map<?, ?> annotations = namespace(configured);
    return new BlueprintProcessTimeouts(
        duration(
            annotations.get("startup-timeout-seconds"),
            "annotations.sls-lite.startup-timeout-seconds",
            MAXIMUM_STARTUP_SECONDS),
        duration(
            annotations.get("stop-timeout-seconds"),
            "annotations.sls-lite.stop-timeout-seconds",
            MAXIMUM_STOP_SECONDS));
  }

  private static Map<?, ?> namespace(Map<String, Object> annotations) {
    Object configured = annotations.get("sls-lite");
    if (configured == null) {
      return Map.of();
    }
    if (!(configured instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException("annotations.sls-lite must be an object");
    }
    return map;
  }

  private static Optional<Duration> duration(Object configured, String path, int maximumSeconds) {
    if (configured == null) {
      return Optional.empty();
    }
    if (!(configured instanceof Number number)
        || number.longValue() <= 0
        || number.longValue() > maximumSeconds
        || number.doubleValue() != number.longValue()) {
      throw new IllegalArgumentException(
          path + " must be an integer between 1 and " + maximumSeconds);
    }
    return Optional.of(Duration.ofSeconds(number.longValue()));
  }
}
