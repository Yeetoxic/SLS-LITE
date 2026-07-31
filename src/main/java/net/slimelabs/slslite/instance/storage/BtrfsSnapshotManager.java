package net.slimelabs.slslite.instance.storage;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Properties;
import java.util.concurrent.TimeUnit;

final class BtrfsSnapshotManager {

  static final String MANIFEST_FILE = ".sls-lite-btrfs.properties";
  private static final String MANIFEST_VERSION = "1";
  private static final int MAX_SUBVOLUMES = 256;
  private static final long MAX_MANIFEST_BYTES = 1_048_576;

  private final Path instancesRoot;
  private final Path contentRoot;
  private final SubvolumeAdapter subvolumes;

  BtrfsSnapshotManager(Path instancesRoot, Path contentRoot) {
    this(instancesRoot, contentRoot, new CommandSubvolumeAdapter());
  }

  BtrfsSnapshotManager(Path instancesRoot, Path contentRoot, SubvolumeAdapter subvolumes) {
    this.instancesRoot = instancesRoot.toAbsolutePath().normalize();
    this.contentRoot = contentRoot.toAbsolutePath().normalize();
    this.subvolumes = java.util.Objects.requireNonNull(subvolumes, "subvolumes");
  }

  boolean hasManifest(Path instanceDirectory) {
    return Files.exists(instanceDirectory.resolve(MANIFEST_FILE), LinkOption.NOFOLLOW_LINKS);
  }

  boolean isEligibleSource(Path sourceDirectory) throws IOException {
    Path source = checkedSource(sourceDirectory);
    return subvolumes.isSubvolume(source) && !subvolumes.hasNestedSubvolumes(source);
  }

  void snapshot(Path instanceDirectory, Path sourceDirectory, Path relativeTarget)
      throws IOException {
    Path instance = checkedInstance(instanceDirectory);
    Path source = checkedSource(sourceDirectory);
    Path target = checkedTarget(instance, relativeTarget);
    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("Btrfs snapshot target already exists: " + target);
    }
    if (!subvolumes.isSubvolume(source)) {
      throw new IneligibleSourceException("Btrfs snapshot source is not a subvolume: " + source);
    }
    if (subvolumes.hasNestedSubvolumes(source)) {
      throw new IneligibleSourceException(
          "Btrfs snapshot source contains nested subvolumes: " + source);
    }

