package net.slimelabs.slslite.command;

import com.velocitypowered.api.proxy.Player;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;

/**
 * Tracks the online players who explicitly opted into bounded SLS debug chat.
 */
final class DebugPlayerRegistry {

  private static final int MAX_DETAIL_LENGTH = 256;
  private static final DateTimeFormatter TIMESTAMP =
      DateTimeFormatter.ofPattern("MMM dd HH:mm:ss.SSS", Locale.ENGLISH);

  private final ConcurrentMap<UUID, Player> players = new ConcurrentHashMap<>();

  boolean toggle(Player player) {
    UUID playerId = player.getUniqueId();
    AtomicBoolean enabled = new AtomicBoolean();
    players.compute(
        playerId,
        (ignored, current) -> {
          if (current != null) {
            return null;
          }
          enabled.set(true);
          return player;
        });
    return enabled.get();
  }

  void remove(UUID playerId) {
    players.remove(playerId);
  }

  void clear() {
    players.clear();
  }

  int size() {
    return players.size();
  }

  void publish(String level, String detail) {
    String normalizedLevel =
        level == null || level.isBlank() ? "DEBUG" : level.strip().toUpperCase(Locale.ROOT);
    String normalizedDetail = normalizeDetail(detail);
    Component message =
        CommandMessages.prefix()
            .append(Component.text("[", NamedTextColor.DARK_GRAY))
            .append(Component.text(normalizedLevel, color(normalizedLevel)))
            .append(Component.text("] ", NamedTextColor.DARK_GRAY))
            .append(Component.text(normalizedDetail, NamedTextColor.GRAY))
            .hoverEvent(
                HoverEvent.showText(
                    Component.text(
                        TIMESTAMP.format(LocalDateTime.now()), NamedTextColor.DARK_PURPLE)));
    players.forEach(
        (playerId, player) -> {
          if (!player.isActive()) {
            players.remove(playerId, player);
            return;
          }
          player.sendMessage(message);
        });
  }

  private static String normalizeDetail(String detail) {
    String normalized = detail == null ? "" : detail.replace('\r', ' ').replace('\n', ' ').strip();
    if (normalized.length() <= MAX_DETAIL_LENGTH) {
      return normalized;
    }
    return normalized.substring(0, MAX_DETAIL_LENGTH - 1) + "\u2026";
  }

  private static NamedTextColor color(String level) {
    return switch (level) {
      case "INFO" -> NamedTextColor.AQUA;
      case "WARN" -> NamedTextColor.YELLOW;
      case "ERROR" -> NamedTextColor.DARK_RED;
      default -> NamedTextColor.GRAY;
    };
  }
}
