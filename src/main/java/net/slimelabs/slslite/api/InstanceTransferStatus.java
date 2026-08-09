package net.slimelabs.slslite.api;

/** Terminal status of an exact-instance transfer request. */
public enum InstanceTransferStatus {
  CONNECTED,
  ALREADY_CONNECTED,
  PLAYER_OFFLINE,
  INSTANCE_NOT_FOUND,
  INSTANCE_NOT_READY,
  INSTANCE_NOT_REGISTERED,
  INSTANCE_FULL,
  FORCE_CAPACITY_FULL,
  CONNECTION_FAILED
}
