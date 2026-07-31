package net.slimelabs.slslite.install;

import java.nio.file.Path;
import java.util.List;

public record SoftwareCacheCleanupReport(
    boolean dryRun,
    List<Entry> eligible,
    List<Entry> removed,
    int protectedCount,
    int tooNewCount) {

  public SoftwareCacheCleanupReport {
    eligible = List.copyOf(eligible);
    removed = List.copyOf(removed);
  }

  public record Entry(InstallationKey key, Path directory) {}
}
