package net.slimelabs.slslite.config;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.List;

public record StorageConfig(
    StorageStrategy strategy,
    Path snapshotHookExecutable,
    int snapshotHookTimeoutSeconds,
    List<StorageStrategy> autoPriority,
    int copyParallelism) {

  public static final int AUTO_COPY_PARALLELISM = 0;
  public static final int MAX_COPY_PARALLELISM = 16;
  public static final List<StorageStrategy> DEFAULT_AUTO_PRIORITY =
      List.of(
          StorageStrategy.REFLINK,
          StorageStrategy.BTRFS,
          StorageStrategy.OVERLAY,
          StorageStrategy.FUSE_OVERLAY,
          StorageStrategy.COPY);

  public StorageConfig(StorageStrategy strategy) {
    this(strategy, null, 30, DEFAULT_AUTO_PRIORITY, AUTO_COPY_PARALLELISM);
  }

  public StorageConfig(
      StorageStrategy strategy, Path snapshotHookExecutable, int snapshotHookTimeoutSeconds) {
    this(
        strategy,
        snapshotHookExecutable,
        snapshotHookTimeoutSeconds,
        DEFAULT_AUTO_PRIORITY,
        AUTO_COPY_PARALLELISM);
  }

  public StorageConfig {
    if (strategy == null) {
      throw new IllegalArgumentException("storage strategy is required");
    }
    if (snapshotHookTimeoutSeconds < 1 || snapshotHookTimeoutSeconds > 300) {
      throw new IllegalArgumentException("snapshot hook timeout must be between 1 and 300 seconds");
    }
    if (snapshotHookExecutable != null) {
      snapshotHookExecutable = snapshotHookExecutable.toAbsolutePath().normalize();
    }
    if (autoPriority == null || autoPriority.isEmpty()) {
      throw new IllegalArgumentException("storage.auto_priority must not be empty");
    }
    autoPriority = List.copyOf(autoPriority);
    if (new HashSet<>(autoPriority).size() != autoPriority.size()) {
      throw new IllegalArgumentException("storage.auto_priority must not contain duplicates");
    }
    for (StorageStrategy candidate : autoPriority) {
      if (candidate == null
          || candidate == StorageStrategy.AUTO
          || candidate == StorageStrategy.SNAPSHOT_HOOK) {
        throw new IllegalArgumentException(
            "storage.auto_priority may contain only copy, reflink, btrfs, overlay, or fuse-overlay");
      }
    }
    if (copyParallelism < AUTO_COPY_PARALLELISM || copyParallelism > MAX_COPY_PARALLELISM) {
      throw new IllegalArgumentException(
          "storage.copy_parallelism must be auto or an integer from 1 to " + MAX_COPY_PARALLELISM);
    }
  }

  public int resolvedCopyParallelism() {
    if (copyParallelism != AUTO_COPY_PARALLELISM) {
      return copyParallelism;
    }
    return Math.max(1, Math.min(4, Runtime.getRuntime().availableProcessors()));
  }

  public boolean permitsPortableFallback() {
    return strategy == StorageStrategy.AUTO && autoPriority.contains(StorageStrategy.COPY);
  }
}
