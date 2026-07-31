package net.slimelabs.slslite.instance.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.function.BooleanSupplier;
import net.slimelabs.slslite.blueprint.BlueprintCopy;
import net.slimelabs.slslite.blueprint.BlueprintVolume;
import net.slimelabs.slslite.config.StorageConfig;
import net.slimelabs.slslite.config.StorageStrategy;
import net.slimelabs.slslite.instance.InstancePreparationException;
import net.slimelabs.slslite.instance.model.InstanceIdGenerator;
import net.slimelabs.slslite.instance.storage.BlueprintContentResolver.ResolvedCopy;
import net.slimelabs.slslite.instance.storage.BlueprintContentResolver.ResolvedVolume;

public final class InstanceDirectoryPreparer {

  private static final int MAX_COPY_PARALLELISM = 4;

  private final Path instancesRoot;
  private final BlueprintContentResolver contentResolver;
  private final DirectoryCopyEngine copyEngine;
  private final VolumeApplicator volumeApplicator;
  private final PreparedStorageLifecycle storageLifecycle;
  private final PersistentInstanceTransaction replacementTransaction;

  public InstanceDirectoryPreparer(Path instancesRoot) {
    this(instancesRoot, instancesRoot);
  }

  public InstanceDirectoryPreparer(Path instancesRoot, Path contentRoot) {
    this(
        instancesRoot,
        contentRoot,
        new PortableFileCopyOperation(),
        Thread::sleep,
        StorageStrategy.COPY,
        new OverlayFsLayerManager(instancesRoot, contentRoot),
        new BtrfsSnapshotManager(instancesRoot, contentRoot),
        false,
        productionCopyParallelism());
  }

  public InstanceDirectoryPreparer(
      Path instancesRoot,
      Path contentRoot,
      StorageStrategy requestedStrategy,
      StorageStrategy selectedStrategy) {
    this(
        instancesRoot,
        contentRoot,
        fileCopyOperation(requestedStrategy, selectedStrategy),
        Thread::sleep,
        selectedStrategy,
        overlayLayerManager(instancesRoot, contentRoot, selectedStrategy),
        new BtrfsSnapshotManager(instancesRoot, contentRoot),
        requestedStrategy == StorageStrategy.AUTO,
        productionCopyParallelism());
  }

  public InstanceDirectoryPreparer(
      Path instancesRoot,
      Path contentRoot,
      StorageConfig storage,
      StorageStrategy selectedStrategy) {
    this(
        instancesRoot,
        contentRoot,
        fileCopyOperation(storage.strategy(), selectedStrategy),
        Thread::sleep,
        selectedStrategy,
        overlayLayerManager(instancesRoot, contentRoot, selectedStrategy),
        new BtrfsSnapshotManager(instancesRoot, contentRoot),
        storage.strategy() == StorageStrategy.AUTO,
        productionCopyParallelism(),
        selectedStrategy == StorageStrategy.SNAPSHOT_HOOK
            ? new SnapshotHookLayerManager(
                instancesRoot,
                contentRoot,
                new SnapshotHookClient(
                    storage.snapshotHookExecutable(), storage.snapshotHookTimeoutSeconds()))
            : null);
  }

  InstanceDirectoryPreparer(
      Path instancesRoot, Path contentRoot, FileCopyOperation fileCopy, RetrySleeper retrySleeper) {
    this(
        instancesRoot,
        contentRoot,
        fileCopy,
        retrySleeper,
        StorageStrategy.COPY,
        new OverlayFsLayerManager(instancesRoot, contentRoot),
        new BtrfsSnapshotManager(instancesRoot, contentRoot),
        false,
        1);
  }

  InstanceDirectoryPreparer(
      Path instancesRoot,
      Path contentRoot,
      FileCopyOperation fileCopy,
      RetrySleeper retrySleeper,
      StorageStrategy selectedStrategy,
      OverlayFsLayerManager overlayLayers) {
    this(
        instancesRoot,
        contentRoot,
        fileCopy,
        retrySleeper,
        selectedStrategy,
        overlayLayers,
        new BtrfsSnapshotManager(instancesRoot, contentRoot),
        false,
        1);
  }

