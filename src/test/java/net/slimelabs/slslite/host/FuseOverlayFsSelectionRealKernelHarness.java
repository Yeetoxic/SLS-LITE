package net.slimelabs.slslite.host;

import java.nio.file.Files;
import java.nio.file.Path;
import net.slimelabs.slslite.config.StorageStrategy;

/**
 * Opt-in exact-path FUSE capability gate for disposable Linux.
 */
public final class FuseOverlayFsSelectionRealKernelHarness {

  private FuseOverlayFsSelectionRealKernelHarness() {}

  public static void main(String[] arguments) throws Exception {
    if (arguments.length != 1) {
      throw new IllegalArgumentException("Expected one disposable test-root argument");
    }
    Path root = Path.of(arguments[0]).toAbsolutePath().normalize();
    if (!Files.isDirectory(root) || !isEmpty(root)) {
      throw new IllegalArgumentException("Test root must be an existing empty directory: " + root);
    }
    HostStorageCapabilityChecker.StorageCheck check =
        new HostStorageCapabilityChecker().checkWithSelection(root, StorageStrategy.FUSE_OVERLAY);
    StorageStrategy selected =
        check
            .selection()
            .selected()
            .orElseThrow(
                () ->
                    new IllegalStateException("Explicit fuse-overlayfs selection was unavailable"));
    if (selected != StorageStrategy.FUSE_OVERLAY) {
      throw new IllegalStateException(
          "Expected fuse-overlayfs but selected " + selected.configValue());
    }
    System.out.println("SLS-LITE fuse-overlayfs exact-path selection PASS");
  }

  private static boolean isEmpty(Path directory) throws Exception {
    try (var entries = Files.list(directory)) {
      return entries.findAny().isEmpty();
    }
  }
}
