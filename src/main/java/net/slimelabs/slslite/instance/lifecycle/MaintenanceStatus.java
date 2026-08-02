package net.slimelabs.slslite.instance.lifecycle;

import java.time.Instant;

/** Immutable host-wide admission state for planned maintenance and draining. */
public record MaintenanceStatus(boolean enabled, Instant changedAt, String reason) {

  public MaintenanceStatus {
    changedAt = java.util.Objects.requireNonNull(changedAt, "changedAt");
    reason = reason == null ? "" : reason.strip();
    if (reason.length() > 256) {
      throw new IllegalArgumentException("Maintenance reason exceeds 256 characters");
    }
    if (reason.chars().anyMatch(character -> Character.isISOControl(character))) {
      throw new IllegalArgumentException("Maintenance reason contains a control character");
    }
  }

  public static MaintenanceStatus accepting() {
    return new MaintenanceStatus(false, Instant.now(), "");
  }
}
