package net.slimelabs.slslite.instance.storage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ConcurrentHashMap;
import net.slimelabs.slslite.blueprint.BlueprintPersistentFile;
import net.slimelabs.slslite.instance.InstancePreparationException;
import net.slimelabs.slslite.io.BoundedFileReader;
import net.slimelabs.slslite.io.ConfinedFiles;

/** Transactional import and single-writer publication for file-shaped persistent state. */
final class PersistentFileStateManager {

  static final String MANIFEST_FILE = ".sls-lite-persistent-files.properties";
  static final int MAX_FILES = 32;
  static final int MAX_FILE_BYTES = 8 * 1024 * 1024;
  static final int MAX_TOTAL_BYTES = 32 * 1024 * 1024;
  private static final int MAX_MANIFEST_BYTES = 64 * 1024;
  private static final String SCHEMA = "1";

  private final Path instancesRoot;
  private final Path contentRoot;
  private final Path persistentFilesRoot;
  private final Path conflictRoot;
  private final Map<Path, String> owners = new ConcurrentHashMap<>();

  PersistentFileStateManager(Path instancesRoot, Path contentRoot) {
    this.instancesRoot = canonicalRoot(instancesRoot);
    this.contentRoot = canonicalRoot(contentRoot);
    this.persistentFilesRoot = this.contentRoot.resolve("volumes").normalize();
    this.conflictRoot = this.contentRoot.resolve("internal/persistent-file-conflicts").normalize();
  }

