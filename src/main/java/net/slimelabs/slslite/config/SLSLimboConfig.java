package net.slimelabs.slslite.config;

public record SLSLimboConfig(
    boolean enabled,
    int memoryMiB,
    int startupTimeoutSeconds,
    int advertisedProtocol,
    int maxRestartAttempts,
    int initialBackoffSeconds,
    int maxBackoffSeconds,
    int stableAfterSeconds,
    SLSLimboPresentationConfig presentation) {

  public static final int MINIMUM_FIXED_PROTOCOL = 770;

  public SLSLimboConfig(
      boolean enabled,
      int memoryMiB,
      int startupTimeoutSeconds,
      int advertisedProtocol,
      int maxRestartAttempts,
      int initialBackoffSeconds,
      int maxBackoffSeconds,
      int stableAfterSeconds) {
    this(
        enabled,
        memoryMiB,
        startupTimeoutSeconds,
        advertisedProtocol,
        maxRestartAttempts,
        initialBackoffSeconds,
        maxBackoffSeconds,
        stableAfterSeconds,
        SLSLimboPresentationConfig.defaults());
  }

  public SLSLimboConfig {
    if (memoryMiB < 64) {
      throw new IllegalArgumentException("SLS-Limbo memory must be at least 64 MiB");
    }
    if (startupTimeoutSeconds <= 0) {
      throw new IllegalArgumentException("SLS-Limbo startup timeout must be positive");
    }
    if (advertisedProtocol != -1 && advertisedProtocol < MINIMUM_FIXED_PROTOCOL) {
      throw new IllegalArgumentException(
          "SLS-Limbo advertised protocol must be -1 or at least " + MINIMUM_FIXED_PROTOCOL);
    }
    if (maxRestartAttempts < 0) {
      throw new IllegalArgumentException("SLS-Limbo maximum restart attempts must not be negative");
    }
    if (initialBackoffSeconds <= 0) {
      throw new IllegalArgumentException("SLS-Limbo initial backoff must be positive");
    }
    if (maxBackoffSeconds < initialBackoffSeconds) {
      throw new IllegalArgumentException(
          "SLS-Limbo maximum backoff must not be below initial backoff");
    }
    if (stableAfterSeconds <= 0) {
      throw new IllegalArgumentException("SLS-Limbo stable period must be positive");
    }
    if (presentation == null) {
      throw new IllegalArgumentException("SLS-Limbo presentation configuration is required");
    }
  }

  /** Protocol exposed to integrations that cannot represent NanoLimbo's native sentinel. */
  public int synchronizationProtocol() {
    return advertisedProtocol == -1 ? MINIMUM_FIXED_PROTOCOL : advertisedProtocol;
  }
}
