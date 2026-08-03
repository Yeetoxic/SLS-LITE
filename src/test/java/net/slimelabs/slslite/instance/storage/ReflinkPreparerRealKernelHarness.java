package net.slimelabs.slslite.instance.storage;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;
import net.slimelabs.slslite.blueprint.BlueprintVolume;
import net.slimelabs.slslite.config.StorageStrategy;

/** Opt-in reflink lifecycle gate for an empty disposable reflink-capable directory. */
public final class ReflinkPreparerRealKernelHarness {

  private ReflinkPreparerRealKernelHarness() {}

  public static void main(String[] arguments) throws Exception {
    if (arguments.length != 1) {
      throw new IllegalArgumentException("Expected one empty disposable reflink-capable root");
    }
    Path root = Path.of(arguments[0]).toAbsolutePath().normalize();
    if (!Files.isDirectory(root)) {
      throw new IllegalArgumentException("Disposable reflink root does not exist: " + root);
    }
    try (var entries = Files.list(root)) {
      if (entries.findAny().isPresent()) {
        throw new IllegalArgumentException("Disposable reflink root must be empty: " + root);
      }
    }

    Path content = root.resolve("content");
    Path instances = content.resolve("instances");
    Path software = content.resolve("software/paper");
    Path world = content.resolve("worlds/adventure");
    Files.createDirectories(software);
    Files.createDirectories(world);
    Files.writeString(software.resolve("server.jar"), "server");
    Files.writeString(world.resolve("level.dat"), "source-v1");
    byte[] bulk = new byte[8 * 1_024 * 1_024];
    Arrays.fill(bulk, (byte) 0x5a);
    Files.write(world.resolve("bulk.bin"), bulk);

    InstanceDirectoryPreparer preparer =
        new InstanceDirectoryPreparer(
            instances, content, StorageStrategy.REFLINK, StorageStrategy.REFLINK);
    BlueprintVolume volume =
        new BlueprintVolume("world", "worlds/adventure", "/world", BlueprintVolume.Mode.COW);

    Path prepared = preparer.prepare("game.x82odk", software, List.of(volume));
    assertSharedExtents(world.resolve("bulk.bin"), prepared.resolve("world/bulk.bin"));
    Files.writeString(prepared.resolve("world/level.dat"), "instance");
    assertEquals("source-v1", Files.readString(world.resolve("level.dat")));

    Files.writeString(world.resolve("level.dat"), "source-v2");
    preparer.replace("game.x82odk", software, List.of(volume), ignored -> {});
    assertEquals("source-v2", Files.readString(prepared.resolve("world/level.dat")));
    assertSharedExtents(world.resolve("bulk.bin"), prepared.resolve("world/bulk.bin"));

    preparer.delete("game.x82odk");
    if (Files.exists(prepared)) {
      throw new IllegalStateException("Reflink instance survived transactional deletion");
    }
    assertEquals("source-v2", Files.readString(world.resolve("level.dat")));
    System.out.println("SLS-LITE reflink prepare/reset/delete PASS");
  }

  private static void assertSharedExtents(Path source, Path clone) throws Exception {
    String sourceMap = fiemap(source);
    String cloneMap = fiemap(clone);
    if (!hasSharedExtent(sourceMap) || !hasSharedExtent(cloneMap)) {
      throw new IllegalStateException(
          "Expected source and clone to report shared extents:\n" + sourceMap + "\n" + cloneMap);
    }
  }

  private static String fiemap(Path path) throws Exception {
    Process process =
        new ProcessBuilder("xfs_io", "-c", "fiemap -v", path.toString())
            .redirectErrorStream(true)
            .start();
    String output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    if (!process.waitFor(30, TimeUnit.SECONDS) || process.exitValue() != 0) {
      process.destroyForcibly();
      throw new IllegalStateException(
          "Unable to inspect reflink extents for " + path + ": " + output);
    }
    return output;
  }

  private static boolean hasSharedExtent(String fiemap) {
    return fiemap
        .lines()
        .anyMatch(
            line -> {
              if (line.contains("shared")) {
                return true;
              }
              String[] columns = line.strip().split("\\s+");
              if (columns.length == 0) {
                return false;
              }
              String flags = columns[columns.length - 1];
              try {
                return flags.startsWith("0x") && (Long.decode(flags) & 0x2000L) != 0;
              } catch (NumberFormatException ignored) {
                return false;
              }
            });
  }

  private static void assertEquals(String expected, String actual) {
    if (!expected.equals(actual)) {
      throw new IllegalStateException("Expected '" + expected + "' but found '" + actual + "'");
    }
  }
}
