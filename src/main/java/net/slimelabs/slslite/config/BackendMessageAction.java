package net.slimelabs.slslite.config;

import java.util.Locale;

public enum BackendMessageAction {
  MATCHMAKE("matchmake"),
  COMMAND("command");

  private final String configValue;

  BackendMessageAction(String configValue) {
    this.configValue = configValue;
  }

  public String configValue() {
    return configValue;
  }

  public static BackendMessageAction parse(String value) {
    String normalized = value.trim().toLowerCase(Locale.ROOT);
    for (BackendMessageAction action : values()) {
      if (action.configValue.equals(normalized)) {
        return action;
      }
    }
    throw new IllegalArgumentException(
        "unknown backend messaging action '" + value + "'; expected matchmake or command");
  }
}
