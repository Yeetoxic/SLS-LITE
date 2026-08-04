package net.slimelabs.slslite.api;

import java.util.UUID;

/**
 * Request to place an online player into SLS-LITE matchmaking.
 *
 * @param playerId online player's UUID
 * @param registry requested blueprint registry/type
 * @param blueprintId requested blueprint identifier
 */
public record QueueRequest(UUID playerId, String registry, String blueprintId) {

  public QueueRequest {
    playerId = java.util.Objects.requireNonNull(playerId, "playerId");
    registry = requireText(registry, "registry");
    blueprintId = requireText(blueprintId, "blueprintId");
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
    return value;
  }
}
