package net.slimelabs.slslite.instance;

import net.slimelabs.slslite.blueprint.BlueprintVolume;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.BooleanSupplier;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class InstanceDirectoryPreparer {

    private static final Logger LOGGER =
            LoggerFactory.getLogger(InstanceDirectoryPreparer.class);
    private static final Pattern BACKUP_DIRECTORY = Pattern.compile(
            "^\\.(.+)\\.backup-([0-9a-f-]{36})$"
    );
    private static final Pattern STAGING_DIRECTORY = Pattern.compile(
            "^\\.(.+)\\.reset-([0-9a-f-]{36})$"
    );
    private static final long[] COPY_RETRY_DELAYS_MILLIS = {
        250,
        750,
        2_000,
        5_000
    };

    private final Path instancesRoot;
    private final Path contentRoot;
    private final FileCopyOperation fileCopy;
    private final RetrySleeper retrySleeper;

    public InstanceDirectoryPreparer(Path instancesRoot) {
        this(instancesRoot, instancesRoot);
    }

    public InstanceDirectoryPreparer(Path instancesRoot, Path contentRoot) {
        this(
                instancesRoot,
                contentRoot,
                Files::copy,
                Thread::sleep
        );
    }

    InstanceDirectoryPreparer(
            Path instancesRoot,
            Path contentRoot,
            FileCopyOperation fileCopy,
            RetrySleeper retrySleeper
    ) {
        this.instancesRoot = instancesRoot.toAbsolutePath().normalize();
        this.contentRoot = contentRoot.toAbsolutePath().normalize();
        this.fileCopy = java.util.Objects.requireNonNull(fileCopy, "fileCopy");
        this.retrySleeper = java.util.Objects.requireNonNull(
                retrySleeper,
                "retrySleeper"
        );
    }

    public Path root() {
        return instancesRoot;
    }

    public Path prepare(String instanceId, Path sourceDirectory)
            throws InstancePreparationException {
        return prepare(instanceId, sourceDirectory, List.of());
    }

    public Path prepare(
            String instanceId,
            Path sourceDirectory,
            List<BlueprintVolume> volumes
    ) throws InstancePreparationException {
        return prepare(instanceId, sourceDirectory, volumes, () -> false);
    }

    Path prepare(
            String instanceId,
            Path sourceDirectory,
            List<BlueprintVolume> volumes,
            BooleanSupplier cancellationRequested
    ) throws InstancePreparationException {
        Path destination = destination(instanceId);
        Path source = sourceDirectory.toAbsolutePath().normalize();
        java.util.Objects.requireNonNull(
                cancellationRequested,
                "cancellationRequested"
        );

        if (!Files.isDirectory(source)) {
            throw new InstancePreparationException(
                    "Software base directory does not exist: " + source
            );
        }
        if (destination.startsWith(source)) {
            throw new InstancePreparationException(
                    "Software base directory cannot contain the instances directory: " + source
            );
        }
        if (Files.exists(destination)) {
            throw new InstancePreparationException(
                    "Instance directory already exists: " + destination
            );
        }

        try {
            checkCancelled(cancellationRequested);
            List<ResolvedVolume> resolvedVolumes = resolveVolumes(volumes, destination);
            Files.createDirectories(instancesRoot);
            copyDirectory(source, destination, cancellationRequested);
            applyVolumes(resolvedVolumes, cancellationRequested);
            checkCancelled(cancellationRequested);
            return destination;
        } catch (IOException | InstancePreparationException exception) {
            try {
                deleteDirectory(destination);
            } catch (IOException cleanupException) {
                exception.addSuppressed(cleanupException);
            }
            throw new InstancePreparationException(
                    "Unable to prepare instance directory " + destination
                            + ": " + exception.getMessage(),
                    exception
            );
        }
    }

    public void delete(String instanceId) throws InstancePreparationException {
        Path destination = destination(instanceId);
        try {
            deleteDirectory(destination);
        } catch (IOException exception) {
            throw new InstancePreparationException(
                    "Unable to delete instance directory " + destination,
                    exception
            );
        }
    }

    public void replace(
            String instanceId,
            Path sourceDirectory,
            DirectoryInitializer initializer
    ) throws InstancePreparationException {
        replace(instanceId, sourceDirectory, List.of(), initializer);
    }

    public void replace(
            String instanceId,
            Path sourceDirectory,
            List<BlueprintVolume> volumes,
            DirectoryInitializer initializer
    ) throws InstancePreparationException {
        Path destination = destination(instanceId);
        Path source = sourceDirectory.toAbsolutePath().normalize();
        if (!Files.isDirectory(source)) {
            throw new InstancePreparationException(
                    "Software base directory does not exist: " + source
            );
        }
        if (!Files.isDirectory(destination)) {
            throw new InstancePreparationException(
                    "Persistent instance directory does not exist: " + destination
            );
        }
        if (destination.startsWith(source) || source.startsWith(destination)) {
            throw new InstancePreparationException(
                    "Software base and persistent instance directories must not overlap"
            );
        }

        String nonce = java.util.UUID.randomUUID().toString();
        Path staging = instancesRoot.resolve("." + instanceId + ".reset-" + nonce);
        Path backup = instancesRoot.resolve("." + instanceId + ".backup-" + nonce);
        boolean originalMoved = false;
        boolean replacementMoved = false;
        boolean initialized = false;
        try {
            List<ResolvedVolume> resolvedVolumes = resolveVolumes(volumes, staging);
            copyDirectory(source, staging, () -> false);
            applyVolumes(resolvedVolumes, () -> false);
            moveDirectory(destination, backup);
            originalMoved = true;
            moveDirectory(staging, destination);
            replacementMoved = true;
            initializer.initialize(destination);
            initialized = true;
            deleteDirectory(backup);
        } catch (Exception exception) {
            if (initialized) {
                LOGGER.warn(
                        "Persistent reset for {} committed, but backup cleanup failed; "
                                + "{} will be retried during the next startup: {}",
                        instanceId,
                        backup,
                        exception.getMessage()
                );
                return;
            }
            try {
                if (replacementMoved) {
                    deleteDirectory(destination);
                }
                if (originalMoved && Files.exists(backup)) {
                    moveDirectory(backup, destination);
                }
                deleteDirectory(staging);
            } catch (IOException rollbackFailure) {
                exception.addSuppressed(rollbackFailure);
            }
            throw new InstancePreparationException(
                    "Unable to reset persistent instance " + destination,
                    exception
            );
        }
    }

    public int recoverInterruptedReplacements(DirectoryCommitVerifier verifier)
            throws IOException {
        java.util.Objects.requireNonNull(verifier, "verifier");
        Files.createDirectories(instancesRoot);
        List<Path> directories;
        try (var entries = Files.list(instancesRoot)) {
            directories = entries
                    .filter(path -> Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS))
                    .toList();
        }

        int recovered = 0;
        Set<Path> handledStaging = new HashSet<>();
        for (Path backup : directories) {
            Matcher match = BACKUP_DIRECTORY.matcher(
                    backup.getFileName().toString()
            );
            if (!match.matches() || !InstanceIdGenerator.isValid(match.group(1))) {
                continue;
            }
            String instanceId = match.group(1);
            Path destination = instancesRoot.resolve(instanceId);
            Path staging = instancesRoot.resolve(
                    "." + instanceId + ".reset-" + match.group(2)
            );
            handledStaging.add(staging);

            if (Files.isDirectory(destination, LinkOption.NOFOLLOW_LINKS)
                    && verifier.isCommitted(destination, instanceId)) {
                deleteDirectory(backup);
            } else {
                deleteDirectory(destination);
                moveDirectory(backup, destination);
            }
            deleteDirectory(staging);
            recovered++;
        }

        for (Path staging : directories) {
            if (handledStaging.contains(staging)) {
                continue;
            }
            Matcher match = STAGING_DIRECTORY.matcher(
                    staging.getFileName().toString()
            );
            if (!match.matches() || !InstanceIdGenerator.isValid(match.group(1))) {
                continue;
            }
            deleteDirectory(staging);
            recovered++;
        }
        return recovered;
    }

    private List<ResolvedVolume> resolveVolumes(
            List<BlueprintVolume> volumes,
            Path destination
    ) throws IOException, InstancePreparationException {
        if (volumes.isEmpty()) {
            return List.of();
        }

        Path realContentRoot = contentRoot.toRealPath();
        Path normalizedInstancesRoot = Files.exists(instancesRoot)
                ? instancesRoot.toRealPath()
                : instancesRoot.toAbsolutePath().normalize();
        List<ResolvedVolume> resolved = new ArrayList<>();
        for (BlueprintVolume volume : volumes) {
            if (volume.mode() == BlueprintVolume.Mode.RW) {
                throw new InstancePreparationException(
                        "Volume '" + volume.name() + "' uses mode rw. SLS-LITE "
                                + "cannot safely emulate a shared writable host mount; "
                                + "use cow or manage this server outside SLS-LITE"
                );
            }

            Path source = resolveVolumeSource(volume, realContentRoot);
            Path target = resolveVolumeTarget(volume, destination);
            if (source.startsWith(normalizedInstancesRoot)) {
                throw new InstancePreparationException(
                        "Volume source '" + volume.source()
                                + "' must not read from the instances directory"
                );
            }
            for (ResolvedVolume previous : resolved) {
                boolean sameCowTarget = target.equals(previous.target())
                        && volume.mode() == BlueprintVolume.Mode.COW
                        && previous.volume().mode() == BlueprintVolume.Mode.COW;
                if (!sameCowTarget
                        && (target.startsWith(previous.target())
                        || previous.target().startsWith(target))) {
                    throw new InstancePreparationException(
                            "Volume targets overlap: '" + previous.volume().target()
                                    + "' and '" + volume.target() + "'"
                    );
                }
            }
            resolved.add(new ResolvedVolume(volume, source, target));
        }
        return List.copyOf(resolved);
    }

    private Path resolveVolumeSource(
            BlueprintVolume volume,
            Path realContentRoot
    ) throws IOException, InstancePreparationException {
        String configured = portablePath(volume.source(), "source", volume.name());
        Path relative = configuredPath(configured, "source", volume.name());
        if (relative.isAbsolute()) {
            throw new InstancePreparationException(
                    "Volume source must be relative to " + contentRoot + ": "
                            + volume.source()
            );
        }

        Path source = contentRoot.resolve(relative).normalize();
        if (source.equals(contentRoot) || !source.startsWith(contentRoot)) {
            throw new InstancePreparationException(
                    "Volume source must stay inside " + contentRoot + ": "
                            + volume.source()
            );
        }
        if (!Files.isDirectory(source)) {
            throw new InstancePreparationException(
                    "Volume source directory does not exist: " + source
            );
        }

        rejectSymbolicPathSegments(contentRoot, source);
        Path realSource = source.toRealPath();
        if (!realSource.startsWith(realContentRoot)) {
            throw new InstancePreparationException(
                    "Volume source resolves outside " + contentRoot + ": "
                            + volume.source()
            );
        }
        return realSource;
    }

    private static void rejectSymbolicPathSegments(Path root, Path source)
            throws IOException, InstancePreparationException {
        Path current = root;
        for (Path segment : root.relativize(source)) {
            current = current.resolve(segment);
            BasicFileAttributes attributes = Files.readAttributes(
                    current,
                    BasicFileAttributes.class,
                    LinkOption.NOFOLLOW_LINKS
            );
            if (attributes.isSymbolicLink() || attributes.isOther()) {
                throw new InstancePreparationException(
                        "Volume source paths must not contain symbolic links or "
                                + "special filesystem entries: " + current
                );
            }
        }
    }

    private static Path resolveVolumeTarget(
            BlueprintVolume volume,
            Path destination
    ) throws InstancePreparationException {
        String configured = portablePath(volume.target(), "target", volume.name());
        if (configured.startsWith("//")) {
            throw new InstancePreparationException(
                    "Volume target must be an instance path such as '/world': "
                            + volume.target()
            );
        }
        String instanceRelative = configured.startsWith("/")
                ? configured.substring(1)
                : configured;
        Path relative = configuredPath(instanceRelative, "target", volume.name());
        if (relative.isAbsolute() || instanceRelative.isBlank()) {
            throw new InstancePreparationException(
                    "Volume target must identify a directory inside the instance: "
                            + volume.target()
            );
        }

        Path target = destination.resolve(relative).normalize();
        if (target.equals(destination) || !target.startsWith(destination)) {
            throw new InstancePreparationException(
                    "Volume target must stay inside the instance: " + volume.target()
            );
        }
        return target;
    }

    private static String portablePath(String configured, String field, String name)
            throws InstancePreparationException {
        String value = configured.trim();
        if (value.indexOf('\\') >= 0) {
            throw new InstancePreparationException(
                    "Volume " + field + " for '" + name
                            + "' must use portable '/' separators: " + configured
            );
        }
        return value;
    }

    private static Path configuredPath(String value, String field, String name)
            throws InstancePreparationException {
        try {
            return Path.of(value);
        } catch (InvalidPathException exception) {
            throw new InstancePreparationException(
                    "Invalid volume " + field + " for '" + name + "': " + value,
                    exception
            );
        }
    }

    private void applyVolumes(
            List<ResolvedVolume> volumes,
            BooleanSupplier cancellationRequested
    )
            throws IOException, InstancePreparationException {
        Map<Path, ResolvedVolume> appliedTargets = new LinkedHashMap<>();
        for (ResolvedVolume volume : volumes) {
            checkCancelled(cancellationRequested);
            ResolvedVolume first = appliedTargets.get(volume.target());
            if (first == null && Files.exists(volume.target())) {
                throw new InstancePreparationException(
                        "Volume target collides with existing instance content: "
                                + volume.volume().target()
                );
            }
            if (first == null) {
                copyDirectory(
                        volume.source(),
                        volume.target(),
                        cancellationRequested
                );
                appliedTargets.put(volume.target(), volume);
            } else {
                mergeDirectoryFirstWins(
                        volume.source(),
                        volume.target(),
                        cancellationRequested
                );
            }
        }
    }

    private void mergeDirectoryFirstWins(
            Path source,
            Path destination,
            BooleanSupplier cancellationRequested
    ) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(
                    Path directory,
                    BasicFileAttributes attrs
            ) throws IOException {
                checkCancelled(cancellationRequested);
                rejectSymbolicLink(directory, attrs);
                Path target = destination.resolve(source.relativize(directory));
                if (Files.exists(target) && !Files.isDirectory(target)) {
                    return FileVisitResult.SKIP_SUBTREE;
                }
                Files.createDirectories(target);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                checkCancelled(cancellationRequested);
                rejectSymbolicLink(file, attrs);
                Path target = destination.resolve(source.relativize(file));
                if (!Files.exists(target)) {
                    copyFileWithRetry(file, target, cancellationRequested);
                }
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private Path destination(String instanceId) throws InstancePreparationException {
        if (!InstanceIdGenerator.isValid(instanceId)) {
            throw new InstancePreparationException("Invalid instance ID: " + instanceId);
        }

        Path destination = instancesRoot.resolve(instanceId).normalize();
        if (!destination.startsWith(instancesRoot) || destination.equals(instancesRoot)) {
            throw new InstancePreparationException(
                    "Instance directory must stay inside " + instancesRoot
            );
        }
        return destination;
    }

    private void copyDirectory(
            Path source,
            Path destination,
            BooleanSupplier cancellationRequested
    ) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs)
                    throws IOException {
                checkCancelled(cancellationRequested);
                rejectSymbolicLink(directory, attrs);
                Path relative = source.relativize(directory);
                Files.createDirectories(destination.resolve(relative));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                checkCancelled(cancellationRequested);
                rejectSymbolicLink(file, attrs);
                Path target = destination.resolve(source.relativize(file));
                copyFileWithRetry(
                        file,
                        target,
                        cancellationRequested
                );
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private void copyFileWithRetry(
            Path source,
            Path target,
            BooleanSupplier cancellationRequested
    ) throws IOException {
        IOException lastFailure = null;
        for (int attempt = 0; attempt <= COPY_RETRY_DELAYS_MILLIS.length; attempt++) {
            checkCancelled(cancellationRequested);
            try {
                fileCopy.copy(source, target);
                checkCancelled(cancellationRequested);
                return;
            } catch (IOException exception) {
                if (exception instanceof PreparationCancelledException) {
                    throw exception;
                }
                lastFailure = exception;
                if (attempt == COPY_RETRY_DELAYS_MILLIS.length
                        || !isRetryableCopyFailure(exception)) {
                    throw exception;
                }
                try {
                    Files.deleteIfExists(target);
                } catch (IOException cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                    throw exception;
                }
                try {
                    sleepWithCancellation(
                            COPY_RETRY_DELAYS_MILLIS[attempt],
                            cancellationRequested
                    );
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    IOException failure = new IOException(
                            "Interrupted while retrying file copy: " + source,
                            interrupted
                    );
                    failure.addSuppressed(exception);
                    throw failure;
                }
            }
        }
        throw lastFailure;
    }

    private void sleepWithCancellation(
            long milliseconds,
            BooleanSupplier cancellationRequested
    ) throws InterruptedException, PreparationCancelledException {
        long remaining = milliseconds;
        while (remaining > 0) {
            checkCancelled(cancellationRequested);
            long slice = Math.min(remaining, 100);
            retrySleeper.sleep(slice);
            remaining -= slice;
        }
        checkCancelled(cancellationRequested);
    }

    private static void checkCancelled(BooleanSupplier cancellationRequested)
            throws PreparationCancelledException {
        if (cancellationRequested.getAsBoolean()) {
            throw new PreparationCancelledException();
        }
    }

    private static boolean isRetryableCopyFailure(IOException exception) {
        return exception instanceof FileSystemException
                && !(exception instanceof FileAlreadyExistsException)
                && !(exception instanceof NoSuchFileException);
    }

    private static void moveDirectory(Path source, Path destination) throws IOException {
        try {
            Files.move(source, destination, StandardCopyOption.ATOMIC_MOVE);
        } catch (java.nio.file.AtomicMoveNotSupportedException exception) {
            Files.move(source, destination);
        }
    }

    private static void rejectSymbolicLink(Path path, BasicFileAttributes attrs)
            throws IOException {
        if (attrs.isSymbolicLink() || attrs.isOther()) {
            throw new IOException(
                    "Symbolic links and special filesystem entries are not allowed "
                            + "in copied directories: " + path
            );
        }
    }

    private static void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory)) {
            return;
        }

        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path current, IOException exception)
                    throws IOException {
                if (exception != null) {
                    throw exception;
                }
                Files.delete(current);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    @FunctionalInterface
    public interface DirectoryInitializer {
        void initialize(Path directory) throws Exception;
    }

    @FunctionalInterface
    public interface DirectoryCommitVerifier {
        boolean isCommitted(Path directory, String instanceId) throws IOException;
    }

    @FunctionalInterface
    interface FileCopyOperation {
        void copy(Path source, Path target) throws IOException;
    }

    @FunctionalInterface
    interface RetrySleeper {
        void sleep(long milliseconds) throws InterruptedException;
    }

    private static final class PreparationCancelledException extends IOException {

        private PreparationCancelledException() {
            super("Instance preparation was cancelled");
        }
    }

    private record ResolvedVolume(
            BlueprintVolume volume,
            Path source,
            Path target
    ) {
    }
}
