package net.slimelabs.slslite.config;

import java.util.List;
import net.kyori.adventure.text.minimessage.MiniMessage;

public record TransferActionBarConfig(
    boolean enabled,
    String joining,
    String forceJoining,
    String dequeued,
    String progress,
    List<String> frames,
    int frameIntervalMillis) {

  public static final int MAX_TEMPLATE_LENGTH = 1024;
  public static final int MAX_FRAMES = 32;
  public static final int MIN_FRAME_INTERVAL_MILLIS = 25;
  public static final int MAX_FRAME_INTERVAL_MILLIS = 2000;

  public static TransferActionBarConfig defaults() {
    return new TransferActionBarConfig(
        true,
        "<green>Joining <server>",
        "<yellow>Force joining <server>",
        "<red>You have been dequeued.",
        "<frame> <yellow><phase> <gray>(<server>)",
        List.of(
            "<gold>\u2587\u2586\u2585\u2583\u2582\u2582\u2582\u2582\u2582",
            "<gold>\u2586\u2587\u2586\u2585\u2583\u2582\u2582\u2582\u2582",
            "<gold>\u2585\u2586\u2587\u2586\u2585\u2583\u2582\u2582\u2582",
            "<gold>\u2583\u2585\u2586\u2587\u2586\u2585\u2583\u2582\u2582",
            "<gold>\u2582\u2583\u2585\u2586\u2587\u2586\u2585\u2583\u2582",
            "<gold>\u2582\u2582\u2583\u2585\u2586\u2587\u2586\u2585\u2583",
            "<gold>\u2582\u2582\u2582\u2583\u2585\u2586\u2587\u2586\u2585",
            "<gold>\u2582\u2582\u2582\u2582\u2583\u2585\u2586\u2587\u2586",
            "<gold>\u2582\u2582\u2582\u2582\u2582\u2583\u2585\u2586\u2587",
            "<gold>\u2582\u2582\u2582\u2582\u2583\u2585\u2586\u2587\u2586",
            "<gold>\u2582\u2582\u2582\u2583\u2585\u2586\u2587\u2586\u2585",
            "<gold>\u2582\u2583\u2585\u2586\u2587\u2586\u2585\u2583\u2582",
            "<gold>\u2583\u2585\u2586\u2587\u2586\u2585\u2583\u2582\u2582",
            "<gold>\u2585\u2586\u2587\u2586\u2585\u2583\u2582\u2582\u2582",
            "<gold>\u2586\u2587\u2586\u2585\u2583\u2582\u2582\u2582\u2582"),
        72);
  }

  public TransferActionBarConfig {
    joining = validateTemplate(joining, "joining");
    forceJoining = validateTemplate(forceJoining, "forceJoining");
    dequeued = validateTemplate(dequeued, "dequeued");
    progress = validateTemplate(progress, "progress");
    frames = List.copyOf(frames);
    if (frames.isEmpty() || frames.size() > MAX_FRAMES) {
      throw new IllegalArgumentException("transfer action-bar frames must contain 1-32 entries");
    }
    for (int index = 0; index < frames.size(); index++) {
      validateTemplate(frames.get(index), "frames[" + index + "]");
    }
    if (frameIntervalMillis < MIN_FRAME_INTERVAL_MILLIS
        || frameIntervalMillis > MAX_FRAME_INTERVAL_MILLIS) {
      throw new IllegalArgumentException("transfer action-bar frame interval must be 25-2000 ms");
    }
  }

  private static String validateTemplate(String value, String name) {
    if (value == null || value.isBlank() || value.length() > MAX_TEMPLATE_LENGTH) {
      throw new IllegalArgumentException(
          "transfer action-bar " + name + " must contain 1-" + MAX_TEMPLATE_LENGTH + " characters");
    }
    MiniMessage.miniMessage().deserialize(value);
    return value;
  }
}