  InstanceDirectoryPreparer(
      Path instancesRoot,
      Path contentRoot,
      FileCopyOperation fileCopy,
      RetrySleeper retrySleeper,
      StorageStrategy selectedStrategy,
      OverlayFsLayerManager overlayLayers,
      int copyParallelism) {
    this(
        instancesRoot,
        contentRoot,
        fileCopy,
        retrySleeper,
        selectedStrategy,
        overlayLayers,
        new BtrfsSnapshotManager(instancesRoot, contentRoot),
        false,
        copyParallelism);
  }

  InstanceDirectoryPreparer(
      Path instancesRoot,
      Path contentRoot,
      FileCopyOperation fileCopy,
      RetrySleeper retrySleeper,
      StorageStrategy selectedStrategy,
      OverlayFsLayerManager overlayLayers,
      BtrfsSnapshotManager btrfsSnapshots,
      boolean btrfsPortableFallbackAllowed,
      int copyParallelism) {
    this(
        instancesRoot,
        contentRoot,
        fileCopy,
        retrySleeper,
        selectedStrategy,
        overlayLayers,
        btrfsSnapshots,
        btrfsPortableFallbackAllowed,
        copyParallelism,
        null);
  }

  InstanceDirectoryPreparer(
      Path instancesRoot,
      Path contentRoot,
      FileCopyOperation fileCopy,
      RetrySleeper retrySleeper,
      StorageStrategy selectedStrategy,
      OverlayFsLayerManager overlayLayers,
      BtrfsSnapshotManager btrfsSnapshots,
      boolean btrfsPortableFallbackAllowed,
      int copyParallelism,
      SnapshotHookLayerManager snapshotHooks) {
    if (copyParallelism < 1 || copyParallelism > MAX_COPY_PARALLELISM) {
      throw new IllegalArgumentException(
          "Copy parallelism must be between 1 and " + MAX_COPY_PARALLELISM);
    }
    this.instancesRoot = instancesRoot.toAbsolutePath().normalize();
    this.contentResolver = new BlueprintContentResolver(this.instancesRoot, contentRoot);
    this.copyEngine = new DirectoryCopyEngine(fileCopy, retrySleeper, copyParallelism);
    java.util.Objects.requireNonNull(selectedStrategy, "selectedStrategy");
    java.util.Objects.requireNonNull(overlayLayers, "overlayLayers");
    java.util.Objects.requireNonNull(btrfsSnapshots, "btrfsSnapshots");
    if (selectedStrategy == StorageStrategy.SNAPSHOT_HOOK && snapshotHooks == null) {
      throw new IllegalArgumentException("Snapshot-hook strategy requires a configured helper");
    }
    this.volumeApplicator =
        new VolumeApplicator(
            selectedStrategy,
            copyEngine,
            overlayLayers,
            btrfsSnapshots,
            snapshotHooks,
            btrfsPortableFallbackAllowed);
    this.storageLifecycle =
        new PreparedStorageLifecycle(overlayLayers, btrfsSnapshots, snapshotHooks);
    this.replacementTransaction =
        new PersistentInstanceTransaction(this.instancesRoot, storageLifecycle);
  }

  private static int productionCopyParallelism() {
    return Math.max(1, Math.min(MAX_COPY_PARALLELISM, Runtime.getRuntime().availableProcessors()));
  }

  private static FileCopyOperation fileCopyOperation(
      StorageStrategy requestedStrategy, StorageStrategy selectedStrategy) {
    java.util.Objects.requireNonNull(requestedStrategy, "requestedStrategy");
    java.util.Objects.requireNonNull(selectedStrategy, "selectedStrategy");
    return switch (selectedStrategy) {
      case COPY -> new PortableFileCopyOperation();
      case REFLINK -> new ReflinkFileCopyOperation(requestedStrategy == StorageStrategy.AUTO);
      case OVERLAY, FUSE_OVERLAY, BTRFS, SNAPSHOT_HOOK -> new PortableFileCopyOperation();
      case AUTO ->
          throw new IllegalArgumentException(
              "Storage strategy is not implemented for instance "
                  + "preparation: "
                  + selectedStrategy.configValue());
    };
  }

