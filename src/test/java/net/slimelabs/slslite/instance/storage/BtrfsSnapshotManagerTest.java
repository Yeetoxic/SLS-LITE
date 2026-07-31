package net.slimelabs.slslite.instance.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BtrfsSnapshotManagerTest {

  @TempDir Path temporaryDirectory;

  @Test
  void snapshotsEligibleSourceAndDeletesDeclaredSubvolume() throws Exception {
    Fixture fixture = fixture();

    fixture.manager.snapshot(fixture.instance, fixture.source, Path.of("world"));
    Files.writeString(fixture.instance.resolve("world/level.dat"), "snapshot");

    assertEquals("source", Files.readString(fixture.source.resolve("level.dat")));
    assertTrue(fixture.manager.hasManifest(fixture.instance));

    fixture.manager.deleteSnapshots(fixture.instance);

    assertFalse(Files.exists(fixture.instance.resolve("world")));
    assertFalse(fixture.manager.hasManifest(fixture.instance));
  }

  @Test
  void failedSnapshotDeletesCreatedSubvolumeAndManifest() throws Exception {
    Fixture fixture = fixture();
    fixture.operations.failAfterSnapshot = true;

    assertThrows(
        IOException.class,
        () -> fixture.manager.snapshot(fixture.instance, fixture.source, Path.of("world")));

    assertFalse(Files.exists(fixture.instance.resolve("world")));
    assertFalse(fixture.manager.hasManifest(fixture.instance));
  }

  @Test
  void refusesToTraverseDeclaredTargetThatIsNoLongerSubvolume() throws Exception {
    Fixture fixture = fixture();
    fixture.manager.snapshot(fixture.instance, fixture.source, Path.of("world"));
    fixture.operations.subvolumes.remove(
        fixture.instance.resolve("world").toAbsolutePath().normalize());

    IOException failure =
        assertThrows(IOException.class, () -> fixture.manager.deleteSnapshots(fixture.instance));

    assertTrue(failure.getMessage().contains("Refusing to traverse"));
    assertTrue(Files.exists(fixture.instance.resolve("world/level.dat")));
    assertTrue(fixture.manager.hasManifest(fixture.instance));
  }

  @Test
  void rejectsSourceOutsideManagedContent() throws Exception {
    Fixture fixture = fixture();
    Path outside = temporaryDirectory.resolve("outside");
    Files.createDirectories(outside);
    fixture.operations.subvolumes.add(outside.toRealPath());

    assertThrows(
        IOException.class,
        () -> fixture.manager.snapshot(fixture.instance, outside, Path.of("world")));
  }

  @Test
  void rejectsSourceContainingNestedSubvolumes() throws Exception {
    Fixture fixture = fixture();
    fixture.operations.hasNestedSubvolumes = true;

    assertFalse(fixture.manager.isEligibleSource(fixture.source));
    assertThrows(
        BtrfsSnapshotManager.IneligibleSourceException.class,
        () -> fixture.manager.snapshot(fixture.instance, fixture.source, Path.of("world")));
    assertFalse(fixture.manager.hasManifest(fixture.instance));
  }

  private Fixture fixture() throws Exception {
    Path content = temporaryDirectory.resolve("content");
    Path instances = content.resolve("instances");
    Path source = content.resolve("worlds/source");
    Path instance = instances.resolve("game.x82odk");
    Files.createDirectories(source);
    Files.createDirectories(instance);
    Files.writeString(source.resolve("level.dat"), "source");
    FakeSubvolumes operations = new FakeSubvolumes();
    operations.subvolumes.add(source.toRealPath());
    return new Fixture(
        source, instance, operations, new BtrfsSnapshotManager(instances, content, operations));
  }

  private record Fixture(
      Path source, Path instance, FakeSubvolumes operations, BtrfsSnapshotManager manager) {}

  static final class FakeSubvolumes implements BtrfsSnapshotManager.SubvolumeAdapter {

    private final Set<Path> subvolumes = new HashSet<>();
    private boolean failAfterSnapshot;
    private boolean hasNestedSubvolumes;

    @Override
    public boolean isSubvolume(Path path) {
      return subvolumes.contains(path.toAbsolutePath().normalize());
    }

    @Override
    public boolean hasNestedSubvolumes(Path path) {
      return hasNestedSubvolumes;
    }

    @Override
    public void snapshot(Path source, Path target) throws IOException {
      copyTree(source, target);
      subvolumes.add(target.toAbsolutePath().normalize());
      if (failAfterSnapshot) {
        throw new IOException("intentional snapshot failure");
      }
    }

    @Override
    public void delete(Path path) throws IOException {
      deleteTree(path);
      subvolumes.remove(path.toAbsolutePath().normalize());
    }

    private static void copyTree(Path source, Path target) throws IOException {
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
    }

    private static void deleteTree(Path root) throws IOException {
      try (var paths = Files.walk(root)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
          Files.delete(path);
        }
      }
    }
  }
}
