package net.slimelabs.slslite.instance.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import net.slimelabs.slslite.instance.InstancePreparationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PersistentInstanceTransactionTest {

  private static final String INSTANCE_ID = "game.abc123";
  private static final UUID NONCE = UUID.fromString("12345678-1234-1234-1234-123456789abc");

  @TempDir Path temporaryDirectory;

  private Path instancesRoot;
  private Path destination;
  private Path staging;
  private Path backup;
  private FakeMountAdapter mounts;
  private PersistentInstanceTransaction transaction;

  @BeforeEach
  void setUp() throws Exception {
    instancesRoot = temporaryDirectory.resolve("instances");
    Path contentRoot = temporaryDirectory.resolve("content");
    destination = instancesRoot.resolve(INSTANCE_ID);
    staging = instancesRoot.resolve("." + INSTANCE_ID + ".reset-" + NONCE);
    backup = instancesRoot.resolve("." + INSTANCE_ID + ".backup-" + NONCE);
    Files.createDirectories(destination);
    Files.createDirectories(contentRoot);
    Files.writeString(destination.resolve("world.dat"), "original");
    mounts = new FakeMountAdapter();
    PreparedStorageLifecycle lifecycle =
        new PreparedStorageLifecycle(
            new OverlayFsLayerManager(instancesRoot, contentRoot, mounts),
            new BtrfsSnapshotManager(instancesRoot, contentRoot),
            null);
    transaction = new PersistentInstanceTransaction(instancesRoot, lifecycle, () -> NONCE);
  }

  @Test
  void commitsReplacementAndRemovesTransactionDirectories() throws Exception {
    transaction.replace(
        INSTANCE_ID,
        destination,
        directory -> {
          Files.createDirectories(directory);
          Files.writeString(directory.resolve("world.dat"), "replacement");
        },
        directory -> Files.writeString(directory.resolve("committed"), "yes"));

    assertEquals("replacement", Files.readString(destination.resolve("world.dat")));
    assertTrue(Files.isRegularFile(destination.resolve("committed")));
    assertFalse(Files.exists(staging));
    assertFalse(Files.exists(backup));
  }

  @Test
  void initializationFailureRestoresOriginalDirectory() throws Exception {
    assertThrows(
        InstancePreparationException.class,
        () ->
            transaction.replace(
                INSTANCE_ID,
                destination,
                directory -> {
                  Files.createDirectories(directory);
                  Files.writeString(directory.resolve("world.dat"), "replacement");
                },
                ignored -> {
                  throw new IllegalStateException("metadata failed");
                }));

    assertEquals("original", Files.readString(destination.resolve("world.dat")));
    assertFalse(Files.exists(staging));
    assertFalse(Files.exists(backup));
  }

  @Test
  void committedCleanupFailureLeavesRecoverableBackup() throws Exception {
    mounts.mountPoints.add(backup.resolve("world"));

    transaction.replace(
        INSTANCE_ID,
        destination,
        directory -> {
          Files.createDirectories(directory);
          Files.writeString(directory.resolve("world.dat"), "replacement");
        },
        ignored -> {});

    assertEquals("replacement", Files.readString(destination.resolve("world.dat")));
    assertTrue(Files.isDirectory(backup));
  }

  @Test
  void recoveryRestoresBackupWhenReplacementIsUncommitted() throws Exception {
    Files.move(destination, backup);
    Files.createDirectories(destination);
    Files.writeString(destination.resolve("world.dat"), "incomplete");
    Files.createDirectories(staging);

    int recovered = transaction.recover((directory, instanceId) -> false);

    assertEquals(1, recovered);
    assertEquals("original", Files.readString(destination.resolve("world.dat")));
    assertFalse(Files.exists(backup));
    assertFalse(Files.exists(staging));
  }

  @Test
  void recoveryKeepsCommittedReplacementAndDeletesBackup() throws Exception {
    Files.move(destination, backup);
    Files.createDirectories(destination);
    Files.writeString(destination.resolve("world.dat"), "replacement");
    Files.createDirectories(staging);

    int recovered = transaction.recover((directory, instanceId) -> true);

    assertEquals(1, recovered);
    assertEquals("replacement", Files.readString(destination.resolve("world.dat")));
    assertFalse(Files.exists(backup));
    assertFalse(Files.exists(staging));
  }

  @Test
  void recoveryDeletesOrphanStagingButPreservesUnknownDirectory() throws Exception {
    Files.createDirectories(staging);
    Path unknown = instancesRoot.resolve(".invalid.reset-" + NONCE);
    Files.createDirectories(unknown);

    int recovered = transaction.recover((directory, instanceId) -> false);

    assertEquals(1, recovered);
    assertFalse(Files.exists(staging));
    assertTrue(Files.isDirectory(unknown));
  }

  private static final class FakeMountAdapter implements OverlayFsLayerManager.MountAdapter {

    private final List<Path> mountPoints = new ArrayList<>();

    @Override
    public void mount(
        List<Path> lowerDirectories, Path upperDirectory, Path workDirectory, Path target) {}

    @Override
    public void unmount(Path target, Path upperDirectory, Path workDirectory) {}

    @Override
    public boolean isMounted(Path target) {
      return mountPoints.contains(target);
    }

    @Override
    public List<Path> mountPointsBeneath(Path root) {
      return mountPoints.stream().filter(path -> path.startsWith(root)).toList();
    }
  }
}
