package net.slimelabs.slslite.config;

import java.util.List;

/** Immutable, non-secret summary of the loaded host configuration generation. */
public record ConfigMigrationStatus(
    int configuredVersion,
    int currentVersion,
    boolean versionDeclared,
    List<String> effectiveDefaults) {

  public ConfigMigrationStatus {
    if (configuredVersion <= 0 || currentVersion <= 0 || configuredVersion > currentVersion) {
      throw new IllegalArgumentException("Invalid configuration version status");
    }
    effectiveDefaults = List.copyOf(effectiveDefaults);
  }

  public boolean updateAvailable() {
    return !versionDeclared || configuredVersion < currentVersion;
  }
}
