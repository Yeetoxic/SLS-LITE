package net.slimelabs.slslite.config;

import java.util.Locale;

public enum BlueprintSelectionMode {
  FIRST_AVAILABLE("first-available"),
  RANDOM("random");

  private final String configValue;

  BlueprintSelectionMode(String configValue) {
    this.configValue = configValue;
  }

  public String configValue() {
    return configValue;
  }

  public static BlueprintSelectionMode parse(String value) {
    String normalized = value == null ? "" : value.trim().toLowerCase(Locale.ROOT);
    for (BlueprintSelectionMode mode : values()) {
      if (mode.configValue.equals(normalized)) {
        return mode;
      }
    }
    throw new IllegalArgumentException(
        "matchmaking.blueprint_selection must be first-available or random");
  }
}
