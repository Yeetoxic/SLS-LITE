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
    return new BlueprintProcessTimeouts(
        SLSLiteBlueprintAnnotations.duration(
            configured, "startup-timeout-seconds", MAXIMUM_STARTUP_SECONDS),
        SLSLiteBlueprintAnnotations.duration(
            configured, "stop-timeout-seconds", MAXIMUM_STOP_SECONDS));
  }
}
