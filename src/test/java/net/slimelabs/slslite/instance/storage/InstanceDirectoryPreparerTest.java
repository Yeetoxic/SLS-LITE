package net.slimelabs.slslite.instance.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import net.slimelabs.slslite.blueprint.BlueprintCopy;
import net.slimelabs.slslite.blueprint.BlueprintVolume;
import net.slimelabs.slslite.config.StorageStrategy;
import net.slimelabs.slslite.instance.InstancePreparationException;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstanceDirectoryPreparerTest {

  @TempDir Path temporaryDirectory;

  @Test
  void createsIndependentDirectoryCopy() throws Exception {
    Path source = createSource();
    Path instances = temporaryDirectory.resolve("instances");
    InstanceDirectoryPreparer preparer = new InstanceDirectoryPreparer(instances);

    Path prepared = preparer.prepare("game.x82odk", source);
    Files.writeString(prepared.resolve("config/settings.yml"), "changed");

    assertEquals("original", Files.readString(source.resolve("config/settings.yml")));
    assertEquals("server", Files.readString(prepared.resolve("server.jar")));
  }

  @Test
  void copiesCowVolumeIntoInstanceTarget() throws Exception {
    Path source = createSource();
    Path world = temporaryDirectory.resolve("worlds/minigames/spleef");
    Files.createDirectories(world.resolve("region"));
    Files.writeString(world.resolve("level.dat"), "world");
    Files.writeString(world.resolve("region/r.0.0.mca"), "region");
    InstanceDirectoryPreparer preparer =
        new InstanceDirectoryPreparer(temporaryDirectory.resolve("instances"), temporaryDirectory);

    Path prepared =
        preparer.prepare(
            "game.x82odk", source, List.of(volume("worlds/minigames/spleef", "/world")));
    Files.writeString(prepared.resolve("world/level.dat"), "instance change");

    assertEquals("world", Files.readString(world.resolve("level.dat")));
    assertEquals("region", Files.readString(prepared.resolve("world/region/r.0.0.mca")));
  }

  @Test
  void mergesCowVolumesAtSameTargetWithFirstSourcePrecedence() throws Exception {
    Path source = createSource();
    Path first = temporaryDirectory.resolve("plugins/first");
    Path second = temporaryDirectory.resolve("plugins/second");
    Files.createDirectories(first.resolve("shared"));
    Files.createDirectories(second.resolve("shared"));
    Files.writeString(first.resolve("first.jar"), "first");
    Files.writeString(second.resolve("second.jar"), "second");
    Files.writeString(first.resolve("shared/config.yml"), "first wins");
    Files.writeString(second.resolve("shared/config.yml"), "second loses");
    Files.writeString(first.resolve("file-wins"), "file");
    Files.createDirectories(second.resolve("file-wins"));
    Files.writeString(second.resolve("file-wins/hidden.txt"), "hidden");
    InstanceDirectoryPreparer preparer =
        new InstanceDirectoryPreparer(temporaryDirectory.resolve("instances"), temporaryDirectory);

    Path prepared =
        preparer.prepare(
            "game.x82odk",
            source,
            List.of(volume("plugins/first", "/plugins"), volume("plugins/second", "/plugins")));

    assertEquals("first", Files.readString(prepared.resolve("plugins/first.jar")));
    assertEquals("second", Files.readString(prepared.resolve("plugins/second.jar")));
    assertEquals("first wins", Files.readString(prepared.resolve("plugins/shared/config.yml")));
    assertTrue(Files.isRegularFile(prepared.resolve("plugins/file-wins")));
    assertFalse(Files.exists(prepared.resolve("plugins/file-wins/hidden.txt")));
  }

  @Test
  void copiesRoVolumeAsPrivateLocalSnapshot() throws Exception {
    Path source = createSource();
    Path shared = createVolumeSource("plugins/shared");
    Files.writeString(shared.resolve("config.yml"), "source");
    InstanceDirectoryPreparer preparer =
        new InstanceDirectoryPreparer(temporaryDirectory.resolve("instances"), temporaryDirectory);

    Path prepared =
        preparer.prepare(
            "game.x82odk",
            source,
            List.of(volume("plugins/shared", "/plugins", BlueprintVolume.Mode.RO)));
    Files.writeString(prepared.resolve("plugins/config.yml"), "instance");

    assertEquals("source", Files.readString(shared.resolve("config.yml")));
    assertEquals("instance", Files.readString(prepared.resolve("plugins/config.yml")));
  }

  @Test
  void appliesStateCopiesAfterSoftwareAndVolumes() throws Exception {
    Path software = createSource();
    Files.writeString(software.resolve("config/settings.yml"), "software");
    Path world = createVolumeSource("worlds/game");
    Files.writeString(world.resolve("level.dat"), "volume");
    Path copiedConfig = temporaryDirectory.resolve("files/settings.yml");
    Path copiedWorldFile = temporaryDirectory.resolve("files/level.dat");
    Files.createDirectories(copiedConfig.getParent());
    Files.writeString(copiedConfig, "copy");
    Files.writeString(copiedWorldFile, "copy world");
    InstanceDirectoryPreparer preparer =
        new InstanceDirectoryPreparer(temporaryDirectory.resolve("instances"), temporaryDirectory);

    Path prepared =
        preparer.prepare(
            "game.x82odk",
            software,
            List.of(volume("worlds/game", "/world")),
            List.of(
                new BlueprintCopy("files/settings.yml", "config/settings.yml"),
                new BlueprintCopy("files/level.dat", "world/level.dat")),
            () -> false);

    assertEquals("copy", Files.readString(prepared.resolve("config/settings.yml")));
    assertEquals("copy world", Files.readString(prepared.resolve("world/level.dat")));
    assertEquals("software", Files.readString(software.resolve("config/settings.yml")));
    assertEquals("volume", Files.readString(world.resolve("level.dat")));
  }

  @Test
  void mergesStateCopyDirectoryIntoExistingTarget() throws Exception {
    Path software = createSource();
    Path source = temporaryDirectory.resolve("files/plugins");
    Files.createDirectories(source);
    Files.writeString(source.resolve("plugin.jar"), "plugin");
    Files.createDirectories(software.resolve("plugins"));
    Files.writeString(software.resolve("plugins/existing.jar"), "existing");
    InstanceDirectoryPreparer preparer =
        new InstanceDirectoryPreparer(temporaryDirectory.resolve("instances"), temporaryDirectory);

    Path prepared =
        preparer.prepare(
            "game.x82odk",
            software,
            List.of(),
            List.of(new BlueprintCopy("files/plugins", "plugins")),
            () -> false);

    assertEquals("plugin", Files.readString(prepared.resolve("plugins/plugin.jar")));
    assertEquals("existing", Files.readString(prepared.resolve("plugins/existing.jar")));
  }

  @Test
  void missingStateCopySourceRollsBackPreparedInstance() throws Exception {
    Path software = createSource();
    Path instances = temporaryDirectory.resolve("instances");
    InstanceDirectoryPreparer preparer =
        new InstanceDirectoryPreparer(instances, temporaryDirectory);

    InstancePreparationException exception =
        assertThrows(
            InstancePreparationException.class,
            () ->
                preparer.prepare(
                    "game.x82odk",
                    software,
                    List.of(),
                    List.of(new BlueprintCopy("files/missing.jar", "plugins/missing.jar")),
                    () -> false));

    assertTrue(exception.getMessage().contains("Copy source does not exist"));
    assertFalse(Files.exists(instances.resolve("game.x82odk")));
  }

  @Test
  void rejectsRwVolumeWithoutLeavingPartialInstance() throws Exception {
    Path source = createSource();
    createVolumeSource("data/shared");
    Path instances = temporaryDirectory.resolve("instances");
    InstanceDirectoryPreparer preparer =
        new InstanceDirectoryPreparer(instances, temporaryDirectory);

    InstancePreparationException exception =
        assertThrows(
            InstancePreparationException.class,
            () ->
                preparer.prepare(
                    "game.x82odk",
                    source,
                    List.of(volume("data/shared", "/data", BlueprintVolume.Mode.RW))));

    assertTrue(exception.getMessage().contains("shared writable host mount"));
    assertFalse(Files.exists(instances.resolve("game.x82odk")));
  }

  @Test
  void retriesTransientFilesystemCopyFailures() throws Exception {
    Path source = createSource();
    Path instances = temporaryDirectory.resolve("instances");
    AtomicInteger attempts = new AtomicInteger();
    AtomicLong sleptMillis = new AtomicLong();
    InstanceDirectoryPreparer preparer =
        new InstanceDirectoryPreparer(
            instances,
            temporaryDirectory,
            (file, target) -> {
              if (file.getFileName().toString().equals("server.jar")
                  && attempts.incrementAndGet() < 3) {
                Files.writeString(target, "partial");
                throw new FileSystemException(
                    file.toString(), target.toString(), "Input/output error");
              }
              Files.copy(file, target, java.nio.file.StandardCopyOption.COPY_ATTRIBUTES);
            },
            sleptMillis::addAndGet);

    Path prepared = preparer.prepare("game.x82odk", source);

    assertEquals("server", Files.readString(prepared.resolve("server.jar")));
    assertEquals(3, attempts.get());
    assertEquals(1_000, sleptMillis.get());
  }

  @Test
  void cancellationBetweenFilesRollsBackPartialInstance() throws Exception {
    Path source = createSource();
    Path instances = temporaryDirectory.resolve("instances");
    AtomicBoolean cancelled = new AtomicBoolean();
    AtomicInteger copies = new AtomicInteger();
    InstanceDirectoryPreparer preparer =
        new InstanceDirectoryPreparer(
            instances,
            temporaryDirectory,
            (file, target) -> {
              Files.copy(file, target);
              copies.incrementAndGet();
              cancelled.set(true);
            },
            Thread::sleep);

    InstancePreparationException failure =
        assertThrows(
            InstancePreparationException.class,
            () -> preparer.prepare("game.x82odk", source, List.of(), cancelled::get));

    assertTrue(failure.getMessage().contains("cancelled"));
    assertEquals(1, copies.get());
    assertFalse(Files.exists(instances.resolve("game.x82odk")));
  }

  @Test
  void boundsParallelDirectoryCopies() throws Exception {
    Path source = temporaryDirectory.resolve("parallel-source");
    Files.createDirectories(source);
    for (int index = 0; index < 8; index++) {
      Files.writeString(source.resolve("file-" + index), "value-" + index);
    }
    Path instances = temporaryDirectory.resolve("instances");
    AtomicInteger active = new AtomicInteger();
    AtomicInteger maximum = new AtomicInteger();
    CountDownLatch twoWorkersStarted = new CountDownLatch(2);
    InstanceDirectoryPreparer preparer =
        new InstanceDirectoryPreparer(
            instances,
            temporaryDirectory,
            (file, target) -> {
              int current = active.incrementAndGet();
              maximum.accumulateAndGet(current, Math::max);
              twoWorkersStarted.countDown();
              try {
                awaitLatch(twoWorkersStarted, "Parallel copy workers did not start");
                Files.copy(file, target);
              } finally {
                active.decrementAndGet();
              }
            },
            Thread::sleep,
            StorageStrategy.COPY,
            new OverlayFsLayerManager(instances, temporaryDirectory),
            2);

    Path prepared = preparer.prepare("game.x82odk", source);

    assertEquals(2, maximum.get());
    for (int index = 0; index < 8; index++) {
      assertEquals("value-" + index, Files.readString(prepared.resolve("file-" + index)));
    }
  }

  @Test
  void parallelFailureDrainsWorkersBeforeRollbackDeletesDestination() throws Exception {
    Path source = temporaryDirectory.resolve("parallel-source");
    Files.createDirectories(source);
    Files.writeString(source.resolve("blocked"), "blocked");
    Files.writeString(source.resolve("failure"), "failure");
    Path instances = temporaryDirectory.resolve("instances");
    Path destination = instances.resolve("game.x82odk");
    CountDownLatch blockedWorkerStarted = new CountDownLatch(1);
    CountDownLatch failureObserved = new CountDownLatch(1);
    CountDownLatch releaseBlockedWorker = new CountDownLatch(1);
    AtomicReference<Throwable> preparationFailure = new AtomicReference<>();
    AtomicBoolean destinationExistedAtWorkerExit = new AtomicBoolean();
    InstanceDirectoryPreparer preparer =
        new InstanceDirectoryPreparer(
            instances,
            temporaryDirectory,
            (file, target) -> {
              if (file.getFileName().toString().equals("failure")) {
                awaitLatch(blockedWorkerStarted, "Blocked copy worker did not start");
                failureObserved.countDown();
                throw new IOException("intentional parallel failure");
              }
              blockedWorkerStarted.countDown();
              awaitLatch(releaseBlockedWorker, "Blocked copy worker was not released");
              destinationExistedAtWorkerExit.set(Files.isDirectory(destination));
            },
            Thread::sleep,
            StorageStrategy.COPY,
            new OverlayFsLayerManager(instances, temporaryDirectory),
            2);
    Thread preparation =
        Thread.ofPlatform()
            .start(
                () -> {
                  try {
                    preparer.prepare("game.x82odk", source);
                  } catch (Throwable failure) {
                    preparationFailure.set(failure);
                  }
                });

    assertTrue(failureObserved.await(5, TimeUnit.SECONDS));
    Thread.sleep(50);
    assertTrue(preparation.isAlive());
    assertTrue(Files.isDirectory(destination));

    releaseBlockedWorker.countDown();
    preparation.join(5_000);

    assertFalse(preparation.isAlive());
    assertTrue(destinationExistedAtWorkerExit.get());
    assertTrue(preparationFailure.get() instanceof InstancePreparationException);
    assertFalse(Files.exists(destination));
  }

  @Test
  void cancellationInterruptsRetryBackoffAndRollsBack() throws Exception {
    Path source = createSource();
    Path instances = temporaryDirectory.resolve("instances");
    AtomicBoolean cancelled = new AtomicBoolean();
    AtomicInteger attempts = new AtomicInteger();
    AtomicLong sleptMillis = new AtomicLong();
    InstanceDirectoryPreparer preparer =
        new InstanceDirectoryPreparer(
            instances,
            temporaryDirectory,
            (file, target) -> {
              attempts.incrementAndGet();
              throw new FileSystemException(
                  file.toString(), target.toString(), "Input/output error");
            },
            milliseconds -> {
              sleptMillis.addAndGet(milliseconds);
              cancelled.set(true);
            });

    InstancePreparationException failure =
        assertThrows(
            InstancePreparationException.class,
            () -> preparer.prepare("game.x82odk", source, List.of(), cancelled::get));

    assertTrue(failure.getMessage().contains("cancelled"));
    assertEquals(1, attempts.get());
    assertEquals(100, sleptMillis.get());
    assertFalse(Files.exists(instances.resolve("game.x82odk")));
  }

  @Test
  void rollsBackAfterTransientCopyRetryBudgetIsExhausted() throws Exception {
    Path source = createSource();
    Path instances = temporaryDirectory.resolve("instances");
    AtomicInteger attempts = new AtomicInteger();
    InstanceDirectoryPreparer preparer =
        new InstanceDirectoryPreparer(
            instances,
            temporaryDirectory,
            (file, target) -> {
              attempts.incrementAndGet();
              throw new FileSystemException(
                  file.toString(), target.toString(), "Input/output error");
            },
            ignored -> {});

    assertThrows(InstancePreparationException.class, () -> preparer.prepare("game.x82odk", source));

    assertEquals(5, attempts.get());
    assertFalse(Files.exists(instances.resolve("game.x82odk")));
  }

  @Test
  void rejectsVolumeSourceTraversal() throws Exception {
    Path source = createSource();
    Path contentRoot = temporaryDirectory.resolve("data");
    Path outside = temporaryDirectory.resolve("outside-volume");
    Files.createDirectories(contentRoot);
    Files.createDirectories(outside);
    InstanceDirectoryPreparer preparer =
        new InstanceDirectoryPreparer(temporaryDirectory.resolve("instances"), contentRoot);

    assertThrows(
        InstancePreparationException.class,
        () ->
            preparer.prepare(
                "game.x82odk", source, List.of(volume("../outside-volume", "/world"))));
    assertFalse(Files.exists(temporaryDirectory.resolve("instances/game.x82odk")));
  }

  @Test
  void rejectsVolumeTargetTraversal() throws Exception {
    Path source = createSource();
    createVolumeSource("worlds/game");
    InstanceDirectoryPreparer preparer =
        new InstanceDirectoryPreparer(temporaryDirectory.resolve("instances"), temporaryDirectory);

    assertThrows(
        InstancePreparationException.class,
        () ->
            preparer.prepare("game.x82odk", source, List.of(volume("worlds/game", "/../escaped"))));
    assertFalse(Files.exists(temporaryDirectory.resolve("escaped")));
  }

  @Test
  void reportsInvalidVolumePathsAsPreparationFailures() throws Exception {
    Path source = createSource();
    createVolumeSource("worlds/game");
    InstanceDirectoryPreparer preparer =
        new InstanceDirectoryPreparer(temporaryDirectory.resolve("instances"), temporaryDirectory);

    InstancePreparationException exception =
        assertThrows(
            InstancePreparationException.class,
            () ->
                preparer.prepare(
                    "game.x82odk", source, List.of(volume("worlds/game", "/world\u0000invalid"))));

    assertTrue(exception.getMessage().contains("Invalid volume target"));
    assertFalse(Files.exists(temporaryDirectory.resolve("instances/game.x82odk")));
  }

  @Test
  void rejectsOverlappingVolumeTargets() throws Exception {
    Path source = createSource();
    createVolumeSource("worlds/one");
    createVolumeSource("worlds/two");
    InstanceDirectoryPreparer preparer =
        new InstanceDirectoryPreparer(temporaryDirectory.resolve("instances"), temporaryDirectory);

    InstancePreparationException exception =
        assertThrows(
            InstancePreparationException.class,
            () ->
                preparer.prepare(
                    "game.x82odk",
                    source,
                    List.of(volume("worlds/one", "/world"), volume("worlds/two", "/world/data"))));

    assertTrue(exception.getMessage().contains("overlap"));
  }

  @Test
  void rollsBackWhenVolumeCollidesWithSoftwareBase() throws Exception {
    Path source = createSource();
    Files.createDirectories(source.resolve("world"));
    createVolumeSource("worlds/game");
    Path instances = temporaryDirectory.resolve("instances");
    InstanceDirectoryPreparer preparer =
        new InstanceDirectoryPreparer(instances, temporaryDirectory);

    InstancePreparationException exception =
        assertThrows(
            InstancePreparationException.class,
            () ->
                preparer.prepare("game.x82odk", source, List.of(volume("worlds/game", "/world"))));

    assertTrue(exception.getMessage().contains("Unable to prepare instance directory"));
    assertFalse(Files.exists(instances.resolve("game.x82odk")));
  }

  @Test
  void rejectsSymbolicLinkVolumeSource() throws Exception {
    Path source = createSource();
    Path realWorld = createVolumeSource("worlds/real");
    Path linkedWorld = temporaryDirectory.resolve("worlds/linked");
    createSymbolicLinkOrSkip(linkedWorld, realWorld);
    Path instances = temporaryDirectory.resolve("instances");
    InstanceDirectoryPreparer preparer =
        new InstanceDirectoryPreparer(instances, temporaryDirectory);

    InstancePreparationException exception =
        assertThrows(
            InstancePreparationException.class,
            () ->
                preparer.prepare(
                    "game.x82odk", source, List.of(volume("worlds/linked", "/world"))));

    assertTrue(exception.getMessage().contains("symbolic links"));
    assertFalse(Files.exists(instances.resolve("game.x82odk")));
  }

  @Test
  void rollsBackWhenVolumeContainsSymbolicLink() throws Exception {
    Path source = createSource();
    Path world = createVolumeSource("worlds/game");
    Path outside = temporaryDirectory.resolve("outside");
    Files.createDirectories(outside);
    Files.writeString(outside.resolve("outside.dat"), "outside");
    createSymbolicLinkOrSkip(world.resolve("linked"), outside);
    Path instances = temporaryDirectory.resolve("instances");
    InstanceDirectoryPreparer preparer =
        new InstanceDirectoryPreparer(instances, temporaryDirectory);

    InstancePreparationException exception =
        assertThrows(
            InstancePreparationException.class,
            () ->
                preparer.prepare("game.x82odk", source, List.of(volume("worlds/game", "/world"))));

    assertTrue(exception.getMessage().contains("Symbolic links"));
    assertFalse(Files.exists(instances.resolve("game.x82odk")));
  }

  @Test
  void rejectsVolumeSourcesInsideManagedInstances() throws Exception {
    Path source = createSource();
    Path instances = temporaryDirectory.resolve("instances");
    Path activeInstance = instances.resolve("other.abc123");
    Files.createDirectories(activeInstance);
    Files.writeString(activeInstance.resolve("level.dat"), "runtime data");
    InstanceDirectoryPreparer preparer =
        new InstanceDirectoryPreparer(instances, temporaryDirectory);

    InstancePreparationException exception =
        assertThrows(
            InstancePreparationException.class,
            () ->
                preparer.prepare(
                    "game.x82odk", source, List.of(volume("instances/other.abc123", "/world"))));

    assertTrue(exception.getMessage().contains("instances directory"));
  }

  @Test
  void replacementReappliesCowVolume() throws Exception {
    Path source = createSource();
    Path world = createVolumeSource("worlds/game");
    Files.writeString(world.resolve("level.dat"), "clean");
    Path instances = temporaryDirectory.resolve("instances");
    InstanceDirectoryPreparer preparer =
        new InstanceDirectoryPreparer(instances, temporaryDirectory);
    Path prepared =
        preparer.prepare("game.x82odk", source, List.of(volume("worlds/game", "/world")));
    Files.writeString(prepared.resolve("world/level.dat"), "changed");

    preparer.replace(
        "game.x82odk", source, List.of(volume("worlds/game", "/world")), ignored -> {});

    assertEquals("clean", Files.readString(prepared.resolve("world/level.dat")));
  }

  @Test
  void replacementReappliesChangedStateCopySource() throws Exception {
    Path software = createSource();
    Path copied = temporaryDirectory.resolve("files/config.yml");
    Files.createDirectories(copied.getParent());
    Files.writeString(copied, "first");
    Path instances = temporaryDirectory.resolve("instances");
    InstanceDirectoryPreparer preparer =
        new InstanceDirectoryPreparer(instances, temporaryDirectory);
    Path prepared =
        preparer.prepare(
            "game.x82odk",
            software,
            List.of(),
            List.of(new BlueprintCopy("files/config.yml", "plugins/config.yml")),
            () -> false);
    Files.writeString(copied, "second");

    preparer.replace(
        "game.x82odk",
        software,
        List.of(),
        List.of(new BlueprintCopy("files/config.yml", "plugins/config.yml")),
        ignored -> {});

    assertEquals("second", Files.readString(prepared.resolve("plugins/config.yml")));
  }

  @Test
  void refusesExistingInstanceDirectory() throws Exception {
    Path source = createSource();
    Path instances = temporaryDirectory.resolve("instances");
    Files.createDirectories(instances.resolve("game.x82odk"));
    InstanceDirectoryPreparer preparer = new InstanceDirectoryPreparer(instances);

    assertThrows(InstancePreparationException.class, () -> preparer.prepare("game.x82odk", source));
  }

  @Test
  void rejectsUnsafeInstanceId() throws Exception {
    Path source = createSource();
    InstanceDirectoryPreparer preparer =
        new InstanceDirectoryPreparer(temporaryDirectory.resolve("instances"));

    assertThrows(InstancePreparationException.class, () -> preparer.prepare("../outside", source));
    assertFalse(Files.exists(temporaryDirectory.resolve("outside")));
  }

  @Test
  void deletesOnlyNamedInstanceDirectory() throws Exception {
    Path source = createSource();
    Path instances = temporaryDirectory.resolve("instances");
    InstanceDirectoryPreparer preparer = new InstanceDirectoryPreparer(instances);
    Path prepared = preparer.prepare("game.x82odk", source);

    preparer.delete("game.x82odk");

    assertFalse(Files.exists(prepared));
    assertTrue(Files.exists(source));
  }

  @Test
  void replacementRollsBackWhenInitializationFails() throws Exception {
    Path source = createSource();
    Path instances = temporaryDirectory.resolve("instances");
    InstanceDirectoryPreparer preparer = new InstanceDirectoryPreparer(instances);
    Path prepared = preparer.prepare("game.x82odk", source);
    Files.writeString(prepared.resolve("world-data"), "keep me");

    Files.writeString(source.resolve("server.jar"), "replacement");
    assertThrows(
        InstancePreparationException.class,
        () ->
            preparer.replace(
                "game.x82odk",
                source,
                ignored -> {
                  throw new IllegalStateException("metadata failed");
                }));

    assertEquals("keep me", Files.readString(prepared.resolve("world-data")));
    assertEquals("server", Files.readString(prepared.resolve("server.jar")));
    try (var entries = Files.list(instances)) {
      assertEquals(
          java.util.List.of("game.x82odk"),
          entries.map(path -> path.getFileName().toString()).sorted().toList());
    }
  }

  private Path createSource() throws Exception {
    Path source = temporaryDirectory.resolve("software");
    Files.createDirectories(source.resolve("config"));
    Files.writeString(source.resolve("server.jar"), "server");
    Files.writeString(source.resolve("config/settings.yml"), "original");
    return source;
  }

  private Path createVolumeSource(String relative) throws Exception {
    Path source = temporaryDirectory.resolve(relative);
    Files.createDirectories(source);
    Files.writeString(source.resolve("volume.txt"), "volume");
    return source;
  }

  private static BlueprintVolume volume(String source, String target) {
    return volume(source, target, BlueprintVolume.Mode.COW);
  }

  private static BlueprintVolume volume(String source, String target, BlueprintVolume.Mode mode) {
    return new BlueprintVolume("world", source, target, mode);
  }

  private static void createSymbolicLinkOrSkip(Path link, Path target) throws Exception {
    try {
      Files.createSymbolicLink(link, target);
      return;
    } catch (UnsupportedOperationException | java.io.IOException symlinkFailure) {
      boolean windows =
          System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("windows");
      if (windows && Files.isDirectory(target)) {
        Process process =
            new ProcessBuilder("cmd.exe", "/c", "mklink", "/J", link.toString(), target.toString())
                .redirectErrorStream(true)
                .start();
        String output =
            new String(
                process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
        if (process.waitFor() == 0 && Files.exists(link)) {
          return;
        }
        Assumptions.abort("Symbolic links and junctions are unavailable: " + output.trim());
      }
      Assumptions.abort("Symbolic links are unavailable: " + symlinkFailure.getMessage());
    }
  }

  private static void awaitLatch(CountDownLatch latch, String timeoutMessage) throws IOException {
    try {
      if (!latch.await(5, TimeUnit.SECONDS)) {
        throw new IOException(timeoutMessage);
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IOException("Interrupted while waiting in copy test", exception);
    }
  }
}
