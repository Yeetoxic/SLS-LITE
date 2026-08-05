package net.slimelabs.slslite.config;

import net.kyori.adventure.text.minimessage.MiniMessage;

public record SLSLimboPresentationConfig(
    Dimension dimension,
    Ping ping,
    PlayerList playerList,
    TextElement brand,
    TextElement joinMessage,
    BossBar bossBar,
    Title title,
    HeaderFooter headerFooter) {

  public static final int MAX_TEXT_LENGTH = 2048;

  public static SLSLimboPresentationConfig defaults() {
    return new SLSLimboPresentationConfig(
        Dimension.THE_END,
        new Ping(true, "<gold>SLS-Limbo", "<gold>SLS-LITE"),
        new PlayerList(false, "SLS-LITE"),
        new TextElement(true, "<gold>SLS-Limbo"),
        new TextElement(true, "<yellow>You are in SLS-Limbo while your destination gets ready."),
        new BossBar(true, "<yellow>Waiting for your destination...", 1.0, "YELLOW", "SOLID"),
        new Title(true, "<gold>SLS-LITE", "<yellow>SLS-Limbo", 10, 100, 10),
        new HeaderFooter(false, "", ""));
  }

  public SLSLimboPresentationConfig {
    java.util.Objects.requireNonNull(dimension, "dimension");
    java.util.Objects.requireNonNull(ping, "ping");
    java.util.Objects.requireNonNull(playerList, "playerList");
    java.util.Objects.requireNonNull(brand, "brand");
    java.util.Objects.requireNonNull(joinMessage, "joinMessage");
    java.util.Objects.requireNonNull(bossBar, "bossBar");
    java.util.Objects.requireNonNull(title, "title");
    java.util.Objects.requireNonNull(headerFooter, "headerFooter");
  }

  public enum Dimension {
    OVERWORLD,
    NETHER,
    THE_END;

    static Dimension parse(String value) {
      try {
        return valueOf(value.trim().toUpperCase(java.util.Locale.ROOT));
      } catch (IllegalArgumentException | NullPointerException exception) {
        throw new IllegalArgumentException(
            "SLS-Limbo dimension must be OVERWORLD, NETHER, or THE_END");
      }
    }
  }

  public record Ping(boolean enabled, String description, String version) {
    public Ping {
      description = text(description, "ping description", true);
      version = text(version, "ping version", false);
    }
  }

  public record PlayerList(boolean enabled, String username) {
    public PlayerList {
      username = plain(username, "player-list username", false, 64);
    }
  }

  public record TextElement(boolean enabled, String text) {
    public TextElement {
      text = SLSLimboPresentationConfig.text(text, "presentation text", true);
    }
  }

  public record BossBar(
      boolean enabled, String text, double health, String color, String division) {
    private static final java.util.List<String> COLORS =
        java.util.List.of("PINK", "BLUE", "RED", "GREEN", "YELLOW", "PURPLE", "WHITE");
    private static final java.util.List<String> DIVISIONS =
        java.util.List.of("SOLID", "NOTCHED_6", "NOTCHED_10", "NOTCHED_12", "NOTCHED_20");

    public BossBar {
      text = SLSLimboPresentationConfig.text(text, "boss-bar text", true);
      if (!Double.isFinite(health) || health < 0.0 || health > 1.0) {
        throw new IllegalArgumentException("SLS-Limbo boss-bar health must be between 0 and 1");
      }
      color = enumName(color, COLORS, "boss-bar color");
      division = enumName(division, DIVISIONS, "boss-bar division");
    }
  }

  public record Title(
      boolean enabled,
      String title,
      String subtitle,
      int fadeInTicks,
      int stayTicks,
      int fadeOutTicks) {
    public Title {
      title = text(title, "title", true);
      subtitle = text(subtitle, "subtitle", true);
      validateTicks(fadeInTicks, "fade-in");
      validateTicks(stayTicks, "stay");
      validateTicks(fadeOutTicks, "fade-out");
    }
  }

  public record HeaderFooter(boolean enabled, String header, String footer) {
    public HeaderFooter {
      header = text(header, "header", true);
      footer = text(footer, "footer", true);
    }
  }

  private static String text(String value, String name, boolean allowEmpty) {
    String validated = plain(value, name, allowEmpty, MAX_TEXT_LENGTH);
    MiniMessage.miniMessage().deserialize(validated);
    return validated;
  }

  private static String plain(String value, String name, boolean allowEmpty, int maximum) {
    if (value == null || (!allowEmpty && value.isBlank()) || value.length() > maximum) {
      throw new IllegalArgumentException(
          "SLS-Limbo "
              + name
              + " must contain "
              + (allowEmpty ? "0" : "1")
              + "-"
              + maximum
              + " characters");
    }
    return value;
  }

  private static String enumName(String value, java.util.List<String> allowed, String name) {
    String normalized = value == null ? "" : value.trim().toUpperCase(java.util.Locale.ROOT);
    if (!allowed.contains(normalized)) {
      throw new IllegalArgumentException(
          "SLS-Limbo " + name + " must be one of " + String.join(", ", allowed));
    }
    return normalized;
  }

  private static void validateTicks(int value, String name) {
    if (value < 0 || value > 12_000) {
      throw new IllegalArgumentException("SLS-Limbo title " + name + " must be 0-12000 ticks");
    }
  }
}
