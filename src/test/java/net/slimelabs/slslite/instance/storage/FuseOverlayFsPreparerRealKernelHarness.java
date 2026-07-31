package net.slimelabs.slslite.instance.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.slimelabs.slslite.blueprint.BlueprintVolume;
import net.slimelabs.slslite.config.StorageStrategy;

/**
 * Opt-in rootless overlay lifecycle harness for disposable Linux with FUSE.
 */
public final class FuseOverlayFsPreparerRealKernelHarness {

  private static final String INSTANCE_ID = "game.abc123";

  private FuseOverlayFsPreparerRealKernelHarness() {}

  public static void main(String[] arguments) throws Exception {
    if (arguments.length != 1) {
      throw new IllegalArgumentException("Expected one disposable test-root argument");
    }
    Path root = Path.of(arguments[0]).toAbsolutePath().normalize();
    if (!Files.isDirectory(root) || !isEmpty(root)) {
      throw new IllegalArgumentException("Test root must be an existing empty directory: " + root);
    }

    Path content = Files.createDirectory(root.resolve("content"));
    Path instances = Files.createDirectory(content.resolve("instances"));
    Path software = Files.createDirectory(content.resolve("software"));
    Files.writeString(software.resolve("server.jar"), "software");
    Path lower = Files.createDirectories(content.resolve("worlds/game"));
    Files.writeString(lower.resolve("level.dat"), "template");
    BlueprintVolume volume =
        new BlueprintVolume("world", "worlds/game", "/world", BlueprintVolume.Mode.COW);
    InstanceDirectoryPreparer preparer =
        new InstanceDirectoryPreparer(
            instances, content, StorageStrategy.FUSE_OVERLAY, StorageStrategy.FUSE_OVERLAY);

    try {
      Path prepared = preparer.prepare(INSTANCE_ID, software, List.of(volume));
      requireContent(prepared.resolve("world/level.dat"), "template");
      Files.writeString(prepared.resolve("world/level.dat"), "private-before-reset");
      preparer =
          new InstanceDirectoryPreparer(
              instances, content, StorageStrategy.FUSE_OVERLAY, StorageStrategy.FUSE_OVERLAY);
      preparer.suspend(INSTANCE_ID);
      requireContent(lower.resolve("level.dat"), "template");
      preparer.resume(INSTANCE_ID);
      requireContent(prepared.resolve("world/level.dat"), "private-before-reset");

      preparer.replace(
          INSTANCE_ID,
          software,
          List.of(volume),
          directory -> Files.writeString(directory.resolve("committed"), "yes"));
      preparer.resume(INSTANCE_ID);
      requireContent(prepared.resolve("world/level.dat"), "template");
      requireContent(prepared.resolve("committed"), "yes");

      preparer.delete(INSTANCE_ID);
      if (Files.exists(prepared)) {
        throw new IllegalStateException("Prepared FUSE instance remained after safe delete");
      }
      requireContent(lower.resolve("level.dat"), "template");
      System.out.println("SLS-LITE fuse-overlayfs prepare/reset/delete PASS");
    } finally {
      if (Files.exists(instances.resolve(INSTANCE_ID))) {
        try {
          preparer.delete(INSTANCE_ID);
        } catch (Exception ignored) {
          // The disposable caller retains failures for diagnosis.
        }
      }
    }
  }

  private static boolean isEmpty(Path directory) throws Exception {
    try (var entries = Files.list(directory)) {
      return entries.findAny().isEmpty();
    }
  }

  private static void requireContent(Path file, String expected) throws Exception {
    String actual = Files.readString(file);
    if (!expected.equals(actual)) {
      throw new IllegalStateException(
          file + " contained '" + actual + "', expected '" + expected + "'");
    }
  }
}
