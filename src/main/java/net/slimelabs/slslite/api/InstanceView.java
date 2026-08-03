package net.slimelabs.slslite.api;

import java.time.Instant;

/** Immutable, implementation-independent managed instance view. */
public record InstanceView(
    String id,
    String blueprintId,
    String blueprintType,
    InstanceStatus status,
    int port,
    int memoryLimitMiB,
    int connectedPlayers,
    boolean persistent,
    Instant createdAt,
    String correlationId) {

  public InstanceView {
    id = requireText(id, "id");
    blueprintId = requireText(blueprintId, "blueprintId");
    blueprintType = requireText(blueprintType, "blueprintType");
    status = java.util.Objects.requireNonNull(status, "status");
    createdAt = java.util.Objects.requireNonNull(createdAt, "createdAt");
    correlationId = requireText(correlationId, "correlationId");
    if (port < 1 || port > 65535 || memoryLimitMiB <= 0 || connectedPlayers < 0) {
      throw new IllegalArgumentException("Invalid instance numeric field");
    }
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Instance " + field + " must not be blank");
    }
    return value;
  }
}
