package net.slimelabs.slslite.host;

import java.nio.file.Files;
import java.nio.file.Path;
import net.slimelabs.slslite.config.StorageStrategy;

/** Opt-in exact-path reflink capability and explicit-selection gate. */
public final class ReflinkSelectionRealKernelHarness {

  private ReflinkSelectionRealKernelHarness() {}

  public static void main(String[] arguments) throws Exception {
    if (arguments.length != 1) {
      throw new IllegalArgumentException("Expected one empty disposable reflink-capable path");
    }
    Path path = Path.of(arguments[0]).toAbsolutePath().normalize();
    Files.createDirectories(path);
    HostStorageCapabilityChecker.StorageCheck check =
        new HostStorageCapabilityChecker().checkWithSelection(path, StorageStrategy.REFLINK);
    StorageStrategy selected =
        check
            .selection()
            .selected()
            .orElseThrow(() -> new IllegalStateException("Reflink capability was unavailable"));
    if (selected != StorageStrategy.REFLINK) {
      throw new IllegalStateException(
          "Expected explicit reflink selection but selected " + selected.configValue());
    }
    System.out.println("SLS-LITE reflink exact-path selection PASS");
  }
}
