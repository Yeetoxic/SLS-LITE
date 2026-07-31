package net.slimelabs.slslite.host;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileStore;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import net.slimelabs.slslite.config.StorageConfig;
import net.slimelabs.slslite.config.StorageStrategy;
import net.slimelabs.slslite.instance.storage.SnapshotHookClient;

final class HostStorageCapabilityChecker {

  private static final long COMMAND_TIMEOUT_SECONDS = 5;
  private static final int CAP_SYS_ADMIN = 21;
  private static final StorageProbeCache SHARED_PROBE_CACHE = new StorageProbeCache(64);
  private static final EnumSet<StorageStrategy> IMPLEMENTED_STRATEGIES =
      EnumSet.of(
          StorageStrategy.COPY,
          StorageStrategy.REFLINK,
          StorageStrategy.BTRFS,
          StorageStrategy.OVERLAY,
          StorageStrategy.FUSE_OVERLAY,
          StorageStrategy.SNAPSHOT_HOOK);
  private final StorageProbeCache probeCache;

  HostStorageCapabilityChecker() {
    this(SHARED_PROBE_CACHE);
  }

  HostStorageCapabilityChecker(StorageProbeCache probeCache) {
    this.probeCache = probeCache;
  }

  List<HostCapability> check(Path instancesDirectory, StorageStrategy requestedStrategy) {
    return checkWithSelection(instancesDirectory, requestedStrategy).capabilities();
  }

  StorageCheck checkWithSelection(Path instancesDirectory, StorageStrategy requestedStrategy) {
    return checkWithSelection(instancesDirectory, new StorageConfig(requestedStrategy));
  }

  StorageCheck checkWithSelection(Path instancesDirectory, StorageConfig storage) {
    StorageStrategy requestedStrategy = storage.strategy();
    List<HostCapability> results = new ArrayList<>();
    results.add(checkFileSystem(instancesDirectory));
    results.add(checkAtomicMove(instancesDirectory));
    StorageProbeCache.Identity identity = identify(instancesDirectory);
    Map<StorageStrategy, HostCapability> cached =
        identity == null ? Map.of() : probeCache.get(identity);
    EnumMap<StorageStrategy, HostCapability> successful = new EnumMap<>(StorageStrategy.class);
    EnumSet<StorageStrategy> detected = EnumSet.of(StorageStrategy.COPY);
    ProbeResult reflink =
        cachedOrProbe(cached, StorageStrategy.REFLINK, () -> checkReflink(instancesDirectory));
    results.add(capabilityForRequest(reflink, requestedStrategy, StorageStrategy.REFLINK));
    addWhenSupported(detected, StorageStrategy.REFLINK, reflink);
    remember(successful, StorageStrategy.REFLINK, reflink);
    ProbeResult btrfs =
        cachedOrProbe(cached, StorageStrategy.BTRFS, () -> checkBtrfs(instancesDirectory));
    results.add(capabilityForRequest(btrfs, requestedStrategy, StorageStrategy.BTRFS));
    addWhenSupported(detected, StorageStrategy.BTRFS, btrfs);
    remember(successful, StorageStrategy.BTRFS, btrfs);
    ProbeResult overlay =
        cachedOrProbe(
            cached,
            StorageStrategy.OVERLAY,
            () ->
                checkOverlayFs(
                    instancesDirectory,
                    requestedStrategy == StorageStrategy.AUTO
                        || requestedStrategy == StorageStrategy.OVERLAY));
    results.add(capabilityForRequest(overlay, requestedStrategy, StorageStrategy.OVERLAY));
    addWhenSupported(detected, StorageStrategy.OVERLAY, overlay);
    remember(successful, StorageStrategy.OVERLAY, overlay);
    ProbeResult fuseOverlay =
        cachedOrProbe(
            cached,
            StorageStrategy.FUSE_OVERLAY,
            () ->
                checkFuseOverlayFs(
                    instancesDirectory,
                    requestedStrategy == StorageStrategy.AUTO
                        || requestedStrategy == StorageStrategy.FUSE_OVERLAY));
    results.add(capabilityForRequest(fuseOverlay, requestedStrategy, StorageStrategy.FUSE_OVERLAY));
    addWhenSupported(detected, StorageStrategy.FUSE_OVERLAY, fuseOverlay);
    remember(successful, StorageStrategy.FUSE_OVERLAY, fuseOverlay);
    if (identity != null) {
      probeCache.put(identity, successful);
    }
    ProbeResult snapshotHook = checkSnapshotHook(instancesDirectory, storage);
    results.add(
        capabilityForRequest(snapshotHook, requestedStrategy, StorageStrategy.SNAPSHOT_HOOK));
    addWhenSupported(detected, StorageStrategy.SNAPSHOT_HOOK, snapshotHook);
    StorageStrategySelection selection =
        new StorageStrategySelector().select(requestedStrategy, detected, IMPLEMENTED_STRATEGIES);
    results.add(selectionCapability(instancesDirectory, selection));
    return new StorageCheck(List.copyOf(results), selection);
  }

