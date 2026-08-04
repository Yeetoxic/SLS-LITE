package net.slimelabs.slslite.api;

import java.util.List;

/**
 * Redacted tail of one instance's bounded in-memory output.
 *
 * @param instanceId managed-instance identifier
 * @param lines up to 20 most recent sanitized lines
 * @param totalRetainedLines lines currently retained by the bounded buffer
 * @param retentionCapacity maximum lines retained by that buffer
 */
public record InstanceLogSnapshot(
    String instanceId, List<String> lines, int totalRetainedLines, int retentionCapacity) {

  public InstanceLogSnapshot {
    instanceId = MaintenanceView.boundedText(instanceId, 128, "instanceId");
    if (instanceId.isBlank()) {
      throw new IllegalArgumentException("instanceId must not be blank");
    }
    lines = List.copyOf(java.util.Objects.requireNonNull(lines, "lines"));
    if (lines.size() > 20) {
      throw new IllegalArgumentException("lines exceeds 20 entries");
    }
    lines.forEach(line -> MaintenanceView.boundedText(line, 512, "log line"));
    if (totalRetainedLines < 0
        || retentionCapacity < 0
        || totalRetainedLines > retentionCapacity
        || lines.size() > totalRetainedLines) {
      throw new IllegalArgumentException("Invalid retained log counts");
    }
  }
}
