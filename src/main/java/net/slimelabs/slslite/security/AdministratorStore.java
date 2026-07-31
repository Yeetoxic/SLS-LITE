package net.slimelabs.slslite.security;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

public final class AdministratorStore {

  static final String FILE_NAME = "administrators.properties";
  private static final String TEMP_FILE_NAME = FILE_NAME + ".tmp";
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
    Files.createDirectories(dataDirectory);
    if (!Files.exists(storePath)) {
      Properties initial = new Properties();
      initial.setProperty(SCHEMA_KEY, SCHEMA_VERSION);
      persist(initial);
      return;
    }
    if (!Files.isRegularFile(storePath)) {
      throw new IOException("Administrator store is not a regular file: " + storePath);
    }
    Properties loaded = new Properties();
    try (InputStream input = Files.newInputStream(storePath)) {
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
    Path temporary = dataDirectory.resolve(TEMP_FILE_NAME);
    try (OutputStream output =
        Files.newOutputStream(
            temporary,
            StandardOpenOption.CREATE,
            StandardOpenOption.TRUNCATE_EXISTING,
            StandardOpenOption.WRITE)) {
      updated.store(output, "Managed by SLS-LITE");
    }
    try {
      Files.move(
          temporary,
          storePath,
          StandardCopyOption.ATOMIC_MOVE,
          StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException ignored) {
      Files.move(temporary, storePath, StandardCopyOption.REPLACE_EXISTING);
    } finally {
      Files.deleteIfExists(temporary);
    }
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
