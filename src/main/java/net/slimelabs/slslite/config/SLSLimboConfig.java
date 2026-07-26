package net.slimelabs.slslite.config;

public record SLSLimboConfig(
        boolean enabled,
        int memoryMiB,
        int startupTimeoutSeconds,
        int advertisedProtocol,
        int maxRestartAttempts,
        int initialBackoffSeconds,
        int maxBackoffSeconds,
        int stableAfterSeconds
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
        if (advertisedProtocol != -1 && advertisedProtocol <= 0) {
            throw new IllegalArgumentException(
                    "SLS-Limbo advertised protocol must be -1 or positive"
            );
        }
        if (maxRestartAttempts < 0) {
            throw new IllegalArgumentException(
                    "SLS-Limbo maximum restart attempts must not be negative"
            );
        }
        if (initialBackoffSeconds <= 0) {
            throw new IllegalArgumentException(
                    "SLS-Limbo initial backoff must be positive"
            );
        }
        if (maxBackoffSeconds < initialBackoffSeconds) {
            throw new IllegalArgumentException(
                    "SLS-Limbo maximum backoff must not be below initial backoff"
            );
        }
        if (stableAfterSeconds <= 0) {
            throw new IllegalArgumentException(
                    "SLS-Limbo stable period must be positive"
            );
        }
    }
}
