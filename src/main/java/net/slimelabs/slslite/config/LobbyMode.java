package net.slimelabs.slslite.config;

import java.util.Locale;

public enum LobbyMode {
  EXTERNAL,
  MANAGED;

  public static LobbyMode parse(String value) {
    try {
      return valueOf(value.toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("lobby.mode must be 'external' or 'managed'", exception);
    }
  }
}
