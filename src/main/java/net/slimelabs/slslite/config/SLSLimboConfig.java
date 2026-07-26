package net.slimelabs.slslite.config;

public record SLSLimboConfig(
        boolean enabled,
        int memoryMiB,
        int startupTimeoutSeconds
) {

    public SLSLimboConfig {
        if (memoryMiB < 64) {
            throw new IllegalArgumentException(
                    "SLS-Limbo memory must be at least 64 MiB"
            );
        }
        if (startupTimeoutSeconds <= 0) {
            throw new IllegalArgumentException(
                    "SLS-Limbo startup timeout must be positive"
            );
        }
    }
}
