package net.slimelabs.slslite.config;

import java.nio.file.Path;
import java.util.List;

/** Immutable, non-secret summary of the loaded host configuration generation. */
public record ConfigMigrationStatus(
    int configuredVersion,
    int currentVersion,
    boolean versionDeclared,
    Path referenceConfig,
    List<String> effectiveDefaults) {

  public ConfigMigrationStatus {
    if (configuredVersion <= 0 || currentVersion <= 0 || configuredVersion > currentVersion) {
      throw new IllegalArgumentException("Invalid configuration version status");
    }
    referenceConfig = referenceConfig.toAbsolutePath().normalize();
    effectiveDefaults = List.copyOf(effectiveDefaults);
  }

  public boolean updateAvailable() {
    return !versionDeclared || configuredVersion < currentVersion || !effectiveDefaults.isEmpty();
  }
}