  private static OverlayFsLayerManager overlayLayerManager(
      Path instancesRoot, Path contentRoot, StorageStrategy selectedStrategy) {
    if (selectedStrategy == StorageStrategy.FUSE_OVERLAY) {
      return new OverlayFsLayerManager(instancesRoot, contentRoot, new FuseOverlayFsMountAdapter());
    }
    return new OverlayFsLayerManager(instancesRoot, contentRoot);
  }

  public Path root() {
    return instancesRoot;
  }

  public Path prepare(String instanceId, Path sourceDirectory) throws InstancePreparationException {
    return prepare(instanceId, sourceDirectory, List.of());
  }

  public Path prepare(String instanceId, Path sourceDirectory, List<BlueprintVolume> volumes)
      throws InstancePreparationException {
    return prepare(instanceId, sourceDirectory, volumes, List.of(), () -> false);
  }

  public Path prepare(
      String instanceId,
      Path sourceDirectory,
      List<BlueprintVolume> volumes,
      BooleanSupplier cancellationRequested)
      throws InstancePreparationException {
    return prepare(instanceId, sourceDirectory, volumes, List.of(), cancellationRequested);
  }

  public Path prepare(
      String instanceId,
      Path sourceDirectory,
      List<BlueprintVolume> volumes,
      List<BlueprintCopy> copies,
      BooleanSupplier cancellationRequested)
      throws InstancePreparationException {
    Path destination = destination(instanceId);
    Path source = sourceDirectory.toAbsolutePath().normalize();
    java.util.Objects.requireNonNull(cancellationRequested, "cancellationRequested");

    if (!Files.isDirectory(source)) {
      throw new InstancePreparationException("Software base directory does not exist: " + source);
    }
    if (destination.startsWith(source)) {
      throw new InstancePreparationException(
          "Software base directory cannot contain the instances directory: " + source);
    }
    if (Files.exists(destination)) {
      throw new InstancePreparationException("Instance directory already exists: " + destination);
    }

    try {
      checkCancelled(cancellationRequested);
      List<ResolvedVolume> resolvedVolumes = contentResolver.resolveVolumes(volumes, destination);
      List<ResolvedCopy> resolvedCopies = contentResolver.resolveCopies(copies, destination);
      Files.createDirectories(instancesRoot);
      copyEngine.copyDirectory(source, destination, cancellationRequested);
      volumeApplicator.apply(destination, resolvedVolumes, cancellationRequested);
      applyCopies(resolvedCopies, cancellationRequested);
      checkCancelled(cancellationRequested);
      return destination;
    } catch (IOException | InstancePreparationException exception) {
      try {
        storageLifecycle.delete(destination);
      } catch (IOException cleanupException) {
        exception.addSuppressed(cleanupException);
      }
      throw new InstancePreparationException(
          "Unable to prepare instance directory " + destination + ": " + exception.getMessage(),
          exception);
    }
  }

  public void delete(String instanceId) throws InstancePreparationException {
    Path destination = destination(instanceId);
    try {
      storageLifecycle.delete(destination);
    } catch (IOException exception) {
      throw new InstancePreparationException(
          "Unable to delete instance directory " + destination, exception);
    }
  }

