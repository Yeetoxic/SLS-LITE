package net.slimelabs.slslite.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import net.slimelabs.slslite.api.event.LobbyRoute;
import net.slimelabs.slslite.api.event.LobbyServiceStatus;
import org.junit.jupiter.api.Test;

class DiagnosticsSnapshotTest {

  @Test
  void deepCopiesDiagnosticCollections() {
    List<String> lines = new ArrayList<>(List.of("ready"));
    InstanceLogSnapshot log = new InstanceLogSnapshot("arena.1", lines, 1, 1_000);
    List<InstanceLogSnapshot> logs = new ArrayList<>(List.of(log));
    DiagnosticsSnapshot snapshot =
        new DiagnosticsSnapshot(
            Instant.now(),
            new SystemDiagnosticView(ApiStatus.READY, 1, 1, 0),
            new MaintenanceView(false, Instant.now(), ""),
            new LobbyDiagnosticView(
                LobbyServiceStatus.READY,
                LobbyServiceStatus.READY,
                LobbyRoute.PRIMARY,
                true,
                0,
                3,
                ""),
            List.of(),
            List.of(),
            List.of(),
            logs,
            List.of(),
            List.of());

    lines.add("mutated");
    logs.clear();

    assertEquals(List.of("ready"), snapshot.recentLogs().getFirst().lines());
    assertThrows(
        UnsupportedOperationException.class,
        () -> snapshot.recentLogs().add(snapshot.recentLogs().getFirst()));
  }

  @Test
  void enforcesPublicLineAndEntryBounds() {
    List<String> tooManyLines = java.util.Collections.nCopies(21, "line");
    assertThrows(
        IllegalArgumentException.class,
        () -> new InstanceLogSnapshot("arena.1", tooManyLines, 21, 1_000));
    assertThrows(
        IllegalArgumentException.class,
        () -> new MaintenanceView(true, Instant.now(), "bad\nreason"));
    assertThrows(
        IllegalArgumentException.class, () -> new SystemDiagnosticView(ApiStatus.READY, 0, -1, 0));
  }
}
