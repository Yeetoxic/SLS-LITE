package net.slimelabs.slslite.instance.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.slimelabs.slslite.blueprint.Blueprint;
import net.slimelabs.slslite.instance.ManagedInstance;
import net.slimelabs.slslite.instance.ManagedInstanceTestFactory;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class FailedStartDiagnosticsTest {

  @TempDir Path temporaryDirectory;

  @Test
  void recordsFailureAndBoundedRecentOutput() throws Exception {
    FailedStartDiagnostics diagnostics =
        new FailedStartDiagnostics(temporaryDirectory.resolve("failed-starts"));
    ManagedInstance instance = instance("game.abc123");
    ManagedInstanceTestFactory.appendLog(instance, "first");
    ManagedInstanceTestFactory.appendLog(instance, "last");

    Path report =
        diagnostics.record(
            instance, FailurePhase.READINESS, new IllegalStateException("not ready"));
    String content = Files.readString(report);

    assertTrue(content.contains("instance=game.abc123"));
    assertTrue(content.contains("blueprint=minigame/game"));
    assertTrue(content.contains("phase=readiness"));
    assertTrue(content.contains("java.lang.IllegalStateException: not ready"));
    assertTrue(content.contains("first"));
    assertTrue(content.contains("last"));
    assertTrue(Files.size(report) <= FailedStartDiagnostics.MAX_REPORT_BYTES);
  }

  @Test
  void preservesBoundedLeadingAndTrailingOutput() throws Exception {
    FailedStartDiagnostics diagnostics =
        new FailedStartDiagnostics(temporaryDirectory.resolve("failed-starts"));
    ManagedInstance instance = instance("game.abc123");
    for (int index = 0; index < 250; index++) {
      ManagedInstanceTestFactory.appendLog(instance, "output-" + index);
    }

    Path report =
        diagnostics.record(instance, FailurePhase.RUNTIME, new IllegalStateException("crashed"));
    String content = Files.readString(report);

    assertTrue(content.contains("output-0"));
    assertTrue(content.contains("output-99"));
    assertFalse(content.lines().anyMatch("output-100"::equals));
    assertFalse(content.lines().anyMatch("output-149"::equals));
    assertTrue(content.contains("50 retained lines omitted"));
    assertTrue(content.contains("output-150"));
    assertTrue(content.contains("output-249"));
    assertTrue(Files.size(report) <= FailedStartDiagnostics.MAX_REPORT_BYTES);
  }

  @Test
  void prunesOldReports() throws Exception {
    Path root = temporaryDirectory.resolve("failed-starts");
    FailedStartDiagnostics diagnostics = new FailedStartDiagnostics(root);
    for (int index = 0; index <= FailedStartDiagnostics.MAX_REPORTS; index++) {
      diagnostics.record(
          instance("game." + String.format("%06d", index)),
          FailurePhase.PREPARATION,
          new IllegalStateException("failure " + index));
      Thread.sleep(2);
    }

    try (var reports = Files.list(root)) {
      List<Path> retained =
          reports.filter(path -> path.getFileName().toString().endsWith(".log")).toList();
      assertEquals(FailedStartDiagnostics.MAX_REPORTS, retained.size());
      assertFalse(
          retained.stream()
              .anyMatch(path -> path.getFileName().toString().startsWith("game.000000-")));
    }
  }

  @Test
  void redactsSecretsAndPathsAndLeavesNoTemporaryFile() throws Exception {
    Path root = temporaryDirectory.resolve("failed-starts");
    FailedStartDiagnostics diagnostics = new FailedStartDiagnostics(root);
    ManagedInstance instance = instance("game.abc123");
    ManagedInstanceTestFactory.appendLog(
        instance,
        "token=sls_live_do_not_retain path=" + temporaryDirectory.resolve("private/file"));

    Path report =
        diagnostics.record(
            instance,
            FailurePhase.CONFIGURATION,
            new IllegalStateException("password=visible " + temporaryDirectory.resolve("secret")));
    String content = Files.readString(report);

    assertFalse(content.contains("do_not_retain"));
    assertFalse(content.contains("password=visible"));
    assertFalse(content.contains(temporaryDirectory.toString()));
    try (var files = Files.list(root)) {
      assertFalse(files.anyMatch(path -> path.getFileName().toString().endsWith(".tmp")));
    }
  }

  @Test
  void refusesSymlinkedDiagnosticsDirectoryWithoutWritingOutside() throws Exception {
    Path outside = Files.createDirectories(temporaryDirectory.resolve("outside"));
    Path root = temporaryDirectory.resolve("failed-starts");
    try {
      Files.createSymbolicLink(root, outside);
    } catch (UnsupportedOperationException | java.io.IOException exception) {
      return;
    }

    FailedStartDiagnostics diagnostics = new FailedStartDiagnostics(root);
    org.junit.jupiter.api.Assertions.assertThrows(
        java.io.IOException.class,
        () ->
            diagnostics.record(
                instance("game.abc123"),
                FailurePhase.STARTUP,
                new IllegalStateException("failed")));
    try (var files = Files.list(outside)) {
      assertEquals(0, files.count());
    }
  }

  private static ManagedInstance instance(String id) {
    Blueprint blueprint =
        new Blueprint("game", "Game", "minigame", "paper", "1.21.4", 512, 20, 1, false, Map.of());
    return ManagedInstanceTestFactory.preparing(
        id, blueprint, 25570, Path.of("instances").resolve(id));
  }
}
