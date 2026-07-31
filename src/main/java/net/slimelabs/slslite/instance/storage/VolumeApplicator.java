package net.slimelabs.slslite.instance.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;
import net.slimelabs.slslite.blueprint.BlueprintVolume;
import net.slimelabs.slslite.config.StorageStrategy;
import net.slimelabs.slslite.instance.InstancePreparationException;
import net.slimelabs.slslite.instance.storage.BlueprintContentResolver.ResolvedVolume;

/**
 * Applies resolved blueprint volumes using the selected storage strategy.
 */
final class VolumeApplicator {

  private final StorageStrategy strategy;
  private final DirectoryCopyEngine copyEngine;
  private final OverlayFsLayerManager overlayLayers;
  private final BtrfsSnapshotManager btrfsSnapshots;
  private final SnapshotHookLayerManager snapshotHooks;
  private final boolean btrfsPortableFallbackAllowed;

  VolumeApplicator(
      StorageStrategy strategy,
      DirectoryCopyEngine copyEngine,
      OverlayFsLayerManager overlayLayers,
      BtrfsSnapshotManager btrfsSnapshots,
      SnapshotHookLayerManager snapshotHooks,
      boolean btrfsPortableFallbackAllowed) {
    this.strategy = java.util.Objects.requireNonNull(strategy, "strategy");
    this.copyEngine = java.util.Objects.requireNonNull(copyEngine, "copyEngine");
    this.overlayLayers = java.util.Objects.requireNonNull(overlayLayers, "overlayLayers");
    this.btrfsSnapshots = java.util.Objects.requireNonNull(btrfsSnapshots, "btrfsSnapshots");
    this.snapshotHooks = snapshotHooks;
    this.btrfsPortableFallbackAllowed = btrfsPortableFallbackAllowed;
    if (strategy == StorageStrategy.SNAPSHOT_HOOK && snapshotHooks == null) {
      throw new IllegalArgumentException("Snapshot-hook strategy requires a configured helper");
    }
  }

  void apply(Path destination, List<ResolvedVolume> volumes, BooleanSupplier cancellationRequested)
      throws IOException, InstancePreparationException {
    if (strategy == StorageStrategy.BTRFS) {
      applyBtrfs(destination, volumes, cancellationRequested);
      return;
    }
    if (strategy == StorageStrategy.SNAPSHOT_HOOK) {
      applySnapshotHook(destination, volumes, cancellationRequested);
      return;
    }
    if (strategy == StorageStrategy.OVERLAY || strategy == StorageStrategy.FUSE_OVERLAY) {
      applyOverlay(destination, volumes, cancellationRequested);
      return;
    }
    applyPortable(volumes, cancellationRequested);
  }

  private void applyOverlay(
      Path destination, List<ResolvedVolume> volumes, BooleanSupplier cancellationRequested)
      throws IOException, InstancePreparationException {
    Map<Path, List<ResolvedVolume>> overlayTargets = new LinkedHashMap<>();
    List<ResolvedVolume> portableVolumes = new ArrayList<>();
    for (ResolvedVolume volume : volumes) {
      if (volume.volume().mode() == BlueprintVolume.Mode.COW) {
        overlayTargets.computeIfAbsent(volume.target(), ignored -> new ArrayList<>()).add(volume);
      } else {
        portableVolumes.add(volume);
      }
    }
    applyPortable(portableVolumes, cancellationRequested);
    if (overlayTargets.isEmpty()) {
      return;
    }

    List<OverlayFsLayerManager.Layer> layers = new ArrayList<>();
    for (Map.Entry<Path, List<ResolvedVolume>> entry : overlayTargets.entrySet()) {
      checkCancelled(cancellationRequested);
      if (Files.exists(entry.getKey(), LinkOption.NOFOLLOW_LINKS)) {
        throw collision(entry.getValue().getFirst());
      }
      List<Path> lowers = new ArrayList<>();
      for (ResolvedVolume volume : entry.getValue()) {
        copyEngine.validateSource(volume.source(), cancellationRequested);
        lowers.add(volume.source());
      }
      layers.add(new OverlayFsLayerManager.Layer(destination.relativize(entry.getKey()), lowers));
    }
    overlayLayers.prepare(destination, layers);
  }

