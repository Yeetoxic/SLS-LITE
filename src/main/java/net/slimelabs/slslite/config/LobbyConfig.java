package net.slimelabs.slslite.config;

public record LobbyConfig(
        LobbyMode mode,
        String registry,
        String server,
        int maxRestartAttempts,
        int initialBackoffSeconds,
        int maxBackoffSeconds,
        int stableAfterSeconds
) {

    public LobbyConfig(LobbyMode mode, String registry, String server) {
        this(mode, registry, server, 5, 5, 60, 120);
    }

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
        if (maxRestartAttempts < 0) {
            throw new IllegalArgumentException(
                    "lobby max restart attempts must not be negative"
            );
        }
        if (initialBackoffSeconds <= 0) {
            throw new IllegalArgumentException(
                    "lobby initial backoff must be positive"
            );
        }
        if (maxBackoffSeconds < initialBackoffSeconds) {
            throw new IllegalArgumentException(
                    "lobby maximum backoff must not be less than initial backoff"
            );
        }
        if (stableAfterSeconds <= 0) {
            throw new IllegalArgumentException(
                    "lobby stable-after delay must be positive"
            );
        }
    }
}
