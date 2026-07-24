package net.slimelabs.slslite.instance;

import java.io.IOException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;

public final class InstanceDirectoryPreparer {

    private final Path instancesRoot;

    public InstanceDirectoryPreparer(Path instancesRoot) {
        this.instancesRoot = instancesRoot.toAbsolutePath().normalize();
    }

    public Path root() {
        return instancesRoot;
    }

    public Path prepare(String instanceId, Path sourceDirectory)
            throws InstancePreparationException {
        Path destination = destination(instanceId);
        Path source = sourceDirectory.toAbsolutePath().normalize();

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
            Files.createDirectories(instancesRoot);
            copyDirectory(source, destination);
            return destination;
        } catch (IOException exception) {
            try {
                deleteDirectory(destination);
            } catch (IOException cleanupException) {
                exception.addSuppressed(cleanupException);
            }
            throw new InstancePreparationException(
                    "Unable to prepare instance directory " + destination,
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

    private static void copyDirectory(Path source, Path destination) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path directory, BasicFileAttributes attrs)
                    throws IOException {
                rejectSymbolicLink(directory, attrs);
                Path relative = source.relativize(directory);
                Files.createDirectories(destination.resolve(relative));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs)
                    throws IOException {
                rejectSymbolicLink(file, attrs);
                Path target = destination.resolve(source.relativize(file));
                Files.copy(
                        file,
                        target,
                        StandardCopyOption.COPY_ATTRIBUTES
                );
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void rejectSymbolicLink(Path path, BasicFileAttributes attrs)
            throws IOException {
        if (attrs.isSymbolicLink()) {
            throw new IOException("Symbolic links are not allowed in software templates: " + path);
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
}
