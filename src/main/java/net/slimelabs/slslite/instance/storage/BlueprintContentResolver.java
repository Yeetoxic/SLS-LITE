package net.slimelabs.slslite.instance.storage;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import net.slimelabs.slslite.blueprint.BlueprintCopy;
import net.slimelabs.slslite.blueprint.BlueprintVolume;
import net.slimelabs.slslite.instance.InstancePreparationException;

/**
 * Resolves untrusted blueprint volume and copy declarations into contained,
 * normalized filesystem operations.
 */
final class BlueprintContentResolver {

  private final Path instancesRoot;
  private final Path contentRoot;

  BlueprintContentResolver(Path instancesRoot, Path contentRoot) {
    this.instancesRoot = instancesRoot.toAbsolutePath().normalize();
    this.contentRoot = contentRoot.toAbsolutePath().normalize();
  }

  List<ResolvedVolume> resolveVolumes(List<BlueprintVolume> volumes, Path destination)
      throws IOException, InstancePreparationException {
    if (volumes.isEmpty()) {
      return List.of();
    }

    Path realContentRoot = contentRoot.toRealPath();
    Path normalizedInstancesRoot =
        Files.exists(instancesRoot) ? instancesRoot.toRealPath() : instancesRoot;
    List<ResolvedVolume> resolved = new ArrayList<>();
    for (BlueprintVolume volume : volumes) {
      if (volume.mode() == BlueprintVolume.Mode.RW) {
        throw new InstancePreparationException(
            "Volume '"
                + volume.name()
                + "' uses mode rw. SLS-LITE "
                + "cannot safely emulate a shared writable host mount; "
                + "use cow or manage this server outside SLS-LITE");
      }

      Path source = resolveVolumeSource(volume, realContentRoot);
      Path target = resolveVolumeTarget(volume, destination);
      if (source.startsWith(normalizedInstancesRoot)) {
        throw new InstancePreparationException(
            "Volume source '" + volume.source() + "' must not read from the instances directory");
      }
      for (ResolvedVolume previous : resolved) {
        boolean sameCowTarget =
            target.equals(previous.target())
                && volume.mode() == BlueprintVolume.Mode.COW
                && previous.volume().mode() == BlueprintVolume.Mode.COW;
        if (!sameCowTarget
            && (target.startsWith(previous.target()) || previous.target().startsWith(target))) {
          throw new InstancePreparationException(
              "Volume targets overlap: '"
                  + previous.volume().target()
                  + "' and '"
                  + volume.target()
                  + "'");
        }
      }
      resolved.add(new ResolvedVolume(volume, source, target));
    }
    return List.copyOf(resolved);
  }

