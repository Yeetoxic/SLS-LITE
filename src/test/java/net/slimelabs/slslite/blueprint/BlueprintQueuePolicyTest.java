package net.slimelabs.slslite.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BlueprintQueuePolicyTest {

  @Test
  void inheritsHostDefaultWhenAnnotationIsAbsent() {
    BlueprintQueuePolicy policy =
        BlueprintQueuePolicy.from(blueprint(Map.of()), Duration.ofSeconds(180));

    assertEquals(Duration.ofSeconds(180), policy.timeout());
    assertTrue(policy.expires());
  }

  @Test
  void supportsOverrideAndExplicitNoExpiry() {
    assertEquals(
        Duration.ofSeconds(45),
        BlueprintQueuePolicy.from(
                blueprint(Map.of("sls-lite.queue-timeout-seconds", 45)), Duration.ofSeconds(180))
            .timeout());
    assertFalse(
        BlueprintQueuePolicy.from(
                blueprint(Map.of("sls-lite", Map.of("queue-timeout-seconds", 0))),
                Duration.ofSeconds(180))
            .expires());
  }

  @Test
  void rejectsMalformedOverride() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            BlueprintQueuePolicy.from(
                blueprint(Map.of("sls-lite.queue-timeout-seconds", -1)), Duration.ofSeconds(180)));
  }

  private static Blueprint blueprint(Map<String, Object> annotations) {
    return new Blueprint("arena", "Arena", "minigame", "paper", "1.21.11", 512, false, annotations);
  }
}
