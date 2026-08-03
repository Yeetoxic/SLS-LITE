package net.slimelabs.slslite.api.event;

/** Sanitized failure category suitable for extension control flow. */
public enum InstanceFailureCategory {
  CANCELLED,
  TIMEOUT,
  SOFTWARE,
  CONFIGURATION,
  STORAGE,
  READINESS,
  REGISTRATION,
  PROCESS,
  SHUTDOWN
}
