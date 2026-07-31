package net.slimelabs.slslite.install;

import java.nio.file.Path;
import java.util.List;

public record SoftwareCacheCleanupReport(
    boolean dryRun,
    List<Entry> eligible,
    List<Entry> removed,
    int eligibleCount,
    int removedCount,
    int protectedCount,
    int tooNewCount,
    boolean scanLimitReached) {

  public SoftwareCacheCleanupReport {
    eligible = List.copyOf(eligible);
    removed = List.copyOf(removed);
    if (eligibleCount < eligible.size()
        || removedCount < removed.size()
        || protectedCount < 0
        || tooNewCount < 0) {
      throw new IllegalArgumentException("Software cleanup counts are inconsistent");
    }
  }

  public record Entry(InstallationKey key, Path directory) {}
}
