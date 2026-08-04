package net.slimelabs.slslite.api;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import net.slimelabs.slslite.api.event.SoftwareInstallationStatus;

/**
 * Redacted bounded state for one recent software installation.
 *
 * @param softwareId configured software identifier
 * @param version requested software version
 * @param status latest installation state
 * @param startedAt installation start time
 * @param completedAt terminal time, if the installation completed
 * @param detail bounded operator-safe status detail
 * @param recentLogs up to 20 recent sanitized log lines
 */
public record InstallationDiagnosticView(
    String softwareId,
    String version,
    SoftwareInstallationStatus status,
    Instant startedAt,
    Optional<Instant> completedAt,
    String detail,
    List<String> recentLogs) {

  public InstallationDiagnosticView {
    softwareId = requiredText(softwareId, 128, "softwareId");
    version = requiredText(version, 128, "version");
    status = java.util.Objects.requireNonNull(status, "status");
    startedAt = java.util.Objects.requireNonNull(startedAt, "startedAt");
    completedAt = completedAt == null ? Optional.empty() : completedAt;
    detail = MaintenanceView.boundedText(detail, 512, "detail");
    recentLogs = List.copyOf(java.util.Objects.requireNonNull(recentLogs, "recentLogs"));
    if (recentLogs.size() > 20) {
      throw new IllegalArgumentException("recentLogs exceeds 20 entries");
    }
    recentLogs.forEach(line -> MaintenanceView.boundedText(line, 512, "log line"));
  }

  private static String requiredText(String value, int maximum, String field) {
    String normalized = MaintenanceView.boundedText(value, maximum, field);
    if (normalized.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return normalized;
  }
}