  private static Path canonicalRoot(Path root) {
    Path normalized = root.toAbsolutePath().normalize();
    try {
      return Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)
          ? normalized.toRealPath()
          : normalized;
    } catch (IOException exception) {
      return normalized;
    }
  }

  void prepare(
      String instanceId,
      Path destination,
      List<BlueprintPersistentFile> configured,
      java.util.function.BooleanSupplier cancellationRequested)
      throws IOException, InstancePreparationException {
    if (configured.isEmpty()) {
      return;
    }
    List<Entry> entries = resolveConfigured(destination, configured);
    acquire(instanceId, entries);
    try {
      int total = 0;
      List<Entry> imported = new ArrayList<>(entries.size());
      for (Entry entry : entries) {
        if (cancellationRequested.getAsBoolean()) {
          throw new IOException("Instance preparation was cancelled");
        }
        byte[] contents = readBounded(entry.source());
        total = addTotal(total, contents.length);
        atomicWriteTarget(entry.target(), contents);
        imported.add(entry.withDigest(digest(contents)));
      }
      writeManifest(destination, imported);
    } catch (IOException | InstancePreparationException exception) {
      release(instanceId);
      throw exception;
    }
  }

  void inspect(Path destination, List<BlueprintPersistentFile> configured)
      throws IOException, InstancePreparationException {
    int total = 0;
    for (Entry entry : resolveConfigured(destination, configured)) {
      long size = Files.size(entry.source());
      if (size > MAX_FILE_BYTES) {
        throw new InstancePreparationException(
            "Persistent file exceeds the " + MAX_FILE_BYTES + "-byte limit: " + entry.name());
      }
      total = addTotal(total, Math.toIntExact(size));
    }
  }

  void resume(String instanceId, Path destination)
      throws IOException, InstancePreparationException {
    List<Entry> entries = readManifest(destination);
    if (entries.isEmpty()) {
      return;
    }
    acquire(instanceId, entries);
    try {
      int total = 0;
      List<Entry> refreshed = new ArrayList<>(entries.size());
      for (Entry entry : entries) {
        byte[] source = readBounded(entry.source());
        byte[] target = readBounded(entry.target());
        total = addTotal(total, Math.max(source.length, target.length));
        String sourceDigest = digest(source);
        String targetDigest = digest(target);
        if (!sourceDigest.equals(entry.digest()) && !targetDigest.equals(entry.digest())) {
          if (!sourceDigest.equals(targetDigest)) {
            preserveConflict(entry, target);
            throw conflict(entry);
          }
          refreshed.add(entry.withDigest(sourceDigest));
          continue;
        }
        if (!sourceDigest.equals(entry.digest())) {
          atomicWriteTarget(entry.target(), source);
          refreshed.add(entry.withDigest(sourceDigest));
        } else {
          refreshed.add(entry);
        }
      }
      writeManifest(destination, refreshed);
    } catch (IOException | InstancePreparationException exception) {
      release(instanceId);
      throw exception;
    }
  }

  void publish(String instanceId, Path destination)
      throws IOException, InstancePreparationException {
    publish(instanceId, destination, false);
  }

  void publish(String instanceId, Path destination, boolean manifestRequired)
      throws IOException, InstancePreparationException {
    List<Entry> entries = readManifest(destination);
    if (entries.isEmpty()) {
      release(instanceId);
      if (manifestRequired) {
        throw new InstancePreparationException(
            "Persistent file manifest is missing for an instance that declares persistent files");
      }
      return;
    }
    try {
      int total = 0;
      List<Entry> published = new ArrayList<>(entries.size());
      for (Entry entry : entries) {
        byte[] source = readBounded(entry.source());
        byte[] target = readBounded(entry.target());
        total = addTotal(total, Math.max(source.length, target.length));
        String sourceDigest = digest(source);
        String targetDigest = digest(target);
        if (!sourceDigest.equals(entry.digest()) && !sourceDigest.equals(targetDigest)) {
          preserveConflict(entry, target);
          throw conflict(entry);
        }
        if (!sourceDigest.equals(targetDigest)) {
          atomicWriteSource(entry.source(), target);
        }
        published.add(entry.withDigest(targetDigest));
      }
      writeManifest(destination, published);
    } finally {
      release(instanceId);
    }
  }

  void release(String instanceId) {
    owners.entrySet().removeIf(entry -> entry.getValue().equals(instanceId));
  }

  private List<Entry> resolveConfigured(Path destination, List<BlueprintPersistentFile> configured)
      throws IOException, InstancePreparationException {
    if (configured.size() > MAX_FILES) {
      throw new InstancePreparationException("Persistent file mapping count exceeds " + MAX_FILES);
    }
    Path realContentRoot = contentRoot.toRealPath();
    List<Entry> entries = new ArrayList<>(configured.size());
    for (BlueprintPersistentFile file : configured) {
      Path source = contentRoot.resolve(file.source()).normalize();
      if (source.equals(persistentFilesRoot)
          || !source.startsWith(persistentFilesRoot)
          || source.startsWith(instancesRoot)
          || source.startsWith(conflictRoot)) {
        throw new InstancePreparationException(
            "Persistent file source must stay below volumes/: " + file.source());
      }
      rejectSymbolicSegments(contentRoot, source, true);
      ConfinedFiles.requireRegularFile(source);
      Path realSource = source.toRealPath();
      if (!realSource.startsWith(realContentRoot)) {
        throw new InstancePreparationException(
            "Persistent file source resolves outside the content root: " + file.source());
      }
      requireNoPendingConflict(realSource);
      Path target = destination.resolve(file.target()).normalize();
      if (target.equals(destination) || !target.startsWith(destination)) {
        throw new InstancePreparationException(
            "Persistent file target must stay inside the instance: " + file.target());
      }
      rejectSymbolicSegments(destination, target, false);
      if (Files.isDirectory(target, LinkOption.NOFOLLOW_LINKS)) {
        throw new InstancePreparationException(
            "Persistent file target is a directory: " + file.target());
      }
      rejectReservedTarget(destination, target);
      entries.add(new Entry(file.name(), realSource, target, ""));
    }
    return List.copyOf(entries);
  }

  private void acquire(String instanceId, List<Entry> entries) throws InstancePreparationException {
    List<Path> acquired = new ArrayList<>();
    for (Entry entry : entries) {
      String existing = owners.putIfAbsent(entry.source(), instanceId);
      if (existing != null && !existing.equals(instanceId)) {
        acquired.forEach(source -> owners.remove(source, instanceId));
        throw new InstancePreparationException(
            "Persistent file '"
                + entry.name()
                + "' is already owned by active instance "
                + existing);
      }
      if (existing == null) {
        acquired.add(entry.source());
      }
    }
  }

  private List<Entry> readManifest(Path destination)
      throws IOException, InstancePreparationException {
    Path manifest = destination.resolve(MANIFEST_FILE).normalize();
    if (!Files.exists(manifest, LinkOption.NOFOLLOW_LINKS)) {
      return List.of();
    }
    ConfinedFiles.requireRegularFile(manifest);
    Properties values = new Properties();
    try (InputStream input = BoundedFileReader.openNoFollow(manifest, MAX_MANIFEST_BYTES)) {
      values.load(input);
    }
    if (!SCHEMA.equals(values.getProperty("schema"))) {
      throw new InstancePreparationException("Unsupported persistent file manifest schema");
    }
    int count;
    try {
      count = Integer.parseInt(values.getProperty("count", "-1"));
    } catch (NumberFormatException exception) {
      throw new InstancePreparationException("Invalid persistent file manifest count", exception);
    }
    if (count < 0 || count > MAX_FILES) {
      throw new InstancePreparationException("Invalid persistent file manifest count: " + count);
    }
    List<Entry> entries = new ArrayList<>(count);
    java.util.HashSet<Path> sources = new java.util.HashSet<>();
    java.util.HashSet<Path> targets = new java.util.HashSet<>();
    for (int index = 0; index < count; index++) {
      String prefix = "file." + index + ".";
      String name = required(values, prefix + "name");
      String sourceText = required(values, prefix + "source");
      String targetText = required(values, prefix + "target");
      String expectedDigest = required(values, prefix + "sha256");
      if (!expectedDigest.matches("[0-9a-f]{64}")) {
        throw new InstancePreparationException("Invalid persistent file manifest digest");
      }
      Path source = contentRoot.resolve(sourceText).normalize();
      Path target = destination.resolve(targetText).normalize();
      if (source.equals(persistentFilesRoot) || !source.startsWith(persistentFilesRoot)) {
        throw new InstancePreparationException("Persistent file manifest source escapes volumes/");
      }
      if (source.startsWith(instancesRoot) || source.startsWith(conflictRoot)) {
        throw new InstancePreparationException(
            "Persistent file manifest source uses reserved managed storage");
      }
      if (target.equals(destination) || !target.startsWith(destination)) {
        throw new InstancePreparationException(
            "Persistent file manifest target escapes the instance");
      }
      rejectReservedTarget(destination, target);
      rejectSymbolicSegments(contentRoot, source, true);
      rejectSymbolicSegments(destination, target, true);
      ConfinedFiles.requireRegularFile(source);
      ConfinedFiles.requireRegularFile(target);
      Path realSource = source.toRealPath();
      requireNoPendingConflict(realSource);
      if (!sources.add(realSource) || !targets.add(target)) {
        throw new InstancePreparationException(
            "Persistent file manifest contains duplicate sources or targets");
      }
      entries.add(new Entry(name, realSource, target, expectedDigest));
    }
    return List.copyOf(entries);
  }

  private void writeManifest(Path destination, List<Entry> entries) throws IOException {
    Properties values = new Properties();
    values.setProperty("schema", SCHEMA);
    values.setProperty("count", Integer.toString(entries.size()));
    for (int index = 0; index < entries.size(); index++) {
      Entry entry = entries.get(index);
      String prefix = "file." + index + ".";
      values.setProperty(prefix + "name", entry.name());
      values.setProperty(
          prefix + "source", contentRoot.relativize(entry.source()).toString().replace('\\', '/'));
      values.setProperty(
          prefix + "target", destination.relativize(entry.target()).toString().replace('\\', '/'));
      values.setProperty(prefix + "sha256", entry.digest());
    }
    ConfinedFiles.atomicWriteProperties(
        destination, MANIFEST_FILE, values, "Managed by SLS-LITE", MAX_MANIFEST_BYTES);
  }

  private void preserveConflict(Entry entry, byte[] target) throws IOException {
    Path directory = recoveryDirectory("persistent-file-conflicts", entry.source());
    Path candidate = directory.resolve("candidate");
    if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
      ConfinedFiles.requireRegularFile(candidate);
      throw new IOException("An unresolved persistent-file candidate already exists: " + candidate);
    }
    ConfinedFiles.atomicWrite(directory, "candidate", target, MAX_FILE_BYTES);
  }

  private Path recoveryDirectory(String category, Path source) throws IOException {
    Path internal = ConfinedFiles.ensureDirectory(contentRoot.resolve("internal"));
    Path categoryDirectory = ConfinedFiles.ensureDirectory(internal.resolve(category));
    return ConfinedFiles.ensureDirectory(categoryDirectory.resolve(recoveryKey(source)));
  }

  private void requireNoPendingConflict(Path source)
      throws IOException, InstancePreparationException {
    Path candidate = conflictRoot.resolve(recoveryKey(source)).resolve("candidate").normalize();
    rejectSymbolicSegments(contentRoot, candidate, false);
    if (Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
      ConfinedFiles.requireRegularFile(candidate);
      throw new InstancePreparationException(
          "Persistent file has an unresolved conflict candidate: "
              + contentRoot.relativize(candidate).toString().replace('\\', '/'));
    }
  }

  private String recoveryKey(Path source) {
    String relative = contentRoot.relativize(source).toString().replace('\\', '/');
    return digest(relative.getBytes(StandardCharsets.UTF_8));
  }

  private static void atomicWriteTarget(Path target, byte[] contents) throws IOException {
    Path parent = ConfinedFiles.ensureDirectory(target.getParent());
    ConfinedFiles.atomicWrite(parent, target.getFileName().toString(), contents, MAX_FILE_BYTES);
  }

  private static void atomicWriteSource(Path source, byte[] contents) throws IOException {
    ConfinedFiles.atomicWrite(
        source.getParent(), source.getFileName().toString(), contents, MAX_FILE_BYTES);
  }

  private static byte[] readBounded(Path file) throws IOException {
    ConfinedFiles.requireRegularFile(file);
    try (InputStream input = BoundedFileReader.openNoFollow(file, MAX_FILE_BYTES);
        ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      input.transferTo(output);
      return output.toByteArray();
    }
  }

  private static int addTotal(int total, int additional) throws InstancePreparationException {
    if (additional < 0 || total > MAX_TOTAL_BYTES - additional) {
      throw new InstancePreparationException(
          "Persistent file state exceeds the " + MAX_TOTAL_BYTES + "-byte aggregate limit");
    }
    return total + additional;
  }

  private static String digest(byte[] contents) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contents));
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static String required(Properties values, String key)
      throws InstancePreparationException {
    String value = values.getProperty(key);
    if (value == null || value.isBlank()) {
      throw new InstancePreparationException("Persistent file manifest is missing " + key);
    }
    return value;
  }

  private static void rejectSymbolicSegments(Path root, Path path, boolean requireLeaf)
      throws IOException, InstancePreparationException {
    Path current = root;
    for (Path segment : root.relativize(path)) {
      current = current.resolve(segment);
      if (!Files.exists(current, LinkOption.NOFOLLOW_LINKS)) {
        if (requireLeaf) {
          throw new InstancePreparationException("Persistent file path does not exist: " + current);
        }
        return;
      }
      if (Files.isSymbolicLink(current)) {
        throw new InstancePreparationException(
            "Persistent file paths must not contain symbolic links: " + current);
      }
    }
  }

  private static void rejectReservedTarget(Path destination, Path target)
      throws InstancePreparationException {
    for (Path segment : destination.relativize(target)) {
      if (segment.toString().toLowerCase(java.util.Locale.ROOT).startsWith(".sls-lite-")) {
        throw new InstancePreparationException(
            "Persistent file target uses a reserved SLS-LITE path: "
                + destination.relativize(target));
      }
    }
  }

  private static InstancePreparationException conflict(Entry entry) {
    return new InstancePreparationException(
        "Persistent file '"
            + entry.name()
            + "' changed both inside its instance and at its canonical source; "
            + "the canonical source was preserved and the instance candidate was backed up");
  }

  private record Entry(String name, Path source, Path target, String digest) {
    private Entry withDigest(String replacement) {
      return new Entry(name, source, target, replacement);
    }
  }
}
