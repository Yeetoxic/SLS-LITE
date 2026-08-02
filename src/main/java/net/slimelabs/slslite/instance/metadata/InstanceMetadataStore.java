package net.slimelabs.slslite.instance.metadata;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Optional;
import java.util.Properties;
import net.slimelabs.slslite.instance.model.InstanceDefinitionIdentity;
import net.slimelabs.slslite.instance.model.InstanceLaunchOverrides;
import net.slimelabs.slslite.instance.model.InstanceMetadata;
import net.slimelabs.slslite.instance.model.InstanceState;
import net.slimelabs.slslite.io.BoundedFileReader;
import net.slimelabs.slslite.io.ConfinedFiles;

public final class InstanceMetadataStore {

  public static final String FILE_NAME = ".sls-lite-instance.properties";
  static final long MAX_METADATA_BYTES = 64 * 1024;
  private static final String LEGACY_SCHEMA_VERSION = "1";
  private static final String PREVIOUS_SCHEMA_VERSION = "2";
  private static final String DEFINITION_SCHEMA_VERSION = "3";
  private static final String SCHEMA_VERSION = "4";

  private final Path instancesRoot;

  public InstanceMetadataStore(Path instancesRoot) {
    this.instancesRoot = instancesRoot.toAbsolutePath().normalize();
  }

  public Optional<InstanceMetadata> read(Path instanceDirectory) throws IOException {
    Path directory = requireDirectChild(instanceDirectory);
    Path metadataPath = directory.resolve(FILE_NAME);
    if (!Files.exists(metadataPath, LinkOption.NOFOLLOW_LINKS)) {
      return Optional.empty();
    }
    if (!Files.isRegularFile(metadataPath, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("Instance metadata is not a regular file: " + metadataPath);
    }
    long size = Files.size(metadataPath);
    if (size > MAX_METADATA_BYTES) {
      throw new IOException(
          "Instance metadata exceeds " + MAX_METADATA_BYTES + " bytes: " + metadataPath);
    }

    byte[] encoded;
    try (InputStream input =
        BoundedFileReader.openNoFollow(metadataPath, Math.toIntExact(MAX_METADATA_BYTES))) {
      encoded = input.readAllBytes();
    }
    Properties values = new Properties();
    try (InputStream input = new ByteArrayInputStream(encoded)) {
      values.load(input);
    }
    return Optional.of(parse(values, metadataPath));
  }

  public void write(Path instanceDirectory, InstanceMetadata metadata) throws IOException {
    Path directory = requireDirectChild(instanceDirectory);
    if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("Instance directory does not exist: " + directory);
    }
    if (Files.isSymbolicLink(directory)) {
      throw new IOException("Instance directory must not be a symbolic link: " + directory);
    }
    if (!directory.getFileName().toString().equals(metadata.instanceId())) {
      throw new IOException("Instance metadata ID does not match directory: " + directory);
    }

    Properties values = new Properties();
    values.setProperty(
        "schema", metadata.definitionIdentity() == null ? LEGACY_SCHEMA_VERSION : SCHEMA_VERSION);
    values.setProperty("instance_id", metadata.instanceId());
    values.setProperty("blueprint_id", metadata.blueprintId());
    if (metadata.definitionIdentity() != null) {
      values.setProperty("software_id", metadata.definitionIdentity().softwareId());
      values.setProperty("software_version", metadata.definitionIdentity().softwareVersion());
      values.setProperty("definition_fingerprint", metadata.definitionIdentity().fingerprint());
    }
    writeOverrides(values, metadata.launchOverrides());
    values.setProperty("persistent", Boolean.toString(metadata.persistent()));
    values.setProperty("state", metadata.state().name());
    values.setProperty("created_at", metadata.createdAt().toString());
    if (metadata.processId() != null) {
      values.setProperty("process_id", Long.toString(metadata.processId()));
      if (metadata.processStartedAt() != null) {
        values.setProperty("process_started_at", metadata.processStartedAt().toString());
      }
    }

    ByteArrayOutputStream output = new ByteArrayOutputStream();
    try (output) {
      values.store(output, "Managed by SLS-LITE");
    }
    ConfinedFiles.atomicWrite(
        directory, FILE_NAME, output.toByteArray(), Math.toIntExact(MAX_METADATA_BYTES));
  }

