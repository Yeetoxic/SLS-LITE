package net.slimelabs.slslite.config;

public record SecurityConfig(
    boolean allowInsecureOfflineAdministrators,
    int claimCodeExpirySeconds,
    BackendMessagingConfig backendMessaging) {

  public SecurityConfig(boolean allowInsecureOfflineAdministrators, int claimCodeExpirySeconds) {
    this(
        allowInsecureOfflineAdministrators,
        claimCodeExpirySeconds,
        BackendMessagingConfig.defaults());
  }

  public SecurityConfig {
    if (claimCodeExpirySeconds <= 0) {
      throw new IllegalArgumentException("claim code expiry must be positive");
    }
    if (backendMessaging == null) {
      throw new IllegalArgumentException("backend messaging configuration is required");
    }
  }
}