  public boolean deletePersistent(String instanceId) throws InstancePreparationException {
    Path destination = destination(instanceId);
    if (!Files.isDirectory(destination, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
      throw new InstancePreparationException(
          "Persistent instance directory does not exist: " + destination);
    }
    return replacementTransaction.delete(instanceId, destination);
  }

  public void resume(String instanceId) throws InstancePreparationException {
    Path destination = destination(instanceId);
    try {
      storageLifecycle.resume(destination);
    } catch (IOException | RuntimeException exception) {
      throw new InstancePreparationException(
          "Unable to resume instance storage " + destination, exception);
    }
  }

  public void suspend(String instanceId) throws InstancePreparationException {
    Path destination = destination(instanceId);
    try {
      storageLifecycle.suspend(destination);
    } catch (IOException | RuntimeException exception) {
      throw new InstancePreparationException(
          "Unable to suspend instance storage " + destination, exception);
    }
  }

  public void replace(String instanceId, Path sourceDirectory, DirectoryInitializer initializer)
      throws InstancePreparationException {
    replace(instanceId, sourceDirectory, List.of(), initializer);
  }

  public void replace(
      String instanceId,
      Path sourceDirectory,
      List<BlueprintVolume> volumes,
      DirectoryInitializer initializer)
      throws InstancePreparationException {
    replace(instanceId, sourceDirectory, volumes, List.of(), initializer);
  }

  public void replace(
      String instanceId,
      Path sourceDirectory,
      List<BlueprintVolume> volumes,
      List<BlueprintCopy> copies,
      DirectoryInitializer initializer)
      throws InstancePreparationException {
    Path destination = destination(instanceId);
    Path source = sourceDirectory.toAbsolutePath().normalize();
    if (!Files.isDirectory(source)) {
      throw new InstancePreparationException("Software base directory does not exist: " + source);
    }
    if (!Files.isDirectory(destination)) {
      throw new InstancePreparationException(
          "Persistent instance directory does not exist: " + destination);
    }
    if (destination.startsWith(source) || source.startsWith(destination)) {
      throw new InstancePreparationException(
          "Software base and persistent instance directories must not overlap");
    }

    replacementTransaction.replace(
        instanceId,
        destination,
        staging -> {
          List<ResolvedVolume> resolvedVolumes = contentResolver.resolveVolumes(volumes, staging);
          List<ResolvedCopy> resolvedCopies = contentResolver.resolveCopies(copies, staging);
          copyEngine.copyDirectory(source, staging, () -> false);
          volumeApplicator.apply(staging, resolvedVolumes, () -> false);
          applyCopies(resolvedCopies, () -> false);
        },
        directory -> initializer.initialize(directory));
  }

  public int recoverInterruptedReplacements(DirectoryCommitVerifier verifier) throws IOException {
    java.util.Objects.requireNonNull(verifier, "verifier");
    return replacementTransaction.recover(verifier::isCommitted);
  }

  private void applyCopies(List<ResolvedCopy> copies, BooleanSupplier cancellationRequested)
      throws IOException, InstancePreparationException {
    for (ResolvedCopy copy : copies) {
      checkCancelled(cancellationRequested);
      if (copy.directory()) {
        if (Files.exists(copy.target(), LinkOption.NOFOLLOW_LINKS)
            && !Files.isDirectory(copy.target(), LinkOption.NOFOLLOW_LINKS)) {
          throw new InstancePreparationException(
              "Copy directory target is not a directory: " + copy.copy().target());
        }
        copyEngine.copyDirectoryReplacing(copy.source(), copy.target(), cancellationRequested);
      } else {
        if (Files.isDirectory(copy.target(), LinkOption.NOFOLLOW_LINKS)) {
          throw new InstancePreparationException(
              "Copy file target is a directory: " + copy.copy().target());
        }
        Files.createDirectories(copy.target().getParent());
        Files.deleteIfExists(copy.target());
        copyEngine.copyFile(copy.source(), copy.target(), cancellationRequested);
      }
    }
  }

  private Path destination(String instanceId) throws InstancePreparationException {
    if (!InstanceIdGenerator.isValid(instanceId)) {
      throw new InstancePreparationException("Invalid instance ID: " + instanceId);
    }

    Path destination = instancesRoot.resolve(instanceId).normalize();
    if (!destination.startsWith(instancesRoot) || destination.equals(instancesRoot)) {
      throw new InstancePreparationException(
          "Instance directory must stay inside " + instancesRoot);
    }
    return destination;
  }

  private static void checkCancelled(BooleanSupplier cancellationRequested)
      throws PreparationCancelledException {
    if (cancellationRequested.getAsBoolean()) {
      throw new PreparationCancelledException();
    }
  }

  @FunctionalInterface
  public interface DirectoryInitializer {
    void initialize(Path directory) throws Exception;
  }

  @FunctionalInterface
  public interface DirectoryCommitVerifier {
    boolean isCommitted(Path directory, String instanceId) throws IOException;
  }

  @FunctionalInterface
  interface FileCopyOperation {
    void copy(Path source, Path target) throws IOException;
  }

  @FunctionalInterface
  interface RetrySleeper {
    void sleep(long milliseconds) throws InterruptedException;
  }

  private static final class PreparationCancelledException extends IOException {

    private PreparationCancelledException() {
      super("Instance preparation was cancelled");
    }
  }
}
