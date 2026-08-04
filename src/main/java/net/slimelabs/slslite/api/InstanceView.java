package net.slimelabs.slslite.api;

import java.time.Instant;

/**
 * Immutable, implementation-independent managed instance view.
 *
 * @param id managed-instance identifier
 * @param blueprintId source blueprint identifier
 * @param blueprintType source blueprint registry/type
 * @param status current public lifecycle status
 * @param port allocated local backend port
 * @param memoryLimitMiB admitted memory limit in mebibytes
 * @param connectedPlayers current connected-player count
 * @param persistent whether the instance survives normal proxy restarts
 * @param createdAt instance creation time
 * @param correlationId bounded identifier for correlated diagnostics
 */
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
