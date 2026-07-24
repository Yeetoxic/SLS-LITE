package net.slimelabs.slslite.config;

public record LobbyConfig(
        LobbyMode mode,
        String registry,
        String server
) {

    public LobbyConfig {
        if (mode == null) {
            throw new IllegalArgumentException("lobby mode is required");
        }
        if (registry == null || registry.isBlank()) {
            throw new IllegalArgumentException("lobby registry must not be blank");
        }
        if (server == null || server.isBlank()) {
            throw new IllegalArgumentException("lobby server must not be blank");
        }
    }
}
