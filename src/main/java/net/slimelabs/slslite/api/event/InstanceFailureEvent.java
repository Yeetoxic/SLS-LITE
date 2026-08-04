package net.slimelabs.slslite.api.event;

import java.time.Instant;
import java.util.Objects;

/**
 * Sanitized failure of an accepted managed instance.
 *
 * @param sequence provider-lifetime event sequence number
 * @param occurredAt failure observation time
 * @param instanceId failed managed-instance identifier
 * @param blueprintId source blueprint identifier
 * @param blueprintType source blueprint registry/type
 * @param correlationId bounded identifier for correlated diagnostics
 * @param phase lifecycle phase in which the failure occurred
 * @param category sanitized failure category
 */
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
