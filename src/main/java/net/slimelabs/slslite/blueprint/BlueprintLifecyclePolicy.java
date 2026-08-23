package net.slimelabs.slslite.blueprint;

import java.time.Duration;
import java.util.Map;

public record BlueprintLifecyclePolicy(boolean keepAlive, Duration idleTimeout) {

  public static final String NAMESPACE = SLSLiteBlueprintAnnotations.NAMESPACE;
  public static final String KEEP_ALIVE = SLSLiteBlueprintAnnotations.KEEP_ALIVE;
  public static final String STOP_WHEN_EMPTY = SLSLiteBlueprintAnnotations.STOP_WHEN_EMPTY;
  public static final String IDLE_SHUTDOWN_SECONDS =
      SLSLiteBlueprintAnnotations.IDLE_SHUTDOWN_SECONDS;

  public BlueprintLifecyclePolicy {
    if (idleTimeout == null || idleTimeout.isNegative()) {
      throw new IllegalArgumentException("idleTimeout must not be negative");
    }
  }

  public static BlueprintLifecyclePolicy from(Blueprint blueprint, int defaultIdleShutdownSeconds) {
    Map<String, Object> annotations = blueprint.annotations();
    boolean keepAlive = SLSLiteBlueprintAnnotations.booleanValue(annotations, KEEP_ALIVE, false);
    boolean stopWhenEmpty =
        SLSLiteBlueprintAnnotations.booleanValue(annotations, STOP_WHEN_EMPTY, true);
    int idleSeconds =
        SLSLiteBlueprintAnnotations.integerValue(
            annotations, IDLE_SHUTDOWN_SECONDS, defaultIdleShutdownSeconds, 0, Integer.MAX_VALUE);
    return new BlueprintLifecyclePolicy(
        blueprint.save()
            || keepAlive
            || !stopWhenEmpty
            || VSLSBlueprintAnnotations.dontStopWhenEmpty(annotations)
            || idleSeconds == 0,
        Duration.ofSeconds(idleSeconds));
  }
}
