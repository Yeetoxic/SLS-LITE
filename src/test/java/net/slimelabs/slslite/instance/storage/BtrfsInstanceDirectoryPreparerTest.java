package net.slimelabs.slslite.instance.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
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
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class BtrfsInstanceDirectoryPreparerTest {

  @TempDir Path temporaryDirectory;

  @Test
  void snapshotsEligibleCowVolumeAndDeletesItTransactionally() throws Exception {
    Fixture fixture = fixture(true, true);

    Path prepared = fixture.preparer.prepare("game.x82odk", fixture.software, List.of(cowVolume()));
    Files.writeString(prepared.resolve("world/level.dat"), "instance");

    assertEquals("source", Files.readString(fixture.world.resolve("level.dat")));
    assertTrue(Files.isRegularFile(prepared.resolve(BtrfsSnapshotManager.MANIFEST_FILE)));

    fixture.preparer.delete("game.x82odk");

    assertFalse(Files.exists(prepared));
    assertTrue(fixture.operations.deletedSnapshots > 0);
  }

  @Test
  void explicitBtrfsRejectsOrdinaryCowSourceAndRollsBack() throws Exception {
    Fixture fixture = fixture(false, false);

    InstancePreparationException failure =
        assertThrows(
            InstancePreparationException.class,
            () -> fixture.preparer.prepare("game.x82odk", fixture.software, List.of(cowVolume())));

    assertTrue(failure.getMessage().contains("requires a subvolume"));
    assertFalse(Files.exists(fixture.instances.resolve("game.x82odk")));
  }

  @Test
  void rejectsSymbolicLinksInsideBtrfsCowSource() throws Exception {
    Fixture fixture = fixture(true, false);
    Path outside = Files.createDirectories(temporaryDirectory.resolve("outside"));
    createSymbolicLinkOrSkip(fixture.world.resolve("escape"), outside);

    InstancePreparationException failure =
        assertThrows(
            InstancePreparationException.class,
            () -> fixture.preparer.prepare("game.x82odk", fixture.software, List.of(cowVolume())));

    assertTrue(failure.getMessage().contains("Symbolic links"));
    assertFalse(Files.exists(fixture.instances.resolve("game.x82odk")));
  }

  @Test
  void autoFallsBackToPortableCopyForOrdinaryCowSource() throws Exception {
    Fixture fixture = fixture(false, true);

    Path prepared = fixture.preparer.prepare("game.x82odk", fixture.software, List.of(cowVolume()));
    Files.writeString(prepared.resolve("world/level.dat"), "instance");

    assertEquals("source", Files.readString(fixture.world.resolve("level.dat")));
    assertFalse(Files.exists(prepared.resolve(BtrfsSnapshotManager.MANIFEST_FILE)));
  }

  @Test
  void replacementSwapsSnapshotsAndDeletesBackupSubvolumes() throws Exception {
    Fixture fixture = fixture(true, false);
    Path prepared = fixture.preparer.prepare("game.x82odk", fixture.software, List.of(cowVolume()));
    Files.writeString(fixture.world.resolve("level.dat"), "replacement");

    fixture.preparer.replace("game.x82odk", fixture.software, List.of(cowVolume()), ignored -> {});

    assertEquals("replacement", Files.readString(prepared.resolve("world/level.dat")));
    assertTrue(fixture.operations.deletedSnapshots > 0);
    try (var entries = Files.list(fixture.instances)) {
      assertEquals(
          List.of("game.x82odk"),
          entries.map(path -> path.getFileName().toString()).sorted().toList());
    }
  }

  @Test
  void reconciliationDeletesStaleEphemeralBtrfsSnapshots() throws Exception {
    Fixture fixture = fixture(true, false);
    Path prepared = fixture.preparer.prepare("game.x82odk", fixture.software, List.of(cowVolume()));
    new InstanceMetadataStore(fixture.instances)
        .write(
            prepared,
            new InstanceMetadata(
                "game.x82odk",
                "game",
                false,
                InstanceState.PREPARING,
                Instant.parse("2026-07-29T12:00:00Z"),
                null,
                null));

    InstanceReconciliationReport report =
        new InstanceReconciler(
                fixture.preparer, LoggerFactory.getLogger(BtrfsInstanceDirectoryPreparerTest.class))
            .reconcile();

    assertEquals(1, report.removedEphemeral());
    assertEquals(0, report.failures());
    assertFalse(Files.exists(prepared));
    assertTrue(fixture.operations.deletedSnapshots > 0);
  }

  private Fixture fixture(boolean eligibleWorld, boolean portableFallbackAllowed) throws Exception {
    Path content = temporaryDirectory.resolve("content");
    Path instances = content.resolve("instances");
    Path software = content.resolve("software/paper");
    Path world = content.resolve("worlds/adventure");
    Files.createDirectories(software);
    Files.createDirectories(world);
    Files.writeString(software.resolve("server.jar"), "server");
    Files.writeString(world.resolve("level.dat"), "source");
    FakeSubvolumes operations = new FakeSubvolumes();
    if (eligibleWorld) {
      operations.subvolumes.add(world.toRealPath());
    }
    BtrfsSnapshotManager manager = new BtrfsSnapshotManager(instances, content, operations);
    InstanceDirectoryPreparer preparer =
        new InstanceDirectoryPreparer(
            instances,
            content,
            new PortableFileCopyOperation(),
            Thread::sleep,
            StorageStrategy.BTRFS,
            new OverlayFsLayerManager(instances, content),
            manager,
            portableFallbackAllowed,
            2);
    return new Fixture(content, instances, software, world, operations, preparer);
  }

  private static BlueprintVolume cowVolume() {
    return new BlueprintVolume("world", "worlds/adventure", "/world", BlueprintVolume.Mode.COW);
  }

  private static void createSymbolicLinkOrSkip(Path link, Path target) throws Exception {
    try {
      Files.createSymbolicLink(link, target);
    } catch (UnsupportedOperationException | IOException exception) {
      Assumptions.abort("Symbolic links are unavailable: " + exception.getMessage());
    }
  }

  private record Fixture(
      Path content,
      Path instances,
      Path software,
      Path world,
      FakeSubvolumes operations,
      InstanceDirectoryPreparer preparer) {}

  private static final class FakeSubvolumes implements BtrfsSnapshotManager.SubvolumeAdapter {

    private final Set<Path> subvolumes = new HashSet<>();
    private int deletedSnapshots;

    @Override
    public boolean isSubvolume(Path path) {
      return subvolumes.contains(path.toAbsolutePath().normalize())
          || Files.isRegularFile(path.resolve(".fake-btrfs-subvolume"));
    }

    @Override
    public void snapshot(Path source, Path target) throws IOException {
      try (var paths = Files.walk(source)) {
        for (Path path : paths.toList()) {
          Path destination = target.resolve(source.relativize(path));
          if (Files.isDirectory(path)) {
            Files.createDirectories(destination);
          } else {
            Files.copy(path, destination);
          }
        }
      }
      Files.writeString(target.resolve(".fake-btrfs-subvolume"), "test-only");
      subvolumes.add(target.toAbsolutePath().normalize());
    }

    @Override
    public void delete(Path path) throws IOException {
      try (var paths = Files.walk(path)) {
        for (Path current : paths.sorted(Comparator.reverseOrder()).toList()) {
          Files.delete(current);
        }
      }
      subvolumes.remove(path.toAbsolutePath().normalize());
      deletedSnapshots++;
    }
  }
}
