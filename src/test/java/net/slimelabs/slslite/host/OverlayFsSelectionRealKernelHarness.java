package net.slimelabs.slslite.host;

import java.nio.file.Files;
import java.nio.file.Path;
import net.slimelabs.slslite.config.StorageStrategy;

/**
 * Opt-in auto-selection harness for a disposable privileged Linux filesystem.
 */
public final class OverlayFsSelectionRealKernelHarness {

  private OverlayFsSelectionRealKernelHarness() {}

  public static void main(String[] arguments) throws Exception {
    if (arguments.length != 1) {
      throw new IllegalArgumentException("Expected one disposable test-root argument");
    }
    Path root = Path.of(arguments[0]).toAbsolutePath().normalize();
    if (!Files.isDirectory(root) || !isEmpty(root)) {
      throw new IllegalArgumentException("Test root must be an existing empty directory: " + root);
    }

    HostStorageCapabilityChecker.StorageCheck check =
        new HostStorageCapabilityChecker().checkWithSelection(root, StorageStrategy.AUTO);
    StorageStrategy selected =
        check
            .selection()
            .selected()
            .orElseThrow(
                () -> new IllegalStateException("Automatic storage selection was unavailable"));
    if (selected != StorageStrategy.OVERLAY) {
      throw new IllegalStateException(
          "Expected OverlayFS on tmpfs after its contained probe, got " + selected.configValue());
    }
    HostCapability overlay =
        check.capabilities().stream()
            .filter(capability -> capability.name().equals("OverlayFS COW"))
            .findFirst()
            .orElseThrow();
    if (overlay.status() != HostCapabilityStatus.PASS) {
      throw new IllegalStateException("OverlayFS capability did not pass: " + overlay.detail());
    }
    System.out.println("SLS-LITE OverlayFS auto-selection PASS");
  }

  private static boolean isEmpty(Path directory) throws Exception {
    try (var entries = Files.list(directory)) {
      return entries.findAny().isEmpty();
    }
  }
}
