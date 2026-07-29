package net.slimelabs.slslite.instance;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

final class PaperRuntimeCachePromoter {

    private static final List<String> CACHE_DIRECTORIES = List.of("cache", "libraries");
    private final Map<Path, Object> promotionLocks = new ConcurrentHashMap<>();

    List<String> promote(Path instanceDirectory, Path softwareBaseDirectory)
            throws IOException {
        Path instance = instanceDirectory.toAbsolutePath().normalize();
        Path softwareBase = softwareBaseDirectory.toAbsolutePath().normalize();
        if (instance.startsWith(softwareBase) || softwareBase.startsWith(instance)) {
            throw new IOException("Instance and software cache directories must not overlap");
        }

        Object lock = promotionLocks.computeIfAbsent(softwareBase, ignored -> new Object());
        synchronized (lock) {
            return promoteLocked(instance, softwareBase);
        }
    }

    private static List<String> promoteLocked(Path instance, Path softwareBase)
            throws IOException {
        List<String> promoted = new ArrayList<>();
        for (String name : CACHE_DIRECTORIES) {
            Path source = instance.resolve(name);
            Path target = softwareBase.resolve(name);
            if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)
                    || Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                continue;
            }

            Path staging = softwareBase.resolve(
                    ".sls-" + name + "-promoting-" + UUID.randomUUID()
            );
            try {
                copyDirectory(source, staging);
                if (moveIfAbsent(staging, target)) {
                    promoted.add(name);
                }
            } finally {
                deleteDirectory(staging);
            }
        }
        return List.copyOf(promoted);
    }

    private static boolean moveIfAbsent(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
            return true;
        } catch (AtomicMoveNotSupportedException exception) {
            try {
                Files.move(source, target);
                return true;
            } catch (FileAlreadyExistsException ignored) {
                return false;
            }
        } catch (FileAlreadyExistsException ignored) {
            return false;
        }
    }

    private static void copyDirectory(Path source, Path destination) throws IOException {
        Files.walkFileTree(source, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(
                    Path directory,
                    BasicFileAttributes attributes
            ) throws IOException {
                rejectSpecialEntry(directory, attributes);
                Files.createDirectories(destination.resolve(source.relativize(directory)));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                rejectSpecialEntry(file, attributes);
                Files.copy(
                        file,
                        destination.resolve(source.relativize(file)),
                        StandardCopyOption.COPY_ATTRIBUTES
                );
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private static void rejectSpecialEntry(Path path, BasicFileAttributes attributes)
            throws IOException {
        if (attributes.isSymbolicLink() || attributes.isOther()) {
            throw new IOException(
                    "Paper runtime cache contains an unsupported filesystem entry: " + path
            );
        }
    }

    private static void deleteDirectory(Path directory) throws IOException {
        if (!Files.exists(directory, LinkOption.NOFOLLOW_LINKS)) {
            return;
        }
        Files.walkFileTree(directory, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attributes)
                    throws IOException {
                Files.delete(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path current, IOException failure)
                    throws IOException {
                if (failure != null) {
                    throw failure;
                }
                Files.delete(current);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
