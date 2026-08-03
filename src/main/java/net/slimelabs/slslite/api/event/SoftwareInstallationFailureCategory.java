package net.slimelabs.slslite.api.event;

/** Sanitized reason family for a failed or cancelled software installation. */
public enum SoftwareInstallationFailureCategory {
  NONE,
  IO,
  INSTALLER,
  INTERNAL,
  CANCELLED
}