  private StorageProbeCache.Identity identify(Path directory) {
    try {
      return probeCache.identify(directory);
    } catch (IOException | RuntimeException ignored) {
      return null;
    }
  }

  private static ProbeResult cachedOrProbe(
      Map<StorageStrategy, HostCapability> cached, StorageStrategy strategy, Probe probe) {
    HostCapability capability = cached.get(strategy);
    return capability == null ? probe.run() : supported(capability);
  }

  private static void remember(
      Map<StorageStrategy, HostCapability> successful,
      StorageStrategy strategy,
      ProbeResult result) {
    if (result.supported()) {
      successful.put(strategy, result.capability());
    }
  }

  private static ProbeResult checkSnapshotHook(Path instancesDirectory, StorageConfig storage) {
    if (storage.strategy() != StorageStrategy.SNAPSHOT_HOOK) {
      return unsupported(
          warning("Snapshot helper COW", "explicit-only operator helper is not configured"));
    }
    try {
      new SnapshotHookClient(storage.snapshotHookExecutable(), storage.snapshotHookTimeoutSeconds())
          .probe(instancesDirectory);
      return supported(
          pass(
              "Snapshot helper COW",
              "configured shell-free helper completed the bounded "
                  + SnapshotHookClient.PROTOCOL
                  + " handshake"));
    } catch (IOException | RuntimeException exception) {
      return unsupported(
          warning("Snapshot helper COW", "configured helper probe failed: " + message(exception)));
    }
  }

  List<HostCapability> check(Path instancesDirectory) {
    return check(instancesDirectory, StorageStrategy.AUTO);
  }

  private static HostCapability checkFileSystem(Path instancesDirectory) {
    try {
      Files.createDirectories(instancesDirectory);
      FileStore store = Files.getFileStore(instancesDirectory);
      long usable = store.getUsableSpace();
      long total = store.getTotalSpace();
      String detail =
          "type="
              + valueOrUnknown(store.type())
              + ", name="
              + valueOrUnknown(store.name())
              + ", usable="
              + gibibytes(usable)
              + "/"
              + gibibytes(total)
              + " GiB"
              + ", attributes="
              + attributeViews(store);
      return pass("Instance filesystem", detail);
    } catch (IOException | RuntimeException exception) {
      return warning("Instance filesystem", message(exception));
    }
  }

  private static HostCapability checkAtomicMove(Path instancesDirectory) {
    Path probe = null;
    try {
      Files.createDirectories(instancesDirectory);
      probe = Files.createTempDirectory(instancesDirectory, ".sls-atomic-probe-");
      Path source = Files.createDirectory(probe.resolve("source"));
      Files.writeString(source.resolve("marker"), "atomic");
      Path destination = probe.resolve("destination");
      Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
      if (!Files.isRegularFile(destination.resolve("marker"))) {
        return warning(
            "Atomic directory moves",
            "The filesystem accepted the move but the probe marker was missing");
      }
      return pass(
          "Atomic directory moves", "same-filesystem atomic directory replacement is available");
    } catch (AtomicMoveNotSupportedException exception) {
      return warning(
          "Atomic directory moves",
          "not supported; transactional operations use verified "
              + "same-filesystem fallback moves");
    } catch (IOException | RuntimeException exception) {
      return warning("Atomic directory moves", message(exception));
    } finally {
      deleteTreeBestEffort(probe);
    }
  }

