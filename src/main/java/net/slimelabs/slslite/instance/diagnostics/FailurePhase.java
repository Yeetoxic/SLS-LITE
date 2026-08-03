package net.slimelabs.slslite.instance.diagnostics;

import java.util.Locale;

public enum FailurePhase {
  CONFIGURATION,
  PREPARATION,
  INSTALLATION,
  STARTUP,
  READINESS,
  REGISTRATION,
  RUNTIME,
  CONNECTION,
  SHUTDOWN,
  CLEANUP;

  public String id() {
    return name().toLowerCase(Locale.ROOT).replace('_', '-');
  }
}
