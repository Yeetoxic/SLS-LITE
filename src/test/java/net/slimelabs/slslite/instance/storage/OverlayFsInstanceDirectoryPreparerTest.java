package net.slimelabs.slslite.instance.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import net.slimelabs.slslite.blueprint.BlueprintVolume;
import net.slimelabs.slslite.config.StorageStrategy;
import net.slimelabs.slslite.instance.InstancePreparationException;
import net.slimelabs.slslite.instance.metadata.InstanceMetadataStore;
import net.slimelabs.slslite.instance.model.InstanceMetadata;
import net.slimelabs.slslite.instance.model.InstanceState;
import net.slimelabs.slslite.instance.reconcile.InstanceReconciler;
import net.slimelabs.slslite.instance.reconcile.InstanceReconciliationReport;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class OverlayFsInstanceDirectoryPreparerTest {

  @TempDir Path temporaryDirectory;

  private Path instances;
  private Path software;
  private Path lower;
  private SimulatedOverlayMounts mounts;
  private InstanceDirectoryPreparer preparer;

  @BeforeEach
  void setUp() throws Exception {
    instances = Files.createDirectory(temporaryDirectory.resolve("instances"));
    software = Files.createDirectory(temporaryDirectory.resolve("software"));
    Files.writeString(software.resolve("server.jar"), "jar");
    lower = Files.createDirectories(temporaryDirectory.resolve("worlds/game"));
    Files.writeString(lower.resolve("level.dat"), "template");
    mounts = new SimulatedOverlayMounts();
    OverlayFsLayerManager layerManager =
        new OverlayFsLayerManager(instances, temporaryDirectory, mounts);
    preparer =
        new InstanceDirectoryPreparer(
            instances,
            temporaryDirectory,
            Files::copy,
            milliseconds -> {},
            StorageStrategy.OVERLAY,
            layerManager);
  }

  @Test
  void preparesPrivateViewAndPersistsUpperAcrossSuspendResume() throws Exception {
    Path prepared = preparer.prepare("game.abc123", software, List.of(volume()));
    Path world = prepared.resolve("world");

    assertEquals("template", Files.readString(world.resolve("level.dat")));
    Files.writeString(world.resolve("level.dat"), "instance");
    Files.writeString(world.resolve("new.dat"), "private");

    preparer.suspend("game.abc123");
    assertEquals("template", Files.readString(lower.resolve("level.dat")));
    assertFalse(Files.exists(world.resolve("level.dat")));

    preparer.resume("game.abc123");
    assertEquals("instance", Files.readString(world.resolve("level.dat")));
    assertEquals("private", Files.readString(world.resolve("new.dat")));
    assertEquals("template", Files.readString(lower.resolve("level.dat")));
  }

  @Test
  void fuseStrategyUsesTheValidatedOverlayLayerLifecycle() throws Exception {
    OverlayFsLayerManager layerManager =
        new OverlayFsLayerManager(instances, temporaryDirectory, mounts);
    InstanceDirectoryPreparer fusePreparer =
        new InstanceDirectoryPreparer(
            instances,
            temporaryDirectory,
            Files::copy,
            milliseconds -> {},
            StorageStrategy.FUSE_OVERLAY,
            layerManager);

    Path prepared = fusePreparer.prepare("game.abc123", software, List.of(volume()));

    assertTrue(mounts.isMounted(prepared.resolve("world")));
    assertEquals("template", Files.readString(prepared.resolve("world/level.dat")));
    fusePreparer.delete("game.abc123");
    assertTrue(mounts.mounted.isEmpty());
  }

  @Test
  void deleteUnmountsBeforeRecursivelyRemovingInstance() throws Exception {
    Path prepared = preparer.prepare("game.abc123", software, List.of(volume()));
    assertTrue(mounts.isMounted(prepared.resolve("world")));

    preparer.delete("game.abc123");

    assertFalse(Files.exists(prepared));
    assertTrue(mounts.mounted.isEmpty());
    assertEquals("template", Files.readString(lower.resolve("level.dat")));
  }

  @Test
  void failedUnmountPreventsRecursiveDeletion() throws Exception {
    Path prepared = preparer.prepare("game.abc123", software, List.of(volume()));
    mounts.failUnmount = true;

    assertThrows(InstancePreparationException.class, () -> preparer.delete("game.abc123"));
    assertThrows(InstancePreparationException.class, () -> preparer.resume("game.abc123"));

    assertTrue(Files.isDirectory(prepared));
    assertTrue(mounts.isMounted(prepared.resolve("world")));
    assertEquals("template", Files.readString(lower.resolve("level.dat")));
  }

  @Test
  void missingManifestCannotHideLiveMountFromDeletion() throws Exception {
    Path prepared = preparer.prepare("game.abc123", software, List.of(volume()));
    Files.delete(prepared.resolve(OverlayFsLayerManager.MANIFEST_FILE));

    assertThrows(InstancePreparationException.class, () -> preparer.delete("game.abc123"));
    assertThrows(InstancePreparationException.class, () -> preparer.resume("game.abc123"));

    assertTrue(Files.isDirectory(prepared));
    assertTrue(mounts.isMounted(prepared.resolve("world")));
    assertEquals("template", Files.readString(lower.resolve("level.dat")));
  }

  @Test
  void persistentReplacementDropsOldUpperAndRemountsCleanSource() throws Exception {
    Path prepared = preparer.prepare("game.abc123", software, List.of(volume()));
    Files.writeString(prepared.resolve("world/level.dat"), "old-instance");
    preparer.suspend("game.abc123");
    Files.writeString(lower.resolve("level.dat"), "new-template");

    preparer.replace(
        "game.abc123",
        software,
        List.of(volume()),
        directory -> Files.writeString(directory.resolve("committed"), "yes"));

    assertTrue(mounts.mounted.isEmpty());
    preparer.resume("game.abc123");
    assertEquals("new-template", Files.readString(prepared.resolve("world/level.dat")));
    assertTrue(Files.isRegularFile(prepared.resolve("committed")));
  }

  @Test
  void readOnlyVolumeStillUsesPrivatePortableSnapshot() throws Exception {
    Path prepared =
        preparer.prepare(
            "game.abc123",
            software,
            List.of(
                new BlueprintVolume("world", "worlds/game", "/world", BlueprintVolume.Mode.RO)));

    assertEquals("template", Files.readString(prepared.resolve("world/level.dat")));
    assertFalse(Files.exists(prepared.resolve(OverlayFsLayerManager.MANIFEST_FILE)));
    assertTrue(mounts.mounted.isEmpty());
  }

  @Test
  void existingOverlayCanResumeAfterStrategyChangesToCopy() throws Exception {
    Path prepared = preparer.prepare("game.abc123", software, List.of(volume()));
    Files.writeString(prepared.resolve("world/level.dat"), "persistent");
    preparer.suspend("game.abc123");

    OverlayFsLayerManager recoveredLayers =
        new OverlayFsLayerManager(instances, temporaryDirectory, mounts);
    InstanceDirectoryPreparer copySelected =
        new InstanceDirectoryPreparer(
            instances,
            temporaryDirectory,
            Files::copy,
            milliseconds -> {},
            StorageStrategy.COPY,
            recoveredLayers);

    copySelected.resume("game.abc123");

    assertEquals("persistent", Files.readString(prepared.resolve("world/level.dat")));
    assertTrue(mounts.isMounted(prepared.resolve("world")));
  }

  @Test
  void startupReconciliationSuspendsPersistentOverlay() throws Exception {
    Path prepared = preparer.prepare("game.abc123", software, List.of(volume()));
    new InstanceMetadataStore(instances).write(prepared, metadata(true, InstanceState.READY));

    InstanceReconciliationReport report =
        new InstanceReconciler(preparer, LoggerFactory.getLogger(getClass())).reconcile();

    assertEquals(1, report.preservedPersistent());
    assertEquals(0, report.failures());
    assertTrue(mounts.mounted.isEmpty());
    InstanceMetadata recovered = new InstanceMetadataStore(instances).read(prepared).orElseThrow();
    assertEquals(InstanceState.STOPPED, recovered.state());
  }

  @Test
  void startupReconciliationUnmountsBeforeDeletingEphemeralOverlay() throws Exception {
    Path prepared = preparer.prepare("game.abc123", software, List.of(volume()));
    new InstanceMetadataStore(instances).write(prepared, metadata(false, InstanceState.PREPARING));

    InstanceReconciliationReport report =
        new InstanceReconciler(preparer, LoggerFactory.getLogger(getClass())).reconcile();

    assertEquals(1, report.removedEphemeral());
    assertEquals(0, report.failures());
    assertTrue(mounts.mounted.isEmpty());
    assertFalse(Files.exists(prepared));
  }

  private static BlueprintVolume volume() {
    return new BlueprintVolume("world", "worlds/game", "/world", BlueprintVolume.Mode.COW);
  }

  private static InstanceMetadata metadata(boolean persistent, InstanceState state) {
    return new InstanceMetadata(
        "game.abc123",
        "game",
        persistent,
        state,
        Instant.parse("2026-07-29T12:00:00Z"),
        null,
        null);
  }

  private static final class SimulatedOverlayMounts implements OverlayFsLayerManager.MountAdapter {

    private final Set<Path> mounted = new HashSet<>();
    private boolean failUnmount;

    @Override
    public void mount(
        List<Path> lowerDirectories, Path upperDirectory, Path workDirectory, Path target)
        throws IOException {
      clearDirectory(target);
      for (Path lower : lowerDirectories) {
        mergeFirstWins(lower, target);
      }
      copyReplacing(upperDirectory, target);
      mounted.add(target);
    }

    @Override
    public void unmount(Path target, Path upperDirectory, Path workDirectory) throws IOException {
      if (!mounted.contains(target)) {
        return;
      }
      if (failUnmount) {
        throw new IOException("simulated unmount failure");
      }
      clearDirectory(upperDirectory);
      copyReplacing(target, upperDirectory);
      clearDirectory(target);
      mounted.remove(target);
    }

    @Override
    public boolean isMounted(Path target) {
      return mounted.contains(target);
    }

    @Override
    public List<Path> mountPointsBeneath(Path root) {
      return mounted.stream().filter(path -> path.equals(root) || path.startsWith(root)).toList();
    }

    private static void mergeFirstWins(Path source, Path target) throws IOException {
      Files.walkFileTree(
          source,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs)
                throws IOException {
              Files.createDirectories(target.resolve(source.relativize(directory)));
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                throws IOException {
              Path destination = target.resolve(source.relativize(file));
              if (!Files.exists(destination)) {
                Files.copy(file, destination);
              }
              return FileVisitResult.CONTINUE;
            }
          });
    }

    private static void copyReplacing(Path source, Path target) throws IOException {
      Files.walkFileTree(
          source,
          new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs)
                throws IOException {
              Files.createDirectories(target.resolve(source.relativize(directory)));
              return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                throws IOException {
              Files.copy(
                  file,
                  target.resolve(source.relativize(file)),
                  java.nio.file.StandardCopyOption.REPLACE_EXISTING);
              return FileVisitResult.CONTINUE;
            }
          });
    }

    private static void clearDirectory(Path directory) throws IOException {
      if (!Files.exists(directory)) {
        Files.createDirectories(directory);
        return;
      }
      try (var paths = Files.walk(directory)) {
        for (Path path : paths.sorted(java.util.Comparator.reverseOrder()).toList()) {
          if (!path.equals(directory)) {
            Files.delete(path);
          }
        }
      }
    }
  }
}
