package net.slimelabs.slslite.api.event;

import java.time.Instant;
import java.util.Objects;

/**
 * Retained bounded summary of startup instance reconciliation.
 *
 * @param sequence provider-lifetime event sequence number
 * @param occurredAt reconciliation completion time
 * @param correlationId startup correlation identifier
 * @param recoveredStorageTransactions interrupted storage transactions recovered
 * @param removedEphemeral stale ephemeral instances removed
 * @param preservedPersistent persistent instances preserved for remount/restart
 * @param preservedRunning instances preserved because their process may still be running
 * @param preservedUnknown entries preserved because ownership could not be proven
 * @param failures entries that could not be reconciled
 */
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
