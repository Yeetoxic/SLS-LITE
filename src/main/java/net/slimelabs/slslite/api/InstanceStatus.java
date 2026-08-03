package net.slimelabs.slslite.api;

/** Stable public representation of a managed instance lifecycle state. */
public enum InstanceStatus {
  CREATED,
  PREPARING,
  STARTING,
  READY,
  STOPPING,
  STOPPED,
  FAILED
}
