package net.slimelabs.slslite.api;

/** Bounded host-wide catalog, runtime, and queue counts. */
public record SystemDiagnosticView(
    ApiStatus apiStatus, int blueprints, int instances, int queuedPlayers) {

  public SystemDiagnosticView {
    apiStatus = java.util.Objects.requireNonNull(apiStatus, "apiStatus");
    if (blueprints < 0 || instances < 0 || queuedPlayers < 0) {
      throw new IllegalArgumentException("System diagnostic counts must not be negative");
    }
  }
}
