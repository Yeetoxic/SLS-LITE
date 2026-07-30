package net.slimelabs.slslite.config;

import java.nio.file.Path;

public record StorageConfig(
        StorageStrategy strategy,
        Path snapshotHookExecutable,
        int snapshotHookTimeoutSeconds
) {

    public StorageConfig(StorageStrategy strategy) {
        this(strategy, null, 30);
    }

    public StorageConfig {
        if (strategy == null) {
            throw new IllegalArgumentException("storage strategy is required");
        }
        if (snapshotHookTimeoutSeconds < 1
                || snapshotHookTimeoutSeconds > 300) {
            throw new IllegalArgumentException(
                    "snapshot hook timeout must be between 1 and 300 seconds"
            );
        }
        if (snapshotHookExecutable != null) {
            snapshotHookExecutable = snapshotHookExecutable
                    .toAbsolutePath()
                    .normalize();
        }
    }
}
