package net.slimelabs.slslite.api;

import java.time.Duration;
import java.util.Optional;
import java.util.OptionalLong;

/** Runtime statistics without a process identifier or implementation handle. */
public record InstanceStatisticsView(
    String instanceId,
    InstanceStatus status,
    int connectedPlayers,
    int retainedLogLines,
    int logRetentionCapacity,
    Optional<Duration> cpuTime,
    OptionalLong residentBytes,
    OptionalLong storageBytesRead,
    OptionalLong storageBytesWritten) {

  public InstanceStatisticsView {
    instanceId = MaintenanceView.boundedText(instanceId, 128, "instanceId");
    if (instanceId.isBlank()) {
      throw new IllegalArgumentException("instanceId must not be blank");
    }
    status = java.util.Objects.requireNonNull(status, "status");
    if (connectedPlayers < 0
        || retainedLogLines < 0
        || logRetentionCapacity < 0
        || retainedLogLines > logRetentionCapacity) {
      throw new IllegalArgumentException("Invalid instance statistic count");
    }
    cpuTime = cpuTime == null ? Optional.empty() : cpuTime;
    if (cpuTime.filter(Duration::isNegative).isPresent()) {
      throw new IllegalArgumentException("cpuTime must not be negative");
    }
    residentBytes = nonNegative(residentBytes, "residentBytes");
    storageBytesRead = nonNegative(storageBytesRead, "storageBytesRead");
    storageBytesWritten = nonNegative(storageBytesWritten, "storageBytesWritten");
  }

  private static OptionalLong nonNegative(OptionalLong value, String field) {
    OptionalLong normalized = value == null ? OptionalLong.empty() : value;
    if (normalized.isPresent() && normalized.getAsLong() < 0) {
      throw new IllegalArgumentException(field + " must not be negative");
    }
    return normalized;
  }
}
