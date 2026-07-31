package net.slimelabs.slslite.config;

import java.nio.file.Path;

public record ForwardingConfig(ForwardingMode mode, boolean onlineMode, Path secretFile) {

  public ForwardingConfig {
    if (mode == null) {
      throw new IllegalArgumentException("forwarding mode is required");
    }
    if (secretFile == null) {
      throw new IllegalArgumentException("forwarding secret file is required");
    }
    secretFile = secretFile.toAbsolutePath().normalize();
  }
}
