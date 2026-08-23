package net.slimelabs.slslite.blueprint;

import java.time.Duration;

public record BlueprintQueuePolicy(Duration timeout) {

  public static final String NAMESPACE = SLSLiteBlueprintAnnotations.NAMESPACE;
  public static final String QUEUE_TIMEOUT_SECONDS =
      SLSLiteBlueprintAnnotations.QUEUE_TIMEOUT_SECONDS;

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
    var seconds =
        SLSLiteBlueprintAnnotations.optionalInteger(
            blueprint.annotations(), QUEUE_TIMEOUT_SECONDS, 0, Integer.MAX_VALUE);
    return seconds.isPresent()
        ? new BlueprintQueuePolicy(Duration.ofSeconds(seconds.getAsInt()))
        : new BlueprintQueuePolicy(hostDefault);
  }

  public boolean expires() {
    return !timeout.isZero();
  }
}
