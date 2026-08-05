package net.slimelabs.slslite.config;

import java.util.Locale;

public enum ViaVersionSyncPolicy {
  AUTO,
  ON,
  OFF;

  public static ViaVersionSyncPolicy parse(String value) {
    try {
      return valueOf(value.trim().replace('-', '_').toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException | NullPointerException exception) {
      throw new IllegalArgumentException(
          "compatibility.viaversion_backend_sync must be auto, on, or off");
    }
  }
}
