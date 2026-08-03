package net.slimelabs.slslite.api;

import java.time.Instant;
import java.util.List;
import net.slimelabs.slslite.api.event.InstanceFailureEvent;

/** Immutable, redacted, and bounded operational snapshot. */
public record DiagnosticsSnapshot(
    Instant capturedAt,
    SystemDiagnosticView system,
    MaintenanceView maintenance,
    LobbyDiagnosticView lobby,
    List<InstallationDiagnosticView> installations,
    List<HostCapabilityView> hostCapabilities,
    List<InstanceStatisticsView> instanceStatistics,
    List<InstanceLogSnapshot> recentLogs,
    List<InstanceFailureEvent> recentFailures) {

  public DiagnosticsSnapshot {
    capturedAt = java.util.Objects.requireNonNull(capturedAt, "capturedAt");
    system = java.util.Objects.requireNonNull(system, "system");
    maintenance = java.util.Objects.requireNonNull(maintenance, "maintenance");
    lobby = java.util.Objects.requireNonNull(lobby, "lobby");
    installations = boundedCopy(installations, 100, "installations");
    hostCapabilities = boundedCopy(hostCapabilities, 64, "hostCapabilities");
    instanceStatistics = boundedCopy(instanceStatistics, 256, "instanceStatistics");
    recentLogs = boundedCopy(recentLogs, 256, "recentLogs");
    recentFailures = boundedCopy(recentFailures, 64, "recentFailures");
  }

  private static <T> List<T> boundedCopy(List<T> values, int maximum, String field) {
    List<T> copy = List.copyOf(java.util.Objects.requireNonNull(values, field));
    if (copy.size() > maximum) {
      throw new IllegalArgumentException(field + " exceeds " + maximum + " entries");
    }
    return copy;
  }
}
