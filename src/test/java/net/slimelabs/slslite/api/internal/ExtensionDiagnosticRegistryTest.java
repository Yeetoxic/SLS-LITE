package net.slimelabs.slslite.api.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import net.slimelabs.slslite.api.ExtensionDiagnosticFinding;
import net.slimelabs.slslite.api.ExtensionDiagnosticSeverity;
import org.junit.jupiter.api.Test;
import org.slf4j.helpers.NOPLogger;

class ExtensionDiagnosticRegistryTest {

  @Test
  void evaluatesSortedBoundedAndRedactedSnapshots() throws Exception {
    ExtensionDiagnosticRegistry registry = new ExtensionDiagnosticRegistry(NOPLogger.NOP_LOGGER);
    registry.register(
        "zeta",
        () ->
            List.of(
                new ExtensionDiagnosticFinding(
                    "database", ExtensionDiagnosticSeverity.WARNING, "token=do-not-publish")));
    registry.register(
        "alpha",
        () ->
            List.of(
                new ExtensionDiagnosticFinding(
                    "healthy", ExtensionDiagnosticSeverity.INFO, "ready")));

    var snapshots = awaitSnapshots(registry, 2);

    assertEquals(
        List.of("alpha", "zeta"), snapshots.stream().map(view -> view.namespace()).toList());
    assertEquals("ready", snapshots.getFirst().findings().getFirst().message());
    assertEquals("token=<redacted>", snapshots.get(1).findings().getFirst().message());
    registry.close();
    assertTrue(registry.snapshot().isEmpty());
  }

  @Test
  void isolatesFailureAndExcessiveFindings() throws Exception {
    ExtensionDiagnosticRegistry registry = new ExtensionDiagnosticRegistry(NOPLogger.NOP_LOGGER);
    registry.register("failure", () -> null);
    registry.register(
        "excessive",
        () ->
            java.util.stream.IntStream.range(0, 17)
                .mapToObj(
                    index ->
                        new ExtensionDiagnosticFinding(
                            "finding-" + index, ExtensionDiagnosticSeverity.INFO, "message"))
                .toList());

    var snapshots = awaitSnapshots(registry, 2);

    assertEquals(2, snapshots.size());
    assertTrue(
        snapshots.stream()
            .allMatch(
                view ->
                    view.findings().size() == 1
                        && view.findings().getFirst().severity()
                            == ExtensionDiagnosticSeverity.ERROR));
    registry.close();
  }

  @Test
  void snapshotNeverWaitsForSlowExtensionCode() throws Exception {
    ExtensionDiagnosticRegistry registry = new ExtensionDiagnosticRegistry(NOPLogger.NOP_LOGGER);
    java.util.concurrent.CountDownLatch entered = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.CountDownLatch release = new java.util.concurrent.CountDownLatch(1);
    registry.register(
        "slow",
        () -> {
          entered.countDown();
          try {
            release.await();
          } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
          }
          return List.of();
        });
    assertTrue(entered.await(1, java.util.concurrent.TimeUnit.SECONDS));

    long started = System.nanoTime();
    registry.snapshot();
    long elapsedMillis =
        java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - started);

    assertTrue(
        elapsedMillis < 100, "cached diagnostic snapshot blocked for " + elapsedMillis + "ms");
    release.countDown();
    registry.close();
  }

  private static java.util.List<net.slimelabs.slslite.api.ExtensionDiagnosticView> awaitSnapshots(
      ExtensionDiagnosticRegistry registry, int count) throws Exception {
    long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(3);
    java.util.List<net.slimelabs.slslite.api.ExtensionDiagnosticView> snapshots;
    do {
      snapshots = registry.snapshot();
      if (snapshots.size() == count) {
        return snapshots;
      }
      Thread.sleep(10);
    } while (System.nanoTime() < deadline);
    return snapshots;
  }
}
