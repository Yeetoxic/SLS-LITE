package net.slimelabs.slslite.config;

public record DetailedLoggingConfig(
    DetailLogLevel level,
    boolean mirrorToProxyConsole,
    int maxFileKiB,
    int retainedFiles,
    int queueCapacity,
    boolean redactPaths) {

  public DetailedLoggingConfig {
    if (level == null) {
      throw new IllegalArgumentException("detailed log level is required");
    }
    if (maxFileKiB < 64 || maxFileKiB > 1_048_576) {
      throw new IllegalArgumentException(
          "detailed log max file KiB must be between 64 and 1048576");
    }
    if (retainedFiles < 1 || retainedFiles > 32) {
      throw new IllegalArgumentException("detailed log retained files must be between 1 and 32");
    }
    if (queueCapacity < 128 || queueCapacity > 65_536) {
      throw new IllegalArgumentException(
          "detailed log queue capacity must be between 128 and 65536");
    }
  }

  public static DetailedLoggingConfig defaults() {
    return new DetailedLoggingConfig(DetailLogLevel.NORMAL, false, 4096, 3, 1024, true);
  }
}
