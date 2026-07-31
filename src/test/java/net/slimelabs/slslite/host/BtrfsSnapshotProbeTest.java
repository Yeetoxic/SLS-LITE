package net.slimelabs.slslite.host;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BtrfsSnapshotProbeTest {

  @TempDir Path temporaryDirectory;

  @Test
  void verifiesSnapshotIsolationAndCleansContainedProbe() throws Exception {
    FakeOperations operations = new FakeOperations();

    BtrfsSnapshotProbe.Result result = new BtrfsSnapshotProbe(operations).probe(temporaryDirectory);

    assertTrue(result.supported());
    assertTrue(result.detail().contains("write isolation"));
    try (var entries = Files.list(temporaryDirectory)) {
      assertFalse(entries.findAny().isPresent());
    }
  }

  @Test
  void preservesFailedSubvolumeCleanupForDiagnosis() throws Exception {
    FakeOperations operations = new FakeOperations();
    operations.failDeletion = true;

    BtrfsSnapshotProbe.Result result = new BtrfsSnapshotProbe(operations).probe(temporaryDirectory);

    assertFalse(result.supported());
    assertTrue(result.detail().contains("contained probe failed"));
    try (var entries = Files.list(temporaryDirectory)) {
      assertTrue(
          entries.anyMatch(path -> path.getFileName().toString().startsWith(".sls-btrfs-probe-")));
    }
  }

  private static final class FakeOperations extends BtrfsSubvolumeOperations {

    private final Set<Path> subvolumes = new HashSet<>();
    private boolean failDeletion;

    @Override
    boolean available() {
      return true;
    }

    @Override
    boolean isSubvolume(Path path) {
      return subvolumes.contains(path.toAbsolutePath().normalize());
    }

    @Override
    void create(Path path) throws IOException {
      Files.createDirectory(path);
      subvolumes.add(path.toAbsolutePath().normalize());
    }

    @Override
    void snapshot(Path source, Path target) throws IOException {
      Files.createDirectory(target);
      Files.copy(source.resolve("marker"), target.resolve("marker"));
      subvolumes.add(target.toAbsolutePath().normalize());
    }

    @Override
    void delete(Path path) throws IOException {
      if (failDeletion) {
        throw new IOException("intentional delete failure");
      }
      Files.deleteIfExists(path.resolve("marker"));
      Files.delete(path);
      subvolumes.remove(path.toAbsolutePath().normalize());
    }
  }
}
