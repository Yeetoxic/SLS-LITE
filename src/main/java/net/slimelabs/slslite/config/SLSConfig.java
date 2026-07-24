package net.slimelabs.slslite.config;

import java.nio.file.Path;

public record SLSConfig(
        int totalMemoryMiB,
        int portRangeStart,
        int portRangeEnd,
        int queueTimeoutSeconds,
        LobbyConfig lobby,
        Path instancesDirectory
) {

    public SLSConfig {
        if (totalMemoryMiB <= 0) {
            throw new IllegalArgumentException("totalMemoryMiB must be positive");
        }
        if (portRangeStart < 1024 || portRangeStart > 65535) {
            throw new IllegalArgumentException("portRangeStart must be between 1024 and 65535");
        }
        if (portRangeEnd < portRangeStart || portRangeEnd > 65535) {
            throw new IllegalArgumentException(
                    "portRangeEnd must be between portRangeStart and 65535"
            );
        }
        if (queueTimeoutSeconds <= 0) {
            throw new IllegalArgumentException("queueTimeoutSeconds must be positive");
        }
        if (lobby == null) {
            throw new IllegalArgumentException("lobby configuration is required");
        }
        instancesDirectory = instancesDirectory.toAbsolutePath().normalize();
    }
}
