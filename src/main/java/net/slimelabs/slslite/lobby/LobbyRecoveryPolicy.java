package net.slimelabs.slslite.lobby;

import net.slimelabs.slslite.config.LobbyConfig;
import net.slimelabs.slslite.config.SLSLimboConfig;

final class LobbyRecoveryPolicy {

  private final int maxAttempts;
  private final long initialBackoffSeconds;
  private final long maxBackoffSeconds;
  private final long stableAfterSeconds;

  private LobbyRecoveryPolicy(
      int maxAttempts,
      long initialBackoffSeconds,
      long maxBackoffSeconds,
      long stableAfterSeconds) {
    this.maxAttempts = maxAttempts;
    this.initialBackoffSeconds = initialBackoffSeconds;
    this.maxBackoffSeconds = maxBackoffSeconds;
    this.stableAfterSeconds = stableAfterSeconds;
  }

  static LobbyRecoveryPolicy from(LobbyConfig config) {
    return new LobbyRecoveryPolicy(
        config.maxRestartAttempts(),
        config.initialBackoffSeconds(),
        config.maxBackoffSeconds(),
        config.stableAfterSeconds());
  }

  static LobbyRecoveryPolicy from(SLSLimboConfig config) {
    return new LobbyRecoveryPolicy(
        config.maxRestartAttempts(),
        config.initialBackoffSeconds(),
        config.maxBackoffSeconds(),
        config.stableAfterSeconds());
  }

  int maxAttempts() {
    return maxAttempts;
  }

  boolean exhausted(int attempts) {
    return attempts >= maxAttempts;
  }

  long backoffSeconds(int attempt) {
    long delay = initialBackoffSeconds;
    for (int index = 1; index < attempt; index++) {
      delay = Math.min(maxBackoffSeconds, delay * 2);
    }
    return delay;
  }

  long stableAfterSeconds() {
    return stableAfterSeconds;
  }
}
