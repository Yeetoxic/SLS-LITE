package net.slimelabs.slslite.host;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.slimelabs.slslite.config.StorageStrategy;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostStorageCapabilityCheckerTest {

  @TempDir Path temporaryDirectory;

  @Test
  void reportsStorageCapabilitiesAndRemovesEveryProbe() throws Exception {
    List<HostCapability> capabilities =
        new HostStorageCapabilityChecker().check(temporaryDirectory);

    assertTrue(hasCapability(capabilities, "Instance filesystem"));
    assertTrue(hasCapability(capabilities, "Atomic directory moves"));
    assertTrue(hasCapability(capabilities, "Reflink COW"));
    assertTrue(hasCapability(capabilities, "Btrfs snapshot COW"));
    assertTrue(hasCapability(capabilities, "OverlayFS COW"));
    assertTrue(hasCapability(capabilities, "fuse-overlayfs COW"));
    assertTrue(
        capabilities.stream()
            .filter(capability -> capability.name().endsWith("COW"))
            .filter(capability -> capability.status() != HostCapabilityStatus.PASS)
            .allMatch(capability -> capability.status() == HostCapabilityStatus.INFO));
    boolean reflinkAvailable =
        capabilities.stream()
            .anyMatch(
                capability ->
                    capability.name().equals("Reflink COW")
                        && capability.status() == HostCapabilityStatus.PASS);
    boolean overlayAvailable =
        capabilities.stream()
            .anyMatch(
                capability ->
                    capability.name().equals("OverlayFS COW")
                        && capability.status() == HostCapabilityStatus.PASS);
    String expectedSelection =
        reflinkAvailable ? "reflink" : overlayAvailable ? "overlay" : "portable-copy";
    assertTrue(
        capabilities.stream()
            .anyMatch(
                capability ->
                    capability.name().equals("Selected COW strategy")
                        && capability
                            .detail()
                            .startsWith("requested=auto, selected=" + expectedSelection)));
    try (var files = Files.list(temporaryDirectory)) {
      assertFalse(files.anyMatch(path -> path.getFileName().toString().startsWith(".sls-")));
    }
  }

  @Test
  void rejectsExplicitOverlayWhenCapabilityIsUnavailable() {
    List<HostCapability> capabilities =
        new HostStorageCapabilityChecker().check(temporaryDirectory, StorageStrategy.OVERLAY);

    assertTrue(
        capabilities.stream()
            .anyMatch(
                capability ->
                    capability.name().equals("Selected COW strategy")
                        && capability.status() == HostCapabilityStatus.FAILURE
                        && capability.detail().startsWith("requested=overlay, selected=none")
                        && capability.detail().contains("not detected")));
  }

  @Test
  void reportsUnconfiguredExplicitSnapshotHookWithoutThrowing() {
    List<HostCapability> capabilities =
        new HostStorageCapabilityChecker().check(temporaryDirectory, StorageStrategy.SNAPSHOT_HOOK);

    assertTrue(
        capabilities.stream()
            .anyMatch(
                capability ->
                    capability.name().equals("Snapshot helper COW")
                        && capability.status() == HostCapabilityStatus.WARNING
                        && capability.detail().contains("configured helper probe failed")));
    assertTrue(
        capabilities.stream()
            .anyMatch(
                capability ->
                    capability.name().equals("Selected COW strategy")
                        && capability.status() == HostCapabilityStatus.FAILURE
                        && capability
                            .detail()
                            .startsWith("requested=snapshot-hook, selected=none")));
  }

  @Test
  void parsesLinuxFilesystemAndCapabilityInputs() {
    assertTrue(
        HostStorageCapabilityChecker.supportsFileSystem(
            "nodev\tsysfs\nnodev\toverlay\n\text4\n", "overlay"));
    assertFalse(
        HostStorageCapabilityChecker.supportsFileSystem("nodev\tsysfs\n\text4\n", "overlay"));
    assertTrue(
        HostStorageCapabilityChecker.hasEffectiveCapability(
            "Name:\tjava\nCapEff:\t0000000000200000\n", 21));
    assertFalse(
        HostStorageCapabilityChecker.hasEffectiveCapability(
            "Name:\tjava\nCapEff:\t0000000000000000\n", 21));
    assertFalse(HostStorageCapabilityChecker.hasEffectiveCapability("CapEff:\tnot-hex\n", 21));
  }

  private static boolean hasCapability(List<HostCapability> capabilities, String name) {
    return capabilities.stream().anyMatch(capability -> capability.name().equals(name));
  }
}
