package net.slimelabs.slslite.instance.storage;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;

/**
 * Owns resume, suspend, mount-safety validation, and deletion of prepared
 * instance storage.
 */
final class PreparedStorageLifecycle {

  private final OverlayFsLayerManager overlayLayers;
  private final BtrfsSnapshotManager btrfsSnapshots;
  private final SnapshotHookLayerManager snapshotHooks;

  PreparedStorageLifecycle(
      OverlayFsLayerManager overlayLayers,
      BtrfsSnapshotManager btrfsSnapshots,
      SnapshotHookLayerManager snapshotHooks) {
    this.overlayLayers = java.util.Objects.requireNonNull(overlayLayers, "overlayLayers");
    this.btrfsSnapshots = java.util.Objects.requireNonNull(btrfsSnapshots, "btrfsSnapshots");
    this.snapshotHooks = snapshotHooks;
  }

  void resume(Path directory) throws IOException {
    boolean snapshotManifest = SnapshotHookLayerManager.manifestExists(directory);
    boolean overlayManifest = overlayLayers.hasManifest(directory);
    requireSnapshotHelper(snapshotManifest, "resuming");
    if (snapshotManifest) {
      snapshotHooks.resume(directory);
    } else if (overlayManifest) {
      overlayLayers.resume(directory);
    } else if (Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
      overlayLayers.assertNoMountsBeneath(directory);
    }
  }

  void suspend(Path directory) throws IOException {
    boolean snapshotManifest = SnapshotHookLayerManager.manifestExists(directory);
    requireSnapshotHelper(snapshotManifest, "suspending");
    if (snapshotManifest) {
      snapshotHooks.suspend(directory);
    }
    if (overlayLayers.hasManifest(directory)) {
      overlayLayers.suspend(directory);
    }
    if (Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
      overlayLayers.assertNoMountsBeneath(directory);
    }
  }

  void delete(Path directory) throws IOException {
    suspend(directory);
    if (btrfsSnapshots.hasManifest(directory)) {
      btrfsSnapshots.deleteSnapshots(directory);
    }
    if (snapshotHooks != null && snapshotHooks.hasManifest(directory)) {
      snapshotHooks.delete(directory);
    }
    deleteDirectory(directory);
  }

  private void requireSnapshotHelper(boolean snapshotManifest, String operation)
      throws IOException {
    if (snapshotManifest && snapshotHooks == null) {
      throw new IOException(
          "Instance was prepared with snapshot-hook; restore that "
              + "configured helper before "
              + operation
              + ", resetting, or deleting the persistent instance");
    }
  }

  private static void deleteDirectory(Path directory) throws IOException {
    if (!Files.exists(directory)) {
      return;
    }

    Files.walkFileTree(
        directory,
        new SimpleFileVisitor<>() {
          @Override
          public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
              throws IOException {
            Files.delete(file);
            return FileVisitResult.CONTINUE;
          }

          @Override
          public FileVisitResult postVisitDirectory(Path current, IOException exception)
              throws IOException {
            if (exception != null) {
              throw exception;
            }
            Files.delete(current);
            return FileVisitResult.CONTINUE;
          }
        });
  }
}
