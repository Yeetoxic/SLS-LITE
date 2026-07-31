package net.slimelabs.slslite.config;

public record ManagedOutputConfig(
    boolean mirrorToProxyConsole, boolean writeTemporaryFile, int temporaryFileMaxKiB) {

  public ManagedOutputConfig {
    if (temporaryFileMaxKiB <= 0 || temporaryFileMaxKiB > 1_048_576) {
      throw new IllegalArgumentException("temporaryFileMaxKiB must be between 1 and 1048576");
    }
  }
}
