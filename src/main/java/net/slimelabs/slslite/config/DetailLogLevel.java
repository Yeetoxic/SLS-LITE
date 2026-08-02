package net.slimelabs.slslite.config;

import java.util.Locale;

public enum DetailLogLevel {
  OFF,
  NORMAL,
  DETAILED;

  public static DetailLogLevel parse(String value) {
    if (value == null) {
      throw new IllegalArgumentException("detailed log level is required");
    }
    return switch (value.strip().toLowerCase(Locale.ROOT)) {
      case "off" -> OFF;
      case "normal" -> NORMAL;
      case "detailed" -> DETAILED;
      default ->
          throw new IllegalArgumentException("detailed log level must be off, normal, or detailed");
    };
  }
}
