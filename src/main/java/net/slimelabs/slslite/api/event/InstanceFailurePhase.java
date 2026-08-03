package net.slimelabs.slslite.api.event;

/** Stable phase in which an accepted managed instance failed. */
public enum InstanceFailurePhase {
  CONFIGURATION,
  PREPARATION,
  INSTALLATION,
  STARTUP,
  READINESS,
  REGISTRATION,
  RUNTIME,
  CONNECTION,
  SHUTDOWN,
  CLEANUP
}
