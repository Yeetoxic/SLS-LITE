package net.slimelabs.slslite.api.event;

import java.time.Instant;
import net.slimelabs.slslite.api.InstanceStatus;

/**
 * Ordered notification emitted after an accepted managed-instance transition.
 *
 * @param sequence provider-lifetime event sequence number
 * @param occurredAt transition time
 * @param instanceId affected managed-instance identifier
 * @param previousStatus status before the transition
 * @param currentStatus status after the transition
 */
public record InstanceLifecycleEvent(
    long sequence,
    Instant occurredAt,
    String instanceId,
    InstanceStatus previousStatus,
    InstanceStatus currentStatus)
    implements SLSLiteEvent {

  public InstanceLifecycleEvent {
    if (sequence < 1) {
      throw new IllegalArgumentException("sequence must be positive");
    }
    occurredAt = java.util.Objects.requireNonNull(occurredAt, "occurredAt");
    if (instanceId == null || instanceId.isBlank()) {
      throw new IllegalArgumentException("instanceId must not be blank");
    }
    previousStatus = java.util.Objects.requireNonNull(previousStatus, "previousStatus");
    currentStatus = java.util.Objects.requireNonNull(currentStatus, "currentStatus");
  }
}
