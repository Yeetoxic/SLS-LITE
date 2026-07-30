package net.slimelabs.slslite.instance.diagnostics;

import net.slimelabs.slslite.instance.ManagedInstance;
import net.slimelabs.slslite.instance.ManagedInstanceTestFactory;
import net.slimelabs.slslite.blueprint.Blueprint;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class FailedStartDiagnosticsTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void recordsFailureAndBoundedRecentOutput() throws Exception {
        FailedStartDiagnostics diagnostics = new FailedStartDiagnostics(
                temporaryDirectory.resolve("failed-starts")
        );
        ManagedInstance instance = instance("game.abc123");
        ManagedInstanceTestFactory.appendLog(instance, "first");
        ManagedInstanceTestFactory.appendLog(instance, "last");

        Path report = diagnostics.record(
                instance,
                "readiness",
                new IllegalStateException("not ready")
        );
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
    void prunesOldReports() throws Exception {
        Path root = temporaryDirectory.resolve("failed-starts");
        FailedStartDiagnostics diagnostics = new FailedStartDiagnostics(root);
        for (int index = 0; index <= FailedStartDiagnostics.MAX_REPORTS; index++) {
            diagnostics.record(
                    instance("game." + String.format("%06d", index)),
                    "preparation",
                    new IllegalStateException("failure " + index)
            );
            Thread.sleep(2);
        }

        try (var reports = Files.list(root)) {
            List<Path> retained = reports
                    .filter(path -> path.getFileName().toString().endsWith(".log"))
                    .toList();
            assertEquals(FailedStartDiagnostics.MAX_REPORTS, retained.size());
            assertFalse(retained.stream().anyMatch(path ->
                    path.getFileName().toString().startsWith("game.000000-")
            ));
        }
    }

    private static ManagedInstance instance(String id) {
        Blueprint blueprint = new Blueprint(
                "game",
                "Game",
                "minigame",
                "paper",
                "1.21.4",
                512,
                20,
                1,
                false,
                Map.of()
        );
        return ManagedInstanceTestFactory.preparing(
                id,
                blueprint,
                25570,
                Path.of("instances").resolve(id)
        );
    }
}