    List<Path> targets = new ArrayList<>(readManifest(instance));
    if (targets.contains(target)) {
      throw new IOException("Btrfs snapshot target is already declared: " + target);
    }
    rejectOverlappingTarget(targets, target);
    Files.createDirectories(target.getParent());
    targets.add(target);
    writeManifest(instance, targets);
    try {
      subvolumes.snapshot(source, target);
      if (!subvolumes.isSubvolume(target)) {
        throw new IOException("Btrfs snapshot command did not create a subvolume: " + target);
      }
    } catch (IOException | RuntimeException exception) {
      boolean removed = false;
      try {
        if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
          removed = true;
        } else if (subvolumes.isSubvolume(target)) {
          subvolumes.delete(target);
          removed = true;
        }
      } catch (IOException cleanupFailure) {
        exception.addSuppressed(cleanupFailure);
      }
      if (removed) {
        targets.remove(target);
        try {
          writeOrDeleteManifest(instance, targets);
        } catch (IOException cleanupFailure) {
          exception.addSuppressed(cleanupFailure);
        }
      }
      throw exception;
    }
  }

  void deleteSnapshots(Path instanceDirectory) throws IOException {
    Path instance = checkedInstance(instanceDirectory);
    List<Path> targets = new ArrayList<>(readManifest(instance));
    targets.sort(Comparator.comparingInt(Path::getNameCount).reversed());
    IOException failure = null;
    for (Path target : targets) {
      if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
        continue;
      }
      try {
        if (!subvolumes.isSubvolume(target)) {
          throw new IOException(
              "Refusing to traverse a declared Btrfs snapshot "
                  + "that is not a subvolume: "
                  + target);
        }
        subvolumes.delete(target);
      } catch (IOException exception) {
        if (failure == null) {
          failure = exception;
        } else {
          failure.addSuppressed(exception);
        }
      }
    }
    if (failure != null) {
      throw failure;
    }
    Files.delete(instance.resolve(MANIFEST_FILE));
  }

  private List<Path> readManifest(Path instance) throws IOException {
    Path manifest = instance.resolve(MANIFEST_FILE);
    if (!Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)) {
      return List.of();
    }
    if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)
        || Files.size(manifest) > MAX_MANIFEST_BYTES) {
      throw new IOException("Btrfs manifest is missing, unsafe, or too large: " + manifest);
    }
    Properties properties = new Properties();
    try (InputStream input = Files.newInputStream(manifest)) {
      properties.load(input);
    }
    if (!MANIFEST_VERSION.equals(properties.getProperty("version"))) {
      throw new IOException("Unsupported Btrfs manifest version: " + manifest);
    }
    int count = boundedCount(properties.getProperty("subvolumes"));
    if (count == 0) {
      throw new IOException("Btrfs manifest declares no subvolumes: " + manifest);
    }
    List<Path> targets = new ArrayList<>();
    for (int index = 0; index < count; index++) {
      String value = properties.getProperty("subvolume." + index);
      if (value == null || value.isBlank()) {
        throw new IOException("Missing Btrfs manifest subvolume " + index);
      }
      Path target = checkedTarget(instance, Path.of(value));
      rejectOverlappingTarget(targets, target);
      targets.add(target);
    }
    return List.copyOf(targets);
  }

  private void writeOrDeleteManifest(Path instance, List<Path> targets) throws IOException {
    if (targets.isEmpty()) {
      Files.deleteIfExists(instance.resolve(MANIFEST_FILE));
      return;
    }
    writeManifest(instance, targets);
  }

  private void writeManifest(Path instance, List<Path> targets) throws IOException {
    Properties properties = new Properties();
    properties.setProperty("version", MANIFEST_VERSION);
    properties.setProperty("subvolumes", Integer.toString(targets.size()));
    for (int index = 0; index < targets.size(); index++) {
      properties.setProperty(
          "subvolume." + index, portableRelative(instance.relativize(targets.get(index))));
    }
    Path manifest = instance.resolve(MANIFEST_FILE);
    Path temporary = instance.resolve(MANIFEST_FILE + ".tmp");
    try (OutputStream output = Files.newOutputStream(temporary)) {
      properties.store(output, "SLS-LITE Btrfs snapshot manifest");
    }
    try {
      Files.move(
          temporary, manifest, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException exception) {
      Files.move(temporary, manifest, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private Path checkedInstance(Path value) throws IOException {
    Path instance = value.toAbsolutePath().normalize();
    if (instance.equals(instancesRoot)
        || !instance.startsWith(instancesRoot)
        || !Files.isDirectory(instance, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("Btrfs instance is missing or outside " + instancesRoot);
    }
    Path realRoot = instancesRoot.toRealPath();
    Path realInstance = instance.toRealPath();
    if (realInstance.equals(realRoot) || !realInstance.startsWith(realRoot)) {
      throw new IOException("Btrfs instance resolves outside " + realRoot);
    }
    return instance;
  }

  private Path checkedSource(Path value) throws IOException {
    Path source = value.toAbsolutePath().normalize();
    if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("Btrfs snapshot source is missing or unsafe: " + source);
    }
    Path realContent = contentRoot.toRealPath();
    Path realInstances = instancesRoot.toRealPath();
    Path realSource = source.toRealPath();
    if (realSource.equals(realContent)
        || !realSource.startsWith(realContent)
        || realSource.startsWith(realInstances)) {
      throw new IOException("Btrfs snapshot source must stay in managed content: " + source);
    }
    return realSource;
  }

  private static Path checkedTarget(Path instance, Path relative) throws IOException {
    if (relative.isAbsolute() || relative.toString().isBlank()) {
      throw new IOException("Btrfs snapshot target must be a relative instance path");
    }
    Path target = instance.resolve(relative).normalize();
    if (target.equals(instance) || !target.startsWith(instance)) {
      throw new IOException("Btrfs snapshot target escapes the instance: " + relative);
    }
    Path current = instance;
    for (Path segment : instance.relativize(target)) {
      current = current.resolve(segment);
      if (Files.isSymbolicLink(current)) {
        throw new IOException("Btrfs snapshot paths must not contain symbolic links: " + current);
      }
    }
    return target;
  }

  private static void rejectOverlappingTarget(List<Path> existing, Path target) throws IOException {
    for (Path previous : existing) {
      if (target.startsWith(previous) || previous.startsWith(target)) {
        throw new IOException("Btrfs snapshot targets overlap: " + previous + " and " + target);
      }
    }
  }

  private static int boundedCount(String value) throws IOException {
    try {
      int count = Integer.parseInt(value);
      if (count < 0 || count > MAX_SUBVOLUMES) {
        throw new NumberFormatException();
      }
      return count;
    } catch (NumberFormatException exception) {
      throw new IOException("Invalid Btrfs manifest subvolume count");
    }
  }

  private static String portableRelative(Path path) {
    String value = path.toString();
    return java.io.File.separatorChar == '\\' ? value.replace('\\', '/') : value;
  }

  static final class IneligibleSourceException extends IOException {

    IneligibleSourceException(String message) {
      super(message);
    }
  }

  interface SubvolumeAdapter {

    boolean isSubvolume(Path path) throws IOException;

    default boolean hasNestedSubvolumes(Path path) throws IOException {
      return false;
    }

    void snapshot(Path source, Path target) throws IOException;

    void delete(Path path) throws IOException;
  }

  private static final class CommandSubvolumeAdapter implements SubvolumeAdapter {

    private static final long COMMAND_TIMEOUT_SECONDS = 30;

    @Override
    public boolean isSubvolume(Path path) throws IOException {
      if (!Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
        return false;
      }
      if (!"btrfs".equalsIgnoreCase(Files.getFileStore(path).type())) {
        return false;
      }
      return hasSubvolumeRootInode(path);
    }

    private static boolean hasSubvolumeRootInode(Path path) throws IOException {
      try {
        Object inode = Files.getAttribute(path, "unix:ino", LinkOption.NOFOLLOW_LINKS);
        return inode instanceof Number number && number.longValue() == 256L;
      } catch (UnsupportedOperationException exception) {
        return false;
      }
    }

    @Override
    public boolean hasNestedSubvolumes(Path path) throws IOException {
      final boolean[] nested = {false};
      Files.walkFileTree(
          path,
          new java.nio.file.SimpleFileVisitor<>() {
            @Override
            public java.nio.file.FileVisitResult preVisitDirectory(
                Path directory, java.nio.file.attribute.BasicFileAttributes attributes)
                throws IOException {
              if (!directory.equals(path) && hasSubvolumeRootInode(directory)) {
                nested[0] = true;
                return java.nio.file.FileVisitResult.TERMINATE;
              }
              return java.nio.file.FileVisitResult.CONTINUE;
            }
          });
      return nested[0];
    }

    @Override
    public void snapshot(Path source, Path target) throws IOException {
      requireSuccess(
          List.of("btrfs", "subvolume", "snapshot", source.toString(), target.toString()));
    }

    @Override
    public void delete(Path path) throws IOException {
      requireSuccess(List.of("btrfs", "subvolume", "delete", path.toString()));
    }

    private static void requireSuccess(List<String> command) throws IOException {
      int result = execute(command);
      if (result != 0) {
        throw new IOException(
            String.join(" ", command.subList(0, 3)) + " exited with code " + result);
      }
    }

    private static int execute(List<String> command) throws IOException {
      Process process = null;
      try {
        process =
            new ProcessBuilder(command)
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
          process.destroyForcibly();
          throw new IOException("Btrfs command timed out");
        }
        return process.exitValue();
      } catch (InterruptedException exception) {
        Thread.currentThread().interrupt();
        throw new IOException("Btrfs command was interrupted", exception);
      } finally {
        if (process != null && process.isAlive()) {
          process.destroyForcibly();
        }
      }
    }
  }
}
