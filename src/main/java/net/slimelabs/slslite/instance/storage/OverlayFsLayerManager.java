package net.slimelabs.slslite.instance.storage;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Properties;
import net.slimelabs.slslite.io.BoundedFileReader;
import net.slimelabs.slslite.io.ConfinedFiles;

final class OverlayFsLayerManager {

  static final String MANIFEST_FILE = ".sls-lite-overlay.properties";
  static final String LAYERS_DIRECTORY = ".sls-lite-overlay-layers";
  private static final String MANIFEST_VERSION = "2";
  private static final String LEGACY_MANIFEST_VERSION = "1";
  private static final long MAX_MANIFEST_BYTES = 1_048_576;
  private static final int MAX_LAYERS = 256;
  private static final int MAX_LOWERS_PER_LAYER = 256;

  private final Path instancesRoot;
  private final Path contentRoot;
  private final MountAdapter mounts;

  OverlayFsLayerManager(Path instancesRoot, Path contentRoot) {
    this(instancesRoot, contentRoot, new CommandMountAdapter(new OverlayFsMountOperations()));
  }

  OverlayFsLayerManager(Path instancesRoot, Path contentRoot, MountAdapter mounts) {
    this.instancesRoot = instancesRoot.toAbsolutePath().normalize();
    this.contentRoot = contentRoot.toAbsolutePath().normalize();
    this.mounts = java.util.Objects.requireNonNull(mounts, "mounts");
  }

  void prepare(Path instanceDirectory, List<Layer> layers) throws IOException {
    Path instance = checkedInstance(instanceDirectory);
    if (layers.isEmpty()) {
      return;
    }
    Path manifest = instance.resolve(MANIFEST_FILE);
    Path layerRoot = instance.resolve(LAYERS_DIRECTORY);
    if (Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)
        || Files.exists(layerRoot, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException(
          "OverlayFS metadata collides with existing instance content: " + instance);
    }

    List<ResolvedLayer> resolved = resolveNewLayers(instance, layers);
    Files.createDirectory(layerRoot);
    for (ResolvedLayer layer : resolved) {
      Files.createDirectory(layer.layerDirectory());
      Files.createDirectory(layer.upperDirectory());
      Files.createDirectory(layer.workDirectory());
      Files.createDirectory(layer.target());
    }
    writeManifest(instance, resolved);
    try {
      mountAll(resolved);
    } catch (IOException | RuntimeException exception) {
      try {
        unmountAll(resolved);
      } catch (IOException cleanupFailure) {
        exception.addSuppressed(cleanupFailure);
      }
      throw exception;
    }
  }

  void resume(Path instanceDirectory) throws IOException {
    List<ResolvedLayer> layers = readManifest(checkedInstance(instanceDirectory));
    mountAll(layers);
  }

  void suspend(Path instanceDirectory) throws IOException {
    List<ResolvedLayer> layers = readManifest(checkedInstance(instanceDirectory));
    unmountAll(layers);
  }

  boolean hasManifest(Path instanceDirectory) {
    Path instance = instanceDirectory.toAbsolutePath().normalize();
    return instance.startsWith(instancesRoot)
        && Files.isRegularFile(instance.resolve(MANIFEST_FILE), LinkOption.NOFOLLOW_LINKS);
  }

  void assertNoMountsBeneath(Path instanceDirectory) throws IOException {
    Path instance = checkedInstance(instanceDirectory);
    List<Path> remaining = mounts.mountPointsBeneath(instance);
    if (!remaining.isEmpty()) {
      throw new IOException(
          "Refusing to traverse instance while mount remains at " + remaining.get(0));
    }
  }

