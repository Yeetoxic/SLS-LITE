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

final class SnapshotHookLayerManager {

  static final String MANIFEST_FILE = ".sls-lite-snapshot-hook.properties";
  private static final String MANIFEST_VERSION = "1";
  private static final int MAX_LAYERS = 256;
  private static final long MAX_MANIFEST_BYTES = 1_048_576;

  private final Path instancesRoot;
  private final Path contentRoot;
  private final HookAdapter hook;

  SnapshotHookLayerManager(Path instancesRoot, Path contentRoot, SnapshotHookClient client) {
    this(instancesRoot, contentRoot, new ClientHookAdapter(client));
  }

  SnapshotHookLayerManager(Path instancesRoot, Path contentRoot, HookAdapter hook) {
    this.instancesRoot = instancesRoot.toAbsolutePath().normalize();
    this.contentRoot = contentRoot.toAbsolutePath().normalize();
    this.hook = java.util.Objects.requireNonNull(hook, "hook");
  }

  boolean hasManifest(Path instance) {
    return manifestExists(instance);
  }

  static boolean manifestExists(Path instance) {
    return Files.exists(instance.resolve(MANIFEST_FILE), LinkOption.NOFOLLOW_LINKS);
  }

  void prepare(Path instanceValue, Path sourceValue, Path relativeTarget) throws IOException {
    Path instance = checkedInstance(instanceValue);
    Path source = checkedSource(sourceValue);
    Path target = checkedTarget(instance, relativeTarget);
    if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("Snapshot-hook target already exists: " + target);
    }
    List<Layer> layers = new ArrayList<>(readManifest(instance));
    rejectOverlap(layers, target);
    Layer layer = new Layer(source, target);
    layers.add(layer);
    Files.createDirectories(target.getParent());
    writeManifest(instance, layers);
    try {
      hook.prepare(source, target);
      if (!Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
        throw new IOException("Snapshot helper did not materialize target: " + target);
      }
    } catch (IOException | RuntimeException exception) {
      try {
        hook.delete(source, target);
      } catch (IOException cleanupFailure) {
        exception.addSuppressed(cleanupFailure);
      }
      if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
        layers.remove(layer);
        try {
          writeOrDeleteManifest(instance, layers);
        } catch (IOException cleanupFailure) {
          exception.addSuppressed(cleanupFailure);
        }
      }
      throw exception;
    }
  }

  void suspend(Path instanceValue) throws IOException {
    Path instance = checkedInstance(instanceValue);
    List<Layer> layers = readManifest(instance);
    IOException failure = null;
    for (int index = layers.size() - 1; index >= 0; index--) {
      Layer layer = layers.get(index);
      try {
        hook.suspend(layer.source(), layer.target());
        assertNoMounts(layer.target());
      } catch (IOException exception) {
        failure = combine(failure, exception);
      }
    }
    if (failure != null) {
      throw failure;
    }
  }

  void resume(Path instanceValue) throws IOException {
    Path instance = checkedInstance(instanceValue);
    List<Layer> resumed = new ArrayList<>();
    try {
      for (Layer layer : readManifest(instance)) {
        hook.resume(layer.source(), layer.target());
        if (!Files.isDirectory(layer.target(), LinkOption.NOFOLLOW_LINKS)) {
          throw new IOException("Snapshot helper did not resume target: " + layer.target());
        }
        resumed.add(layer);
      }
    } catch (IOException | RuntimeException exception) {
      for (int index = resumed.size() - 1; index >= 0; index--) {
        Layer layer = resumed.get(index);
        try {
          hook.suspend(layer.source(), layer.target());
        } catch (IOException cleanupFailure) {
          exception.addSuppressed(cleanupFailure);
        }
      }
      throw exception;
    }
  }

  void delete(Path instanceValue) throws IOException {
    Path instance = checkedInstance(instanceValue);
    List<Layer> layers = new ArrayList<>(readManifest(instance));
    layers.sort(Comparator.comparingInt(layer -> -layer.target().getNameCount()));
    IOException failure = null;
    for (Layer layer : layers) {
      try {
        hook.delete(layer.source(), layer.target());
        assertNoMounts(layer.target());
        if (Files.exists(layer.target(), LinkOption.NOFOLLOW_LINKS)) {
          throw new IOException(
              "Snapshot helper delete left its target present: " + layer.target());
        }
      } catch (IOException exception) {
        failure = combine(failure, exception);
      }
    }
    if (failure != null) {
      throw failure;
    }
    Files.delete(instance.resolve(MANIFEST_FILE));
  }

  private void assertNoMounts(Path target) throws IOException {
    List<Path> mounts = new OverlayFsMountOperations().mountPointsBeneath(target);
    if (!mounts.isEmpty()) {
      throw new IOException(
          "Snapshot helper left managed mounts beneath " + target + ": " + mounts);
    }
  }

  private List<Layer> readManifest(Path instance) throws IOException {
    Path manifest = instance.resolve(MANIFEST_FILE);
    if (!Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)) {
      return List.of();
    }
    if (!Files.isRegularFile(manifest, LinkOption.NOFOLLOW_LINKS)
        || Files.size(manifest) > MAX_MANIFEST_BYTES) {
      throw new IOException("Snapshot-hook manifest is unsafe: " + manifest);
    }
    Properties values = new Properties();
    try (InputStream input = Files.newInputStream(manifest)) {
      values.load(input);
    }
    if (!MANIFEST_VERSION.equals(values.getProperty("version"))) {
      throw new IOException("Unsupported snapshot-hook manifest: " + manifest);
    }
    int count = count(values.getProperty("layers"));
    if (count == 0) {
      throw new IOException("Snapshot-hook manifest declares no layers");
    }
    List<Layer> layers = new ArrayList<>();
    for (int index = 0; index < count; index++) {
      String source = required(values, "layer." + index + ".source");
      String target = required(values, "layer." + index + ".target");
      Path checkedTarget = checkedTarget(instance, Path.of(target));
      rejectOverlap(layers, checkedTarget);
      layers.add(new Layer(checkedSource(Path.of(source)), checkedTarget));
    }
    return List.copyOf(layers);
  }

  private void writeOrDeleteManifest(Path instance, List<Layer> layers) throws IOException {
    if (layers.isEmpty()) {
      Files.deleteIfExists(instance.resolve(MANIFEST_FILE));
    } else {
      writeManifest(instance, layers);
    }
  }

  private void writeManifest(Path instance, List<Layer> layers) throws IOException {
    Properties values = new Properties();
    values.setProperty("version", MANIFEST_VERSION);
    values.setProperty("layers", Integer.toString(layers.size()));
    for (int index = 0; index < layers.size(); index++) {
      Layer layer = layers.get(index);
      values.setProperty("layer." + index + ".source", layer.source().toString());
      values.setProperty(
          "layer." + index + ".target", portable(instance.relativize(layer.target())));
    }
    Path manifest = instance.resolve(MANIFEST_FILE);
    Path temporary = instance.resolve(MANIFEST_FILE + ".tmp");
    try (OutputStream output = Files.newOutputStream(temporary)) {
      values.store(output, "SLS-LITE snapshot helper manifest");
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
      throw new IOException("Snapshot-hook instance is missing or outside " + instancesRoot);
    }
    Path realRoot = instancesRoot.toRealPath();
    Path realInstance = instance.toRealPath();
    if (realInstance.equals(realRoot) || !realInstance.startsWith(realRoot)) {
      throw new IOException("Snapshot-hook instance resolves outside " + realRoot);
    }
    return instance;
  }

  private Path checkedSource(Path value) throws IOException {
    Path source = value.toAbsolutePath().normalize();
    if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("Snapshot-hook source is missing: " + source);
    }
    Path realContent = contentRoot.toRealPath();
    Path realInstances = instancesRoot.toRealPath();
    Path realSource = source.toRealPath();
    if (realSource.equals(realContent)
        || !realSource.startsWith(realContent)
        || realSource.startsWith(realInstances)) {
      throw new IOException("Snapshot-hook source must stay in managed content: " + source);
    }
    return realSource;
  }

  private static Path checkedTarget(Path instance, Path relative) throws IOException {
    if (relative.isAbsolute() || relative.toString().isBlank()) {
      throw new IOException("Snapshot-hook target must be relative");
    }
    Path target = instance.resolve(relative).normalize();
    if (target.equals(instance) || !target.startsWith(instance)) {
      throw new IOException("Snapshot-hook target escapes its instance");
    }
    Path current = instance;
    for (Path segment : instance.relativize(target)) {
      current = current.resolve(segment);
      if (Files.isSymbolicLink(current)) {
        throw new IOException("Snapshot-hook paths must not contain symbolic links");
      }
    }
    return target;
  }

  private static void rejectOverlap(List<Layer> layers, Path target) throws IOException {
    for (Layer layer : layers) {
      if (target.startsWith(layer.target()) || layer.target().startsWith(target)) {
        throw new IOException(
            "Snapshot-hook targets overlap: " + layer.target() + " and " + target);
      }
    }
  }

  private static int count(String value) throws IOException {
    try {
      int count = Integer.parseInt(value);
      if (count < 0 || count > MAX_LAYERS) {
        throw new NumberFormatException();
      }
      return count;
    } catch (NumberFormatException exception) {
      throw new IOException("Invalid snapshot-hook manifest layer count");
    }
  }

  private static String required(Properties values, String key) throws IOException {
    String value = values.getProperty(key);
    if (value == null || value.isBlank()) {
      throw new IOException("Missing snapshot-hook manifest value " + key);
    }
    return value;
  }

  private static String portable(Path value) {
    return java.io.File.separatorChar == '\\'
        ? value.toString().replace('\\', '/')
        : value.toString();
  }

  private static IOException combine(IOException existing, IOException next) {
    if (existing == null) {
      return next;
    }
    existing.addSuppressed(next);
    return existing;
  }

  private record Layer(Path source, Path target) {}

  interface HookAdapter {

    void prepare(Path source, Path target) throws IOException;

    void suspend(Path source, Path target) throws IOException;

    void resume(Path source, Path target) throws IOException;

    void delete(Path source, Path target) throws IOException;
  }

  private record ClientHookAdapter(SnapshotHookClient client) implements HookAdapter {

    @Override
    public void prepare(Path source, Path target) throws IOException {
      client.prepare(source, target);
    }

    @Override
    public void suspend(Path source, Path target) throws IOException {
      client.suspend(source, target);
    }

    @Override
    public void resume(Path source, Path target) throws IOException {
      client.resume(source, target);
    }

    @Override
    public void delete(Path source, Path target) throws IOException {
      client.delete(source, target);
    }
  }
}