  private void applyBtrfs(
      Path destination, List<ResolvedVolume> volumes, BooleanSupplier cancellationRequested)
      throws IOException, InstancePreparationException {
    if (btrfsSnapshots.hasManifest(destination)) {
      throw new InstancePreparationException(
          "Software content collides with reserved Btrfs metadata: "
              + destination.resolve(BtrfsSnapshotManager.MANIFEST_FILE));
    }
    Map<Path, ResolvedVolume> appliedTargets = new LinkedHashMap<>();
    for (ResolvedVolume volume : volumes) {
      checkCancelled(cancellationRequested);
      ResolvedVolume first = appliedTargets.get(volume.target());
      rejectInitialCollision(first, volume);
      if (first != null) {
        copyEngine.mergeDirectoryFirstWins(volume.source(), volume.target(), cancellationRequested);
        continue;
      }
      if (volume.volume().mode() != BlueprintVolume.Mode.COW) {
        copyEngine.copyDirectory(volume.source(), volume.target(), cancellationRequested);
        appliedTargets.put(volume.target(), volume);
        continue;
      }
      copyEngine.validateSource(volume.source(), cancellationRequested);
      if (!btrfsSnapshots.isEligibleSource(volume.source())) {
        if (!btrfsPortableFallbackAllowed) {
          throw new InstancePreparationException(
              "Explicit Btrfs strategy requires a subvolume "
                  + "source for COW volume "
                  + volume.volume().name()
                  + ": "
                  + volume.source());
        }
        copyEngine.copyDirectory(volume.source(), volume.target(), cancellationRequested);
        appliedTargets.put(volume.target(), volume);
        continue;
      }
      btrfsSnapshots.snapshot(
          destination, volume.source(), destination.relativize(volume.target()));
      appliedTargets.put(volume.target(), volume);
    }
  }

  private void applySnapshotHook(
      Path destination, List<ResolvedVolume> volumes, BooleanSupplier cancellationRequested)
      throws IOException, InstancePreparationException {
    if (snapshotHooks.hasManifest(destination)) {
      throw new InstancePreparationException(
          "Software content collides with reserved snapshot-hook metadata");
    }
    Map<Path, ResolvedVolume> appliedTargets = new LinkedHashMap<>();
    for (ResolvedVolume volume : volumes) {
      checkCancelled(cancellationRequested);
      ResolvedVolume first = appliedTargets.get(volume.target());
      rejectInitialCollision(first, volume);
      if (first != null) {
        copyEngine.mergeDirectoryFirstWins(volume.source(), volume.target(), cancellationRequested);
      } else if (volume.volume().mode() == BlueprintVolume.Mode.COW) {
        copyEngine.validateSource(volume.source(), cancellationRequested);
        snapshotHooks.prepare(
            destination, volume.source(), destination.relativize(volume.target()));
        appliedTargets.put(volume.target(), volume);
      } else {
        copyEngine.copyDirectory(volume.source(), volume.target(), cancellationRequested);
        appliedTargets.put(volume.target(), volume);
      }
    }
  }

  private void applyPortable(List<ResolvedVolume> volumes, BooleanSupplier cancellationRequested)
      throws IOException, InstancePreparationException {
    Map<Path, ResolvedVolume> appliedTargets = new LinkedHashMap<>();
    for (ResolvedVolume volume : volumes) {
      checkCancelled(cancellationRequested);
      ResolvedVolume first = appliedTargets.get(volume.target());
      if (first == null && Files.exists(volume.target())) {
        throw collision(volume);
      }
      if (first == null) {
        copyEngine.copyDirectory(volume.source(), volume.target(), cancellationRequested);
        appliedTargets.put(volume.target(), volume);
      } else {
        copyEngine.mergeDirectoryFirstWins(volume.source(), volume.target(), cancellationRequested);
      }
    }
  }

  private static void rejectInitialCollision(ResolvedVolume first, ResolvedVolume volume)
      throws InstancePreparationException {
    if (first == null && Files.exists(volume.target(), LinkOption.NOFOLLOW_LINKS)) {
      throw collision(volume);
    }
  }

  private static InstancePreparationException collision(ResolvedVolume volume) {
    return new InstancePreparationException(
        "Volume target collides with existing instance content: " + volume.volume().target());
  }

  private static void checkCancelled(BooleanSupplier cancellationRequested)
      throws VolumeApplicationCancelledException {
    if (cancellationRequested.getAsBoolean()) {
      throw new VolumeApplicationCancelledException();
    }
  }

  private static final class VolumeApplicationCancelledException extends IOException {

    private VolumeApplicationCancelledException() {
      super("Instance preparation was cancelled");
    }
  }
}