  private List<ResolvedLayer> resolveNewLayers(Path instance, List<Layer> layers)
      throws IOException {
    List<ResolvedLayer> resolved = new ArrayList<>();
    List<Path> targets = new ArrayList<>();
    for (int index = 0; index < layers.size(); index++) {
      Layer layer = layers.get(index);
      Path target = checkedTarget(instance, layer.target());
      for (Path previous : targets) {
        if (target.startsWith(previous) || previous.startsWith(target)) {
          throw new IOException("OverlayFS targets overlap: " + previous + " and " + target);
        }
      }
      targets.add(target);
      if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
        throw new IOException("OverlayFS target collides with existing content: " + target);
      }
      List<Path> lowers = new ArrayList<>();
      for (Path lower : layer.lowerDirectories()) {
        lowers.add(checkedLower(lower));
      }
      Path layerDirectory = instance.resolve(LAYERS_DIRECTORY).resolve(Integer.toString(index));
      resolved.add(
          new ResolvedLayer(
              target,
              List.copyOf(lowers),
              layerDirectory,
              layerDirectory.resolve("upper"),
              layerDirectory.resolve("work")));
    }
    return List.copyOf(resolved);
  }

  private List<ResolvedLayer> readManifest(Path instance) throws IOException {
    Path manifest = instance.resolve(MANIFEST_FILE);
    if (!Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)) {
      return List.of();
    }
    if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("OverlayFS manifest is not a regular file: " + manifest);
    }
    if (Files.size(manifest) > MAX_MANIFEST_BYTES) {
      throw new IOException("OverlayFS manifest is too large: " + manifest);
    }
    Properties properties = new Properties();
    try (InputStream input =
        BoundedFileReader.openNoFollow(manifest, Math.toIntExact(MAX_MANIFEST_BYTES))) {
      properties.load(input);
    }
    String version = properties.getProperty("version");
    if (!MANIFEST_VERSION.equals(version) && !LEGACY_MANIFEST_VERSION.equals(version)) {
      throw new IOException("Unsupported OverlayFS manifest version: " + manifest);
    }
    String recordedAdapter =
        LEGACY_MANIFEST_VERSION.equals(version) ? "overlay" : required(properties, "mount-adapter");
    if (!mounts.storageType().equals(recordedAdapter)) {
      throw new IOException(
          "OverlayFS instance requires mount adapter "
              + recordedAdapter
              + " but "
              + mounts.storageType()
              + " is configured");
    }
    int count = boundedInteger(properties, "layers", MAX_LAYERS);
    if (count == 0) {
      throw new IOException("OverlayFS manifest has no layers: " + manifest);
    }
    Path layerRoot = instance.resolve(LAYERS_DIRECTORY);
    if (!Files.isDirectory(layerRoot, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("OverlayFS layer root is missing or unsafe: " + layerRoot);
    }
    List<ResolvedLayer> resolved = new ArrayList<>();
    List<Path> targets = new ArrayList<>();
    for (int index = 0; index < count; index++) {
      String prefix = "layer." + index + ".";
      String targetValue = required(properties, prefix + "target");
      Path target = checkedTarget(instance, Path.of(targetValue));
      rejectOverlappingTarget(targets, target);
      targets.add(target);
      int lowerCount = boundedInteger(properties, prefix + "lowers", MAX_LOWERS_PER_LAYER);
      if (lowerCount == 0) {
        throw new IOException("OverlayFS layer has no lower directories");
      }
      List<Path> lowers = new ArrayList<>();
      for (int lower = 0; lower < lowerCount; lower++) {
        lowers.add(checkedLower(Path.of(required(properties, prefix + "lower." + lower))));
      }
      Path layerDirectory =
          instance.resolve(LAYERS_DIRECTORY).resolve(Integer.toString(index)).normalize();
      Path upper = checkedLayerDirectory(instance, layerDirectory.resolve("upper"));
      Path work = checkedLayerDirectory(instance, layerDirectory.resolve("work"));
      if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)
          || !Files.isDirectory(upper, LinkOption.NOFOLLOW_LINKS)
          || !Files.isDirectory(work, LinkOption.NOFOLLOW_LINKS)) {
        throw new IOException("OverlayFS manifest references missing layer directories");
      }
      resolved.add(new ResolvedLayer(target, List.copyOf(lowers), layerDirectory, upper, work));
    }
    return List.copyOf(resolved);
  }

  private void mountAll(List<ResolvedLayer> layers) throws IOException {
    List<ResolvedLayer> mountedHere = new ArrayList<>();
    try {
      for (ResolvedLayer layer : layers) {
        if (mounts.isMounted(layer.target())) {
          throw new IOException(
              "OverlayFS target is unexpectedly already mounted: " + layer.target());
        }
        // A helper can mount successfully and then fail while checking
        // the result, so include the attempt in rollback beforehand.
        mountedHere.add(layer);
        mounts.mount(
            layer.lowerDirectories(),
            layer.upperDirectory(),
            layer.workDirectory(),
            layer.target());
      }
    } catch (IOException | RuntimeException exception) {
      try {
        unmountAll(mountedHere);
      } catch (IOException cleanupFailure) {
        exception.addSuppressed(cleanupFailure);
      }
      throw exception;
    }
  }

  private void unmountAll(List<ResolvedLayer> layers) throws IOException {
    IOException failure = null;
    for (int index = layers.size() - 1; index >= 0; index--) {
      ResolvedLayer layer = layers.get(index);
      try {
        mounts.unmount(layer.target(), layer.upperDirectory(), layer.workDirectory());
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
  }

  private void writeManifest(Path instance, List<ResolvedLayer> layers) throws IOException {
    Properties properties = new Properties();
    properties.setProperty("version", MANIFEST_VERSION);
    properties.setProperty("mount-adapter", mounts.storageType());
    properties.setProperty("layers", Integer.toString(layers.size()));
    for (int index = 0; index < layers.size(); index++) {
      ResolvedLayer layer = layers.get(index);
      String prefix = "layer." + index + ".";
      properties.setProperty(
          prefix + "target", portableRelative(instance.relativize(layer.target())));
      properties.setProperty(prefix + "lowers", Integer.toString(layer.lowerDirectories().size()));
      for (int lower = 0; lower < layer.lowerDirectories().size(); lower++) {
        properties.setProperty(
            prefix + "lower." + lower, layer.lowerDirectories().get(lower).toString());
      }
    }
    ConfinedFiles.atomicWriteProperties(
        instance,
        MANIFEST_FILE,
        properties,
        "SLS-LITE OverlayFS manifest",
        Math.toIntExact(MAX_MANIFEST_BYTES));
  }

  private Path checkedInstance(Path value) throws IOException {
    Path instance = value.toAbsolutePath().normalize();
    if (instance.equals(instancesRoot) || !instance.startsWith(instancesRoot)) {
      throw new IOException("OverlayFS instance must stay below " + instancesRoot);
    }
    if (!Files.isDirectory(instance, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("OverlayFS instance is missing or unsafe: " + instance);
    }
    Path realRoot = instancesRoot.toRealPath();
    Path realInstance = instance.toRealPath();
    if (realInstance.equals(realRoot) || !realInstance.startsWith(realRoot)) {
      throw new IOException("OverlayFS instance resolves outside " + realRoot);
    }
    return instance;
  }

  private Path checkedTarget(Path instance, Path relative) throws IOException {
    if (relative.isAbsolute() || relative.toString().isBlank()) {
      throw new IOException("OverlayFS target must be a relative instance path");
    }
    Path target = instance.resolve(relative).normalize();
    if (target.equals(instance)
        || !target.startsWith(instance)
        || target.startsWith(instance.resolve(LAYERS_DIRECTORY))) {
      throw new IOException("OverlayFS target escapes the instance: " + relative);
    }
    rejectSymbolicSegments(instance, target);
    return target;
  }

  private Path checkedLower(Path value) throws IOException {
    Path lower = value.toAbsolutePath().normalize();
    if (!Files.isDirectory(lower, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("OverlayFS lower directory does not exist: " + lower);
    }
    Path realContent = contentRoot.toRealPath();
    Path realInstances = instancesRoot.toRealPath();
    Path realLower = lower.toRealPath();
    if (realLower.equals(realContent)
        || !realLower.startsWith(realContent)
        || realLower.startsWith(realInstances)) {
      throw new IOException("OverlayFS lower directory must stay in managed content: " + lower);
    }
    return realLower;
  }

  private static Path checkedLayerDirectory(Path instance, Path value) throws IOException {
    Path layerRoot = instance.resolve(LAYERS_DIRECTORY).normalize();
    Path directory = value.normalize();
    if (!directory.startsWith(layerRoot) || directory.equals(layerRoot)) {
      throw new IOException("OverlayFS layer directory escapes managed metadata");
    }
    rejectSymbolicSegments(instance, directory);
    if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("OverlayFS layer directory is missing or unsafe: " + directory);
    }
    Path realLayerRoot = layerRoot.toRealPath();
    Path realDirectory = directory.toRealPath();
    if (realDirectory.equals(realLayerRoot) || !realDirectory.startsWith(realLayerRoot)) {
      throw new IOException("OverlayFS layer directory resolves outside managed metadata");
    }
    return directory;
  }

  private static void rejectOverlappingTarget(List<Path> existing, Path target) throws IOException {
    for (Path previous : existing) {
      if (target.startsWith(previous) || previous.startsWith(target)) {
        throw new IOException("OverlayFS targets overlap: " + previous + " and " + target);
      }
    }
  }

  private static void rejectSymbolicSegments(Path root, Path target) throws IOException {
    Path current = root;
    for (Path segment : root.relativize(target)) {
      current = current.resolve(segment);
      if (Files.isSymbolicLink(current)) {
        throw new IOException("OverlayFS paths must not contain symbolic links: " + current);
      }
    }
  }

  private static int boundedInteger(Properties properties, String key, int maximum)
      throws IOException {
    String value = required(properties, key);
    try {
      int parsed = Integer.parseInt(value);
      if (parsed < 0 || parsed > maximum) {
        throw new NumberFormatException();
      }
      return parsed;
    } catch (NumberFormatException exception) {
      throw new IOException("Invalid OverlayFS manifest integer " + key);
    }
  }

  private static String required(Properties properties, String key) throws IOException {
    String value = properties.getProperty(key);
    if (value == null || value.isBlank()) {
      throw new IOException("Missing OverlayFS manifest value " + key);
    }
    return value;
  }

  private static String portableRelative(Path path) {
    String value = path.toString();
    return java.io.File.separatorChar == '\\' ? value.replace('\\', '/') : value;
  }

  record Layer(Path target, List<Path> lowerDirectories) {

    Layer {
      java.util.Objects.requireNonNull(target, "target");
      java.util.Objects.requireNonNull(lowerDirectories, "lowerDirectories");
      lowerDirectories = List.copyOf(lowerDirectories);
      if (lowerDirectories.isEmpty()) {
        throw new IllegalArgumentException("OverlayFS layer requires at least one lower directory");
      }
    }
  }

  private record ResolvedLayer(
      Path target,
      List<Path> lowerDirectories,
      Path layerDirectory,
      Path upperDirectory,
      Path workDirectory) {}

  interface MountAdapter {

    default String storageType() {
      return "overlay";
    }

    void mount(List<Path> lowerDirectories, Path upperDirectory, Path workDirectory, Path target)
        throws IOException;

    void unmount(Path target, Path upperDirectory, Path workDirectory) throws IOException;

    boolean isMounted(Path target) throws IOException;

    List<Path> mountPointsBeneath(Path root) throws IOException;
  }

  private record CommandMountAdapter(OverlayFsMountOperations operations) implements MountAdapter {

    @Override
    public void mount(
        List<Path> lowerDirectories, Path upperDirectory, Path workDirectory, Path target)
        throws IOException {
      operations.mount(lowerDirectories, upperDirectory, workDirectory, target);
    }

    @Override
    public void unmount(Path target, Path upperDirectory, Path workDirectory) throws IOException {
      operations.unmount(target, upperDirectory, workDirectory);
    }

    @Override
    public boolean isMounted(Path target) throws IOException {
      return operations.isMounted(target);
    }

    @Override
    public List<Path> mountPointsBeneath(Path root) throws IOException {
      return operations.mountPointsBeneath(root);
    }
  }
}
