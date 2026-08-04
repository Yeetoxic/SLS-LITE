package net.slimelabs.slslite.api;

import java.time.Instant;

/**
 * Current host-wide instance-admission state.
 *
 * @param enabled whether new managed-instance admission is disabled
 * @param changedAt time at which the state last changed
 * @param reason bounded operator-provided reason, or an empty string
 */
public record MaintenanceView(boolean enabled, Instant changedAt, String reason) {

  public MaintenanceView {
    changedAt = java.util.Objects.requireNonNull(changedAt, "changedAt");
    reason = boundedText(reason, 256, "reason");
  }

  static String boundedText(String value, int maximum, String field) {
    String normalized = value == null ? "" : value.strip();
    if (normalized.length() > maximum) {
      throw new IllegalArgumentException(field + " exceeds " + maximum + " characters");
    }
    if (normalized.chars().anyMatch(Character::isISOControl)) {
      throw new IllegalArgumentException(field + " contains a control character");
    }
    return normalized;
  }
}
