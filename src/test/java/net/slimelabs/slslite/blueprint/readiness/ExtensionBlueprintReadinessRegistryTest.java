package net.slimelabs.slslite.blueprint.readiness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import net.slimelabs.slslite.api.BlueprintReadinessStatus;
import net.slimelabs.slslite.api.BlueprintView;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

class ExtensionBlueprintReadinessRegistryTest {

  @Test
  void isolatesCheckerFailureToAnnotatedBlueprints() {
    ExtensionBlueprintReadinessRegistry registry =
        new ExtensionBlueprintReadinessRegistry(NOPLogger.NOP_LOGGER);
    registry.refreshViews(List.of(blueprint("annotated", true), blueprint("unrelated", false)));
    registry.register(
        "example-plugin",
        (blueprint, annotations) -> {
          throw new IllegalStateException("extension secret must not escape");
        });

    var finding = registry.findings("annotated").getFirst().finding();
    assertEquals(BlueprintReadinessStatus.TEMPORARILY_UNAVAILABLE, finding.status());
    assertEquals("checker-error", finding.code());
    assertTrue(finding.message().contains("IllegalStateException"));
    assertTrue(!finding.message().contains("extension secret"));
    assertTrue(registry.findings("unrelated").isEmpty());
    registry.close();
    assertThrows(
        IllegalStateException.class,
        () -> registry.register("late-plugin", (blueprint, annotations) -> List.of()));
  }

  @Test
  void boundsAStalledCheckerWithATemporaryFinding() {
    ExtensionBlueprintReadinessRegistry registry =
        new ExtensionBlueprintReadinessRegistry(NOPLogger.NOP_LOGGER);
    registry.refreshViews(List.of(blueprint("slow", true)));

    long started = System.nanoTime();
    registry.register(
        "example-plugin",
        (blueprint, annotations) -> {
          try {
            Thread.sleep(10_000L);
          } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
          }
          return List.of();
        });
    long elapsedMillis = java.time.Duration.ofNanos(System.nanoTime() - started).toMillis();

    assertTrue(elapsedMillis >= 1_500L && elapsedMillis < 8_000L, "elapsed=" + elapsedMillis);
    assertEquals(
        BlueprintReadinessStatus.TEMPORARILY_UNAVAILABLE,
        registry.findings("slow").getFirst().finding().status());
    assertTrue(registry.findings("slow").getFirst().finding().message().contains("timed out"));
    registry.close();
  }

  private static BlueprintView blueprint(String id, boolean annotated) {
    return new BlueprintView(
        id,
        id,
        "test",
        "paper",
        "26.3",
        512,
        10,
        1,
        false,
        List.of(),
        false,
        Set.of(),
        annotated ? Map.of("example-plugin", Map.of("enabled", true)) : Map.of());
  }
}
