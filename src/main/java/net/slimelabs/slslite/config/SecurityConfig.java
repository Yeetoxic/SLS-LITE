package net.slimelabs.slslite.config;

public record SecurityConfig(
        boolean allowInsecureOfflineAdministrators,
        int claimCodeExpirySeconds
) {

    public SecurityConfig {
        if (claimCodeExpirySeconds <= 0) {
            throw new IllegalArgumentException("claim code expiry must be positive");
        }
    }
}
