package net.slimelabs.slslite.api.event;

import java.time.Instant;
import java.util.Objects;

/** Retained bounded summary of startup instance reconciliation. */
public record ReconciliationEvent(
    long sequence,
    Instant occurredAt,
    String correlationId,
    int recoveredStorageTransactions,
    int removedEphemeral,
    int preservedPersistent,
    int preservedRunning,
    int preservedUnknown,
    int failures)
    implements SLSLiteEvent {

  public ReconciliationEvent {
    if (sequence < 1) {
      throw new IllegalArgumentException("sequence must be positive");
    }
    Objects.requireNonNull(occurredAt, "occurredAt");
    if (correlationId == null || correlationId.isBlank()) {
      throw new IllegalArgumentException("correlationId must not be blank");
    }
    if (recoveredStorageTransactions < 0
        || removedEphemeral < 0
        || preservedPersistent < 0
        || preservedRunning < 0
        || preservedUnknown < 0
        || failures < 0) {
      throw new IllegalArgumentException("reconciliation counts must not be negative");
    }
  }

  public int inspected() {
    return removedEphemeral + preservedPersistent + preservedRunning + preservedUnknown + failures;
  }
}
