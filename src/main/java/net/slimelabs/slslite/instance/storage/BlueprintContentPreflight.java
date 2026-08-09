package net.slimelabs.slslite.instance.storage;

import java.io.IOException;
import java.nio.file.AccessDeniedException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import net.slimelabs.slslite.blueprint.Blueprint;
import net.slimelabs.slslite.blueprint.BlueprintCopy;
import net.slimelabs.slslite.blueprint.BlueprintPersistentFile;
import net.slimelabs.slslite.blueprint.BlueprintVolume;
import net.slimelabs.slslite.config.StorageConfig;
import net.slimelabs.slslite.config.StorageStrategy;
import net.slimelabs.slslite.instance.InstancePreparationException;
import net.slimelabs.slslite.instance.storage.BlueprintContentResolver.ResolvedVolume;

/** Runs the existing confined source/target resolver without creating an instance. */
public final class BlueprintContentPreflight {

  private static final int MAXIMUM_PROBLEMS = 8;
  private final BlueprintContentResolver resolver;
  private final Path destination;
  private final Path instancesRoot;
  private final StorageConfig storage;
  private final StorageStrategy selectedStrategy;
  private final BtrfsSnapshotManager btrfs;
  private final PersistentFileStateManager persistentFiles;

  public BlueprintContentPreflight(
      Path instancesRoot,
      Path contentRoot,
      StorageConfig storage,
      StorageStrategy selectedStrategy) {
    Path normalizedInstances = instancesRoot.toAbsolutePath().normalize();
    this.resolver = new BlueprintContentResolver(normalizedInstances, contentRoot);
    this.destination = normalizedInstances.resolve(".readiness-preflight");
    this.instancesRoot = normalizedInstances;
    this.storage = java.util.Objects.requireNonNull(storage, "storage");
    this.selectedStrategy = java.util.Objects.requireNonNull(selectedStrategy, "selectedStrategy");
    this.btrfs = new BtrfsSnapshotManager(normalizedInstances, contentRoot);
    this.persistentFiles = new PersistentFileStateManager(normalizedInstances, contentRoot);
  }

  public List<Problem> inspect(Blueprint blueprint) {
    List<Problem> problems = new ArrayList<>();
    for (BlueprintVolume volume : blueprint.volumes()) {
      inspectVolume(volume, problems);
      if (problems.size() >= MAXIMUM_PROBLEMS) {
        return List.copyOf(problems);
      }
    }
    if (problems.isEmpty() && blueprint.volumes().size() > 1) {
      inspectAllVolumes(blueprint.volumes(), problems);
    }
    for (BlueprintCopy copy : blueprint.copies()) {
      if (problems.size() >= MAXIMUM_PROBLEMS) {
        break;
      }
      inspectCopy(copy, problems);
    }
    if (!blueprint.persistentFiles().isEmpty() && problems.size() < MAXIMUM_PROBLEMS) {
      inspectPersistentFiles(blueprint.persistentFiles(), problems);
    }
    return List.copyOf(problems);
  }

  private void inspectVolume(BlueprintVolume volume, List<Problem> problems) {
    try {
      ResolvedVolume resolved = resolver.resolveVolumes(List.of(volume), destination).getFirst();
      inspectExplicitStorage(resolved, problems);
    } catch (IOException | InstancePreparationException exception) {
      problems.add(problem(exception));
    }
  }

  private void inspectExplicitStorage(ResolvedVolume resolved, List<Problem> problems)
      throws IOException {
    if (resolved.volume().mode() != BlueprintVolume.Mode.COW
        || storage.strategy() == StorageStrategy.AUTO) {
      return;
    }
    if (selectedStrategy == StorageStrategy.BTRFS && !btrfs.isEligibleSource(resolved.source())) {
      problems.add(
          new Problem(
              false,
              "explicit Btrfs strategy requires volume '"
                  + resolved.volume().name()
                  + "' to source a Btrfs subvolume without nested subvolumes"));
    } else if (selectedStrategy == StorageStrategy.REFLINK
        && !Files.getFileStore(resolved.source()).equals(Files.getFileStore(instancesRoot))) {
      problems.add(
          new Problem(
              false,
              "explicit reflink strategy requires volume '"
                  + resolved.volume().name()
                  + "' to share the instance filesystem"));
    }
  }

  private void inspectAllVolumes(List<BlueprintVolume> volumes, List<Problem> problems) {
    try {
      resolver.resolveVolumes(volumes, destination);
    } catch (IOException | InstancePreparationException exception) {
      problems.add(problem(exception));
    }
  }

  private void inspectCopy(BlueprintCopy copy, List<Problem> problems) {
    try {
      resolver.resolveCopies(List.of(copy), destination);
    } catch (IOException | InstancePreparationException exception) {
      problems.add(problem(exception));
    }
  }

  private void inspectPersistentFiles(List<BlueprintPersistentFile> files, List<Problem> problems) {
    try {
      persistentFiles.inspect(destination, files);
    } catch (IOException | InstancePreparationException exception) {
      problems.add(problem(exception));
    }
  }

  private static Problem problem(Exception exception) {
    boolean temporary =
        exception instanceof AccessDeniedException
            || exception instanceof FileSystemException
                && !(exception instanceof java.nio.file.NoSuchFileException);
    String message =
        exception.getMessage() == null
            ? exception.getClass().getSimpleName()
            : exception.getMessage();
    return new Problem(temporary, message);
  }

  public record Problem(boolean temporary, String message) {}
}
