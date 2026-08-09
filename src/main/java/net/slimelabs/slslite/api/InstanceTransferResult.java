package net.slimelabs.slslite.api;

import java.util.UUID;

/** Bounded terminal result for an exact-instance player transfer. */
public record InstanceTransferResult(
    UUID playerId,
    String instanceId,
    boolean forced,
    InstanceTransferStatus status,
    String detail) {

  public InstanceTransferResult {
    playerId = java.util.Objects.requireNonNull(playerId, "playerId");
    if (instanceId == null
        || instanceId.isBlank()
        || instanceId.length() > 128
        || instanceId.indexOf('\n') >= 0
        || instanceId.indexOf('\r') >= 0) {
      throw new IllegalArgumentException("instanceId must be one line of 1 to 128 characters");
    }
    status = java.util.Objects.requireNonNull(status, "status");
    detail = detail == null ? "" : detail.strip();
    if (detail.length() > 512 || detail.indexOf('\n') >= 0 || detail.indexOf('\r') >= 0) {
      throw new IllegalArgumentException("detail must be one line of at most 512 characters");
    }
  }

  /** Returns whether Velocity accepted the transfer. */
  public boolean connected() {
    return status == InstanceTransferStatus.CONNECTED
        || status == InstanceTransferStatus.ALREADY_CONNECTED;
  }
}
