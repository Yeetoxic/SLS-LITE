package net.slimelabs.slslite.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Duration;
import java.util.Map;
import org.junit.jupiter.api.Test;

final class BlueprintCrashRecoveryPolicyTest {

  @Test
  void parsesBoundedPolicyAndCapsExponentialBackoff() {
    BlueprintCrashRecoveryPolicy policy =
        BlueprintCrashRecoveryPolicy.from(
            blueprint(
                Map.of(
                    "sls-lite",
                    Map.of(
                        "restart-on-crash", true,
                        "restart-max-attempts", 4,
                        "restart-initial-backoff-seconds", 2,
                        "restart-max-backoff-seconds", 5,
                        "restart-stable-after-seconds", 30))));

    assertEquals(true, policy.enabled());
    assertEquals(4, policy.maxAttempts());
    assertEquals(Duration.ofSeconds(2), policy.backoff(1));
    assertEquals(Duration.ofSeconds(4), policy.backoff(2));
    assertEquals(Duration.ofSeconds(5), policy.backoff(3));
    assertEquals(Duration.ofSeconds(5), policy.backoff(4));
  }

  @Test
  void rejectsUnboundedOrInvertedPolicyValues() {
    assertThrows(
        IllegalArgumentException.class,
        () ->
            BlueprintCrashRecoveryPolicy.from(
                blueprint(
                    Map.of(
                        "sls-lite.restart-on-crash", true, "sls-lite.restart-max-attempts", 101))));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            BlueprintCrashRecoveryPolicy.from(
                blueprint(
                    Map.of(
                        "sls-lite.restart-on-crash", true,
                        "sls-lite.restart-initial-backoff-seconds", 10,
                        "sls-lite.restart-max-backoff-seconds", 5))));
  }

  private static Blueprint blueprint(Map<String, Object> annotations) {
    return new Blueprint("fixture", "Fixture", "test", "paper", "1.0", 256, true, annotations);
  }
}
