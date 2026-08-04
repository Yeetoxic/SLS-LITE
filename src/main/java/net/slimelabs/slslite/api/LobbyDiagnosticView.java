package net.slimelabs.slslite.api;

import net.slimelabs.slslite.api.event.LobbyRoute;
import net.slimelabs.slslite.api.event.LobbyServiceStatus;

/**
 * Effective primary/holding lobby health without backend or process details.
 *
 * @param primaryStatus configured primary lobby state
 * @param holdingStatus built-in holding-lobby state
 * @param route lobby tier currently selected for player routing
 * @param limboEnabled whether the built-in holding lobby is configured
 * @param recoveryAttempts recovery attempts used by the primary lobby
 * @param maximumRecoveryAttempts configured recovery-attempt limit
 * @param lastFailure bounded sanitized most-recent failure detail
 */
public record LobbyDiagnosticView(
    LobbyServiceStatus primaryStatus,
    LobbyServiceStatus holdingStatus,
    LobbyRoute route,
    boolean limboEnabled,
    int recoveryAttempts,
    int maximumRecoveryAttempts,
    String lastFailure) {

  public LobbyDiagnosticView {
    primaryStatus = java.util.Objects.requireNonNull(primaryStatus, "primaryStatus");
    holdingStatus = java.util.Objects.requireNonNull(holdingStatus, "holdingStatus");
    route = java.util.Objects.requireNonNull(route, "route");
    if (recoveryAttempts < 0
        || maximumRecoveryAttempts < 0
        || recoveryAttempts > maximumRecoveryAttempts) {
      throw new IllegalArgumentException("Invalid lobby recovery counts");
    }
    lastFailure = MaintenanceView.boundedText(lastFailure, 512, "lastFailure");
  }

  public boolean available() {
    return route != LobbyRoute.NONE;
  }
}
