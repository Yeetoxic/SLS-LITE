package net.slimelabs.slslite.api.event;

import java.time.Instant;
import java.util.Objects;

/** Sanitized failure of an accepted managed instance. */
public record InstanceFailureEvent(
    long sequence,
    Instant occurredAt,
    String instanceId,
    String blueprintId,
    String blueprintType,
    String correlationId,
    InstanceFailurePhase phase,
    InstanceFailureCategory category)
    implements SLSLiteEvent {

  public InstanceFailureEvent {
    if (sequence < 1) {
      throw new IllegalArgumentException("sequence must be positive");
    }
    Objects.requireNonNull(occurredAt, "occurredAt");
    requireText(instanceId, "instanceId");
    requireText(blueprintId, "blueprintId");
    requireText(blueprintType, "blueprintType");
    requireText(correlationId, "correlationId");
    Objects.requireNonNull(phase, "phase");
    Objects.requireNonNull(category, "category");
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }
}
