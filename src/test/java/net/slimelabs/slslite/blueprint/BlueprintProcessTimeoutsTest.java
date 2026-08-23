package net.slimelabs.slslite.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

class BlueprintProcessTimeoutsTest {

  @Test
  void parsesBoundedPerBlueprintTimeouts() {
    BlueprintProcessTimeouts timeouts =
        BlueprintProcessTimeouts.fromAnnotations(
            Map.of("sls-lite", Map.of("startup-timeout-seconds", 240, "stop-timeout-seconds", 45)));

    assertEquals(Duration.ofSeconds(240), timeouts.startupTimeout().orElseThrow());
    assertEquals(Duration.ofSeconds(45), timeouts.stopTimeout().orElseThrow());
  }

  @Test
  void sharesFlattenedAnnotationCompatibilityWithOtherLocalPolicies() {
    BlueprintProcessTimeouts timeouts =
        BlueprintProcessTimeouts.fromAnnotations(Map.of("sls-lite.startup-timeout-seconds", 240));

    assertEquals(Duration.ofSeconds(240), timeouts.startupTimeout().orElseThrow());
  }

  @Test
  void rejectsZeroFractionalAndExcessiveTimeouts() {
    assertInvalid("startup-timeout-seconds", 0);
    assertInvalid("startup-timeout-seconds", 3601);
    assertInvalid("stop-timeout-seconds", 0);
    assertInvalid("stop-timeout-seconds", 601);
    assertInvalid("stop-timeout-seconds", 2.5);
  }

  private static void assertInvalid(String key, Object value) {
    assertThrows(
        IllegalArgumentException.class,
        () -> BlueprintProcessTimeouts.fromAnnotations(Map.of("sls-lite", Map.of(key, value))));
  }
}
