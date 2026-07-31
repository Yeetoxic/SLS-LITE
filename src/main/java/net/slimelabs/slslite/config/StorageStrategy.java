package net.slimelabs.slslite.config;

import java.util.Arrays;
import java.util.Locale;

public enum StorageStrategy {
  AUTO("auto"),
  COPY("copy"),
  REFLINK("reflink"),
  BTRFS("btrfs"),
  OVERLAY("overlay"),
  FUSE_OVERLAY("fuse-overlay"),
  SNAPSHOT_HOOK("snapshot-hook");

  private final String configValue;

  StorageStrategy(String configValue) {
    this.configValue = configValue;
  }

  public String configValue() {
    return configValue;
  }

  public String selectedName() {
    return this == COPY ? "portable-copy" : configValue;
  }

  public static StorageStrategy parse(String value) {
    String normalized = value == null ? "" : value.strip().toLowerCase(Locale.ROOT);
    return Arrays.stream(values())
        .filter(strategy -> strategy.configValue.equals(normalized))
        .findFirst()
        .orElseThrow(
            () ->
                new IllegalArgumentException(
                    "storage.strategy must be 'auto', 'copy', 'reflink', "
                        + "'btrfs', 'overlay', 'fuse-overlay', or "
                        + "'snapshot-hook'"));
  }
}
