package net.slimelabs.slslite.api.event;

/** State transition of one shared automatic software installation. */
public enum SoftwareInstallationStatus {
  STARTED,
  READY,
  FAILED,
  CANCELLED
}
