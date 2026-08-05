package net.slimelabs.slslite.config;

import java.util.Locale;

public enum LobbyMode {
  VELOCITY,
  EXTERNAL,
  MANAGED;

  public static LobbyMode parse(String value) {
    try {
      return valueOf(value.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException(
          "lobby.mode must be 'velocity', 'external', or 'managed'", exception);
    }
  }
}
