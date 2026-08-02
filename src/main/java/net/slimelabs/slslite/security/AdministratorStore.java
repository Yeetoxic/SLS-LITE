package net.slimelabs.slslite.security;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;
import net.slimelabs.slslite.io.BoundedFileReader;
import net.slimelabs.slslite.io.ConfinedFiles;

public final class AdministratorStore {

  static final long MAX_STORE_BYTES = 1024 * 1024;
  static final String FILE_NAME = "administrators.properties";
  private static final String SCHEMA_KEY = "schema";
  private static final String SCHEMA_VERSION = "1";
  private static final String ADMIN_PREFIX = "administrator.";

  private final Path dataDirectory;
  private final Path storePath;
  private final StoreWriter writer;
  private final Properties values = new Properties();

  public AdministratorStore(Path dataDirectory) {
    this(dataDirectory, AdministratorStore::atomicWrite);
  }

  AdministratorStore(Path dataDirectory, StoreWriter writer) {
    this.dataDirectory = dataDirectory.toAbsolutePath().normalize();
    this.storePath = this.dataDirectory.resolve(FILE_NAME);
    this.writer = java.util.Objects.requireNonNull(writer, "writer");
  }

  public synchronized void initialize() throws IOException {
    ConfinedFiles.ensureDirectory(dataDirectory);
    if (!Files.exists(storePath, LinkOption.NOFOLLOW_LINKS)) {
      Properties initial = new Properties();
      initial.setProperty(SCHEMA_KEY, SCHEMA_VERSION);
      persist(initial);
      return;
    }
    if (!Files.isRegularFile(storePath, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("Administrator store is not a regular file: " + storePath);
    }
    if (Files.size(storePath) > MAX_STORE_BYTES) {
      throw new IOException(
          "Administrator store exceeds " + MAX_STORE_BYTES + " bytes: " + storePath);
    }
    byte[] encoded;
    try (InputStream input =
        BoundedFileReader.openNoFollow(storePath, Math.toIntExact(MAX_STORE_BYTES))) {
      encoded = input.readAllBytes();
    }
    Properties loaded = new Properties();
    try (InputStream input = new ByteArrayInputStream(encoded)) {
      loaded.load(input);
    }
    if (!SCHEMA_VERSION.equals(loaded.getProperty(SCHEMA_KEY))) {
      throw new IOException("Unsupported administrator store schema in " + storePath);
    }
    for (String key : loaded.stringPropertyNames()) {
      if (!SCHEMA_KEY.equals(key)) {
        parseAdministrator(key, loaded.getProperty(key));
      }
    }
    replaceValues(loaded);
  }

  public synchronized boolean isEmpty() {
    return values.stringPropertyNames().stream().noneMatch(this::isAdministratorKey);
  }

  public synchronized boolean contains(UUID uniqueId) {
    return values.containsKey(key(uniqueId));
  }

  public synchronized void add(UUID uniqueId, String username) throws IOException {
    Properties updated = copyValues();
    updated.setProperty(key(uniqueId), username);
    persist(updated);
  }

  public synchronized Optional<Administrator> remove(String usernameOrUuid) throws IOException {
    Optional<Administrator> administrator = find(usernameOrUuid);
    if (administrator.isEmpty()) {
      return Optional.empty();
    }
    Properties updated = copyValues();
    updated.remove(key(administrator.get().uniqueId()));
    persist(updated);
    return administrator;
  }

  public synchronized Optional<Administrator> find(String usernameOrUuid) {
    UUID requestedId = parseUuid(usernameOrUuid);
    return list().stream()
        .filter(
            administrator ->
                requestedId != null
                    ? administrator.uniqueId().equals(requestedId)
                    : administrator.lastKnownName().equalsIgnoreCase(usernameOrUuid))
        .findFirst();
  }

  public synchronized List<Administrator> list() {
    return values.stringPropertyNames().stream()
        .filter(this::isAdministratorKey)
        .map(key -> parseAdministrator(key, values.getProperty(key)))
        .sorted(Comparator.comparing(Administrator::lastKnownName, String.CASE_INSENSITIVE_ORDER))
        .toList();
  }

  private void persist(Properties updated) throws IOException {
    writer.write(dataDirectory, storePath, updated);
    replaceValues(updated);
  }

  private Properties copyValues() {
    Properties copied = new Properties();
    copied.putAll(values);
    return copied;
  }

  private void replaceValues(Properties updated) {
    values.clear();
    values.putAll(updated);
  }

  private static void atomicWrite(Path dataDirectory, Path storePath, Properties updated)
      throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (output) {
      updated.store(output, "Managed by SLS-LITE");
    }
    if (!dataDirectory.resolve(FILE_NAME).normalize().equals(storePath.normalize())) {
      throw new IOException("Administrator store target changed unexpectedly");
    }
    ConfinedFiles.atomicWrite(
        dataDirectory, FILE_NAME, output.toByteArray(), Math.toIntExact(MAX_STORE_BYTES));
  }

  @FunctionalInterface
  interface StoreWriter {
    void write(Path dataDirectory, Path storePath, Properties values) throws IOException;
  }

  private Administrator parseAdministrator(String property, String username) {
    if (!isAdministratorKey(property)) {
      throw new IllegalArgumentException("Unknown administrator property: " + property);
    }
    try {
      return new Administrator(
          UUID.fromString(property.substring(ADMIN_PREFIX.length())), username);
    } catch (IllegalArgumentException exception) {
      throw new IllegalArgumentException("Invalid administrator entry " + property, exception);
    }
  }

  private boolean isAdministratorKey(String property) {
    return property.startsWith(ADMIN_PREFIX);
  }

  private static String key(UUID uniqueId) {
    return ADMIN_PREFIX + uniqueId;
  }

  private static UUID parseUuid(String value) {
    try {
      return UUID.fromString(value);
    } catch (IllegalArgumentException ignored) {
      return null;
    }
  }
}
