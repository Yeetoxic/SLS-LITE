package net.slimelabs.slslite.instance;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.DateTimeException;
import java.time.Instant;
import java.util.Optional;
import java.util.Properties;

public final class InstanceMetadataStore {

    public static final String FILE_NAME = ".sls-lite-instance.properties";
    private static final String TEMP_FILE_NAME = FILE_NAME + ".tmp";
    private static final String SCHEMA_VERSION = "1";

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

        Properties values = new Properties();
        try (InputStream input = Files.newInputStream(metadataPath)) {
            values.load(input);
        }
        return Optional.of(parse(values, metadataPath));
    }

    public void write(Path instanceDirectory, InstanceMetadata metadata) throws IOException {
        Path directory = requireDirectChild(instanceDirectory);
        if (!Files.isDirectory(directory, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Instance directory does not exist: " + directory);
        }
        if (!directory.getFileName().toString().equals(metadata.instanceId())) {
            throw new IOException(
                    "Instance metadata ID does not match directory: " + directory
            );
        }

        Properties values = new Properties();
        values.setProperty("schema", SCHEMA_VERSION);
        values.setProperty("instance_id", metadata.instanceId());
        values.setProperty("blueprint_id", metadata.blueprintId());
        values.setProperty("persistent", Boolean.toString(metadata.persistent()));
        values.setProperty("state", metadata.state().name());
        values.setProperty("created_at", metadata.createdAt().toString());
        if (metadata.processId() != null) {
            values.setProperty("process_id", Long.toString(metadata.processId()));
            if (metadata.processStartedAt() != null) {
                values.setProperty(
                        "process_started_at",
                        metadata.processStartedAt().toString()
                );
            }
        }

        Path temporary = directory.resolve(TEMP_FILE_NAME);
        Path destination = directory.resolve(FILE_NAME);
        try (OutputStream output = Files.newOutputStream(
                temporary,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        )) {
            values.store(output, "Managed by SLS-LITE");
        }
        try {
            Files.move(
                    temporary,
                    destination,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(
                    temporary,
                    destination,
                    StandardCopyOption.REPLACE_EXISTING
            );
        }
    }

    private Path requireDirectChild(Path instanceDirectory) throws IOException {
        Path normalized = instanceDirectory.toAbsolutePath().normalize();
        if (!instancesRoot.equals(normalized.getParent())) {
            throw new IOException(
                    "Instance directory must be a direct child of " + instancesRoot
            );
        }
        return normalized;
    }

    private static InstanceMetadata parse(Properties values, Path source) throws IOException {
        try {
            require(values, "schema", source, SCHEMA_VERSION);
            String instanceId = require(values, "instance_id", source);
            String blueprintId = require(values, "blueprint_id", source);
            String persistentText = require(values, "persistent", source);
            if (!"true".equals(persistentText) && !"false".equals(persistentText)) {
                throw invalid(source, "persistent must be true or false");
            }
            InstanceState state = InstanceState.valueOf(require(values, "state", source));
            Instant createdAt = Instant.parse(require(values, "created_at", source));

            String processIdText = values.getProperty("process_id");
            String processStartedText = values.getProperty("process_started_at");
            if (processIdText == null && processStartedText != null) {
                throw invalid(
                        source,
                        "process_started_at requires process_id"
                );
            }
            Long processId = processIdText == null ? null : Long.parseLong(processIdText);
            Instant processStartedAt = processStartedText == null
                    ? null
                    : Instant.parse(processStartedText);
            return new InstanceMetadata(
                    instanceId,
                    blueprintId,
                    Boolean.parseBoolean(persistentText),
                    state,
                    createdAt,
                    processId,
                    processStartedAt
            );
        } catch (IllegalArgumentException | DateTimeException exception) {
            throw invalid(source, exception.getMessage(), exception);
        }
    }

    private static String require(Properties values, String key, Path source)
            throws IOException {
        String value = values.getProperty(key);
        if (value == null || value.isBlank()) {
            throw invalid(source, "missing property " + key);
        }
        return value;
    }

    private static void require(
            Properties values,
            String key,
            Path source,
            String expected
    ) throws IOException {
        String value = require(values, key, source);
        if (!expected.equals(value)) {
            throw invalid(source, "unsupported " + key + " " + value);
        }
    }

    private static IOException invalid(Path source, String detail) {
        return new IOException("Invalid instance metadata " + source + ": " + detail);
    }

    private static IOException invalid(Path source, String detail, Exception cause) {
        return new IOException("Invalid instance metadata " + source + ": " + detail, cause);
    }
}