  List<ResolvedCopy> resolveCopies(List<BlueprintCopy> copies, Path destination)
      throws IOException, InstancePreparationException {
    if (copies.isEmpty()) {
      return List.of();
    }

    Path realContentRoot = contentRoot.toRealPath();
    Path normalizedInstancesRoot =
        Files.exists(instancesRoot) ? instancesRoot.toRealPath() : instancesRoot;
    List<ResolvedCopy> resolved = new ArrayList<>();
    for (BlueprintCopy copy : copies) {
      String configuredSource = portableCopyPath(copy.source(), "source");
      Path relativeSource = configuredCopyPath(configuredSource, "source");
      if (relativeSource.isAbsolute()) {
        throw new InstancePreparationException(
            "Copy source must be relative to " + contentRoot + ": " + copy.source());
      }
      Path source = contentRoot.resolve(relativeSource).normalize();
      if (source.equals(contentRoot) || !source.startsWith(contentRoot)) {
        throw new InstancePreparationException(
            "Copy source must stay inside " + contentRoot + ": " + copy.source());
      }
      if (!Files.exists(source, LinkOption.NOFOLLOW_LINKS)) {
        throw new InstancePreparationException("Copy source does not exist: " + source);
      }
      rejectSymbolicPathSegments(contentRoot, source);
      Path realSource = source.toRealPath();
      if (!realSource.startsWith(realContentRoot)
          || realSource.startsWith(normalizedInstancesRoot)) {
        throw new InstancePreparationException(
            "Copy source must stay in managed content outside instances: " + copy.source());
      }
      BasicFileAttributes attributes =
          Files.readAttributes(realSource, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (!attributes.isDirectory() && !attributes.isRegularFile()) {
        throw new InstancePreparationException(
            "Copy source must be a regular file or directory: " + realSource);
      }

      String configuredTarget = portableCopyPath(copy.target(), "target");
      if (configuredTarget.startsWith("/")) {
        throw new InstancePreparationException(
            "Copy target must be relative to the instance: " + copy.target());
      }
      Path relativeTarget = configuredCopyPath(configuredTarget, "target");
      Path target = destination.resolve(relativeTarget).normalize();
      if (relativeTarget.toString().isBlank()
          || target.equals(destination)
          || !target.startsWith(destination)) {
        throw new InstancePreparationException(
            "Copy target must stay inside the instance: " + copy.target());
      }
      resolved.add(new ResolvedCopy(copy, realSource, target, attributes.isDirectory()));
    }
    return List.copyOf(resolved);
  }

  private Path resolveVolumeSource(BlueprintVolume volume, Path realContentRoot)
      throws IOException, InstancePreparationException {
    String configured = portablePath(volume.source(), "source", volume.name());
    Path relative = configuredPath(configured, "source", volume.name());
    if (relative.isAbsolute()) {
      throw new InstancePreparationException(
          "Volume source must be relative to " + contentRoot + ": " + volume.source());
    }

    Path source = contentRoot.resolve(relative).normalize();
    if (source.equals(contentRoot) || !source.startsWith(contentRoot)) {
      throw new InstancePreparationException(
          "Volume source must stay inside " + contentRoot + ": " + volume.source());
    }
    if (!Files.isDirectory(source)) {
      throw new InstancePreparationException("Volume source directory does not exist: " + source);
    }

    rejectSymbolicPathSegments(contentRoot, source);
    Path realSource = source.toRealPath();
    if (!realSource.startsWith(realContentRoot)) {
      throw new InstancePreparationException(
          "Volume source resolves outside " + contentRoot + ": " + volume.source());
    }
    return realSource;
  }

  private static Path resolveVolumeTarget(BlueprintVolume volume, Path destination)
      throws InstancePreparationException {
    String configured = portablePath(volume.target(), "target", volume.name());
    if (configured.startsWith("//")) {
      throw new InstancePreparationException(
          "Volume target must be an instance path such as '/world': " + volume.target());
    }
    String instanceRelative = configured.startsWith("/") ? configured.substring(1) : configured;
    Path relative = configuredPath(instanceRelative, "target", volume.name());
    if (relative.isAbsolute() || instanceRelative.isBlank()) {
      throw new InstancePreparationException(
          "Volume target must identify a directory inside the instance: " + volume.target());
    }

    Path target = destination.resolve(relative).normalize();
    if (target.equals(destination) || !target.startsWith(destination)) {
      throw new InstancePreparationException(
          "Volume target must stay inside the instance: " + volume.target());
    }
    return target;
  }

  private static String portableCopyPath(String configured, String field)
      throws InstancePreparationException {
    String value = configured.trim();
    if (value.indexOf('\\') >= 0) {
      throw new InstancePreparationException(
          "Copy " + field + " must use portable '/' separators: " + configured);
    }
    return value;
  }

  private static Path configuredCopyPath(String value, String field)
      throws InstancePreparationException {
    try {
      return Path.of(value);
    } catch (InvalidPathException exception) {
      throw new InstancePreparationException("Invalid copy " + field + ": " + value, exception);
    }
  }

  private static String portablePath(String configured, String field, String name)
      throws InstancePreparationException {
    String value = configured.trim();
    if (value.indexOf('\\') >= 0) {
      throw new InstancePreparationException(
          "Volume "
              + field
              + " for '"
              + name
              + "' must use portable '/' separators: "
              + configured);
    }
    return value;
  }

  private static Path configuredPath(String value, String field, String name)
      throws InstancePreparationException {
    try {
      return Path.of(value);
    } catch (InvalidPathException exception) {
      throw new InstancePreparationException(
          "Invalid volume " + field + " for '" + name + "': " + value, exception);
    }
  }

  private static void rejectSymbolicPathSegments(Path root, Path source)
      throws IOException, InstancePreparationException {
    Path current = root;
    for (Path segment : root.relativize(source)) {
      current = current.resolve(segment);
      BasicFileAttributes attributes =
          Files.readAttributes(current, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
      if (attributes.isSymbolicLink() || attributes.isOther()) {
        throw new InstancePreparationException(
            "Volume source paths must not contain symbolic links or "
                + "special filesystem entries: "
                + current);
      }
    }
  }

  record ResolvedVolume(BlueprintVolume volume, Path source, Path target) {}

  record ResolvedCopy(BlueprintCopy copy, Path source, Path target, boolean directory) {}
}
