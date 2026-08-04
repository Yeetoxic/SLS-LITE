package net.slimelabs.slslite.api;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable public view of a player's current matchmaking ticket.
 *
 * @param playerId queued player's UUID
 * @param playerName queued player's last observed name
 * @param registry requested blueprint registry/type
 * @param blueprintId requested blueprint identifier
 * @param instanceId selected managed-instance identifier
 * @param queuedAt ticket creation time
 */
public record QueueTicket(
    UUID playerId,
    String playerName,
    String registry,
    String blueprintId,
    String instanceId,
    Instant queuedAt) {

  public QueueTicket {
    playerId = java.util.Objects.requireNonNull(playerId, "playerId");
    playerName = requireText(playerName, "playerName");
    registry = requireText(registry, "registry");
    blueprintId = requireText(blueprintId, "blueprintId");
    instanceId = requireText(instanceId, "instanceId");
    queuedAt = java.util.Objects.requireNonNull(queuedAt, "queuedAt");
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}
