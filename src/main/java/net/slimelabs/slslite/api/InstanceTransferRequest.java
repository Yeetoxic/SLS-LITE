package net.slimelabs.slslite.api;

import java.util.UUID;

/** Request to transfer one online player to an exact managed instance. */
public record InstanceTransferRequest(UUID playerId, String instanceId, boolean force) {

  public InstanceTransferRequest {
    playerId = java.util.Objects.requireNonNull(playerId, "playerId");
    if (instanceId == null) {
      throw new IllegalArgumentException("instanceId must not be null");
    }
    instanceId = instanceId.strip();
    if (instanceId.isEmpty()
        || instanceId.length() > 128
        || instanceId.indexOf('\n') >= 0
        || instanceId.indexOf('\r') >= 0) {
      throw new IllegalArgumentException("instanceId must be one line of 1 to 128 characters");
    }
  }
}