  private Path requireDirectChild(Path instanceDirectory) throws IOException {
    Path normalized = instanceDirectory.toAbsolutePath().normalize();
    if (!instancesRoot.equals(normalized.getParent())) {
      throw new IOException("Instance directory must be a direct child of " + instancesRoot);
    }
    return normalized;
  }

  private static InstanceMetadata parse(Properties values, Path source) throws IOException {
    try {
      String schema = require(values, "schema", source);
      if (!LEGACY_SCHEMA_VERSION.equals(schema)
          && !PREVIOUS_SCHEMA_VERSION.equals(schema)
          && !DEFINITION_SCHEMA_VERSION.equals(schema)
          && !SCHEMA_VERSION.equals(schema)) {
        throw invalid(source, "unsupported schema " + schema);
      }
      String instanceId = require(values, "instance_id", source);
      String blueprintId = require(values, "blueprint_id", source);
      InstanceDefinitionIdentity identity =
          DEFINITION_SCHEMA_VERSION.equals(schema) || SCHEMA_VERSION.equals(schema)
              ? new InstanceDefinitionIdentity(
                  require(values, "software_id", source),
                  require(values, "software_version", source),
                  require(values, "definition_fingerprint", source))
              : null;
      String persistentText = require(values, "persistent", source);
      if (!"true".equals(persistentText) && !"false".equals(persistentText)) {
        throw invalid(source, "persistent must be true or false");
      }
      InstanceState state = InstanceState.valueOf(require(values, "state", source));
      Instant createdAt = Instant.parse(require(values, "created_at", source));

      String processIdText = values.getProperty("process_id");
      String processStartedText = values.getProperty("process_started_at");
      if (processIdText == null && processStartedText != null) {
        throw invalid(source, "process_started_at requires process_id");
      }
      Long processId = processIdText == null ? null : Long.parseLong(processIdText);
      Instant processStartedAt =
          processStartedText == null ? null : Instant.parse(processStartedText);
      InstanceLaunchOverrides overrides =
          SCHEMA_VERSION.equals(schema) ? readOverrides(values) : InstanceLaunchOverrides.NONE;
      return new InstanceMetadata(
          instanceId,
          blueprintId,
          identity,
          Boolean.parseBoolean(persistentText),
          state,
          createdAt,
          processId,
          processStartedAt,
          overrides);
    } catch (IllegalArgumentException | DateTimeException exception) {
      throw invalid(source, exception.getMessage(), exception);
    }
  }

  private static void writeOverrides(Properties values, InstanceLaunchOverrides overrides) {
    set(values, "override_memory_mib", overrides.memoryLimitMiB());
    set(values, "override_save", overrides.save());
    set(values, "override_seed", overrides.seed());
    set(values, "override_view_distance", overrides.viewDistance());
    set(values, "override_simulation_distance", overrides.simulationDistance());
    set(values, "override_enable_command_block", overrides.enableCommandBlock());
  }

  private static InstanceLaunchOverrides readOverrides(Properties values) {
    return new InstanceLaunchOverrides(
        optionalInteger(values, "override_memory_mib"),
        optionalBoolean(values, "override_save"),
        values.getProperty("override_seed"),
        optionalInteger(values, "override_view_distance"),
        optionalInteger(values, "override_simulation_distance"),
        optionalBoolean(values, "override_enable_command_block"));
  }

  private static void set(Properties values, String key, Object value) {
    if (value != null) {
      values.setProperty(key, String.valueOf(value));
    }
  }

  private static Integer optionalInteger(Properties values, String key) {
    String value = values.getProperty(key);
    return value == null ? null : Integer.valueOf(value);
  }

  private static Boolean optionalBoolean(Properties values, String key) {
    String value = values.getProperty(key);
    if (value == null) {
      return null;
    }
    if (!"true".equals(value) && !"false".equals(value)) {
      throw new IllegalArgumentException(key + " must be true or false");
    }
    return Boolean.valueOf(value);
  }

  private static String require(Properties values, String key, Path source) throws IOException {
    String value = values.getProperty(key);
    if (value == null || value.isBlank()) {
      throw invalid(source, "missing property " + key);
    }
    return value;
  }

  private static IOException invalid(Path source, String detail) {
    return new IOException("Invalid instance metadata " + source + ": " + detail);
  }

  private static IOException invalid(Path source, String detail, Exception cause) {
    return new IOException("Invalid instance metadata " + source + ": " + detail, cause);
  }
}