  private static ProbeResult checkReflink(Path instancesDirectory) {
    if (isWindows()) {
      return unsupported(
          warning(
              "Reflink COW",
              "no safe Windows reflink probe is implemented; portable copy selected"));
    }
    Path probe = null;
    Process process = null;
    try {
      Files.createDirectories(instancesDirectory);
      probe = Files.createTempDirectory(instancesDirectory, ".sls-reflink-probe-");
      Path source = probe.resolve("source");
      Path clone = probe.resolve("clone");
      Files.writeString(source, "source");
      process =
          new ProcessBuilder("cp", "--reflink=always", "--", source.toString(), clone.toString())
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .start();
      if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        return unsupported(warning("Reflink COW", "clone probe timed out"));
      }
      if (process.exitValue() != 0 || !Files.isRegularFile(clone)) {
        return unsupported(
            warning(
                "Reflink COW",
                "filesystem clone rejected or cp lacks --reflink support; "
                    + "portable copy selected"));
      }
      Files.writeString(clone, "clone");
      if (!"source".equals(Files.readString(source))) {
        return unsupported(
            warning("Reflink COW", "clone isolation probe failed; portable copy selected"));
      }
      return supported(
          pass(
              "Reflink COW",
              "same-filesystem clone and write-isolation probe passed; "
                  + "reflink preparation is eligible"));
    } catch (IOException exception) {
      return unsupported(
          warning(
              "Reflink COW",
              "probe unavailable (" + message(exception) + "); portable copy selected"));
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return unsupported(warning("Reflink COW", "clone probe was interrupted"));
    } finally {
      if (process != null && process.isAlive()) {
        process.destroyForcibly();
      }
      deleteTreeBestEffort(probe);
    }
  }

  private static ProbeResult checkBtrfs(Path instancesDirectory) {
    try {
      Files.createDirectories(instancesDirectory);
      FileStore store = Files.getFileStore(instancesDirectory);
      if (!"btrfs".equalsIgnoreCase(store.type())) {
        return unsupported(
            warning(
                "Btrfs snapshot COW",
                "instance storage is "
                    + valueOrUnknown(store.type())
                    + ", not Btrfs; portable copy selected"));
      }
      BtrfsSnapshotProbe.Result probe = new BtrfsSnapshotProbe().probe(instancesDirectory);
      if (!probe.supported()) {
        return unsupported(
            warning("Btrfs snapshot COW", probe.detail() + "; portable copy selected"));
      }
      return supported(
          pass("Btrfs snapshot COW", probe.detail() + "; Btrfs snapshot preparation is eligible"));
    } catch (IOException | RuntimeException exception) {
      return unsupported(warning("Btrfs snapshot COW", "probe unavailable: " + message(exception)));
    }
  }

  private static ProbeResult checkOverlayFs(Path instancesDirectory, boolean runContainedProbe) {
    if (!isLinux()) {
      return unsupported(warning("OverlayFS COW", "requires Linux; portable copy selected"));
    }
    try {
      String fileSystems = Files.readString(Path.of("/proc/filesystems"));
      if (!supportsFileSystem(fileSystems, "overlay")) {
        return unsupported(
            warning(
                "OverlayFS COW",
                "kernel overlay filesystem is unavailable; portable copy selected"));
      }
      String processStatus = Files.readString(Path.of("/proc/self/status"));
      if (!hasEffectiveCapability(processStatus, CAP_SYS_ADMIN)) {
        return unsupported(
            warning(
                "OverlayFS COW",
                "kernel driver is available but this process lacks CAP_SYS_ADMIN; "
                    + "portable copy selected"));
      }
      if (!runContainedProbe) {
        return unsupported(
            warning(
                "OverlayFS COW",
                "kernel driver and CAP_SYS_ADMIN are available; contained "
                    + "mount probing is deferred until OverlayFS is "
                    + "explicitly requested"));
      }
      OverlayFsMountProbe.Result probe = new OverlayFsMountProbe().probe(instancesDirectory);
      if (!probe.supported()) {
        return unsupported(warning("OverlayFS COW", probe.detail()));
      }
      return supported(
          pass("OverlayFS COW", probe.detail() + "; OverlayFS preparation is eligible"));
    } catch (IOException | RuntimeException exception) {
      return unsupported(warning("OverlayFS COW", "probe unavailable: " + message(exception)));
    }
  }

  private static ProbeResult checkFuseOverlayFs(
      Path instancesDirectory, boolean runContainedProbe) {
    if (!isLinux()) {
      return unsupported(warning("fuse-overlayfs COW", "requires Linux; portable copy selected"));
    }
    Path fuseDevice = Path.of("/dev/fuse");
    if (!Files.exists(fuseDevice)
        || !Files.isReadable(fuseDevice)
        || !Files.isWritable(fuseDevice)) {
      return unsupported(
          warning(
              "fuse-overlayfs COW",
              "/dev/fuse is unavailable to this process; portable copy selected"));
    }
    Process process = null;
    try {
      process =
          new ProcessBuilder("fuse-overlayfs", "--version")
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .start();
      if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        return unsupported(warning("fuse-overlayfs COW", "fuse-overlayfs version probe timed out"));
      }
      if (process.exitValue() != 0) {
        return unsupported(
            warning(
                "fuse-overlayfs COW",
                "fuse-overlayfs version probe failed; portable copy selected"));
      }
      if (runContainedProbe) {
        OverlayFsMountProbe.Result probe = new FuseOverlayFsMountProbe().probe(instancesDirectory);
        if (!probe.supported()) {
          return unsupported(warning("fuse-overlayfs COW", probe.detail()));
        }
        return supported(
            pass(
                "fuse-overlayfs COW",
                probe.detail() + "; rootless overlay preparation is eligible"));
      }
      return unsupported(
          warning(
              "fuse-overlayfs COW",
              "/dev/fuse and fuse-overlayfs are available; contained "
                  + "mount probing is deferred until the strategy "
                  + "is explicitly requested"));
    } catch (IOException exception) {
      return unsupported(
          warning(
              "fuse-overlayfs COW",
              "probe unavailable (" + message(exception) + "); portable copy selected"));
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      return unsupported(warning("fuse-overlayfs COW", "version probe was interrupted"));
    } finally {
      if (process != null && process.isAlive()) {
        process.destroyForcibly();
      }
    }
  }

  private static void addWhenSupported(
      EnumSet<StorageStrategy> detected, StorageStrategy strategy, ProbeResult result) {
    if (result.supported()) {
      detected.add(strategy);
    }
  }

  private static HostCapability capabilityForRequest(
      ProbeResult result, StorageStrategy requested, StorageStrategy strategy) {
    HostCapability capability = result.capability();
    if (result.supported()
        || capability.status() != HostCapabilityStatus.WARNING
        || requested == strategy) {
      return capability;
    }
    return new HostCapability(capability.name(), HostCapabilityStatus.INFO, capability.detail());
  }

  private static HostCapability selectionCapability(
      Path instancesDirectory, StorageStrategySelection selection) {
    String selected = selection.selected().map(StorageStrategy::selectedName).orElse("none");
    String detail =
        "requested="
            + selection.requested().configValue()
            + ", selected="
            + selected
            + ", path="
            + instancesDirectory.toAbsolutePath().normalize()
            + " ("
            + selection.detail()
            + ")";
    return new HostCapability(
        "Selected COW strategy",
        selection.available() ? HostCapabilityStatus.PASS : HostCapabilityStatus.FAILURE,
        detail);
  }

  private static ProbeResult supported(HostCapability capability) {
    return new ProbeResult(capability, true);
  }

  private static ProbeResult unsupported(HostCapability capability) {
    return new ProbeResult(capability, false);
  }

  static boolean supportsFileSystem(String fileSystems, String expected) {
    if (fileSystems == null || expected == null || expected.isBlank()) {
      return false;
    }
    return fileSystems
        .lines()
        .map(String::strip)
        .map(line -> line.startsWith("nodev") ? line.substring("nodev".length()).strip() : line)
        .anyMatch(expected::equals);
  }

  static boolean hasEffectiveCapability(String processStatus, int capability) {
    if (processStatus == null || capability < 0) {
      return false;
    }
    String prefix = "CapEff:";
    return processStatus
        .lines()
        .map(String::strip)
        .filter(line -> line.startsWith(prefix))
        .map(line -> line.substring(prefix.length()).strip())
        .findFirst()
        .map(
            value -> {
              try {
                java.math.BigInteger mask = new java.math.BigInteger(value, 16);
                return mask.testBit(capability);
              } catch (NumberFormatException exception) {
                return false;
              }
            })
        .orElse(false);
  }

  private static String attributeViews(FileStore store) {
    List<String> views = new ArrayList<>();
    for (String view : List.of("posix", "dos", "acl", "user")) {
      if (store.supportsFileAttributeView(view)) {
        views.add(view);
      }
    }
    return views.isEmpty() ? "basic" : String.join(",", views);
  }

  private static String gibibytes(long bytes) {
    return String.format(Locale.ROOT, "%.1f", Math.max(0L, bytes) / (1024.0d * 1024.0d * 1024.0d));
  }

  private static String valueOrUnknown(String value) {
    return value == null || value.isBlank() ? "unknown" : value;
  }

  private static boolean isWindows() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
  }

  private static boolean isLinux() {
    return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("linux");
  }

  private static HostCapability pass(String name, String detail) {
    return new HostCapability(name, HostCapabilityStatus.PASS, detail);
  }

  private static HostCapability warning(String name, String detail) {
    return new HostCapability(name, HostCapabilityStatus.WARNING, detail);
  }

  private static String message(Throwable throwable) {
    return throwable.getMessage() == null
        ? throwable.getClass().getSimpleName()
        : throwable.getMessage();
  }

  private static void deleteTreeBestEffort(Path root) {
    if (root == null || !Files.exists(root)) {
      return;
    }
    try (var paths = Files.walk(root)) {
      paths
          .sorted(Comparator.reverseOrder())
          .forEach(
              path -> {
                try {
                  Files.deleteIfExists(path);
                } catch (IOException ignored) {
                  // A failed optional probe must not prevent plugin startup.
                }
              });
    } catch (IOException ignored) {
      // A failed optional probe must not prevent plugin startup.
    }
  }

  private record ProbeResult(HostCapability capability, boolean supported) {}

  @FunctionalInterface
  private interface Probe {
    ProbeResult run();
  }

  record StorageCheck(List<HostCapability> capabilities, StorageStrategySelection selection) {

    StorageCheck {
      capabilities = List.copyOf(capabilities);
    }
  }
}
