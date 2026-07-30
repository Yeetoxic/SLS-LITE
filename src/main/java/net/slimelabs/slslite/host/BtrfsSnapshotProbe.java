package net.slimelabs.slslite.host;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

final class BtrfsSnapshotProbe {

    private final BtrfsSubvolumeOperations subvolumes;

    BtrfsSnapshotProbe() {
        this(new BtrfsSubvolumeOperations());
    }

    BtrfsSnapshotProbe(BtrfsSubvolumeOperations subvolumes) {
        this.subvolumes = java.util.Objects.requireNonNull(
                subvolumes,
                "subvolumes"
        );
    }

    Result probe(Path storagePath) {
        Path probe = null;
        Path source = null;
        Path snapshot = null;
        try {
            Files.createDirectories(storagePath);
            if (!subvolumes.available()) {
                return unsupported(
                        "the btrfs userspace command is unavailable"
                );
            }
            probe = Files.createTempDirectory(
                    storagePath,
                    ".sls-btrfs-probe-"
            );
            source = probe.resolve("source");
            snapshot = probe.resolve("snapshot");
            subvolumes.create(source);
            Files.writeString(source.resolve("marker"), "source");
            subvolumes.snapshot(source, snapshot);
            if (!subvolumes.isSubvolume(snapshot)
                    || !"source".equals(
                            Files.readString(snapshot.resolve("marker"))
                    )) {
                return unsupported(
                        "the contained snapshot did not preserve its source"
                );
            }
            Files.writeString(snapshot.resolve("marker"), "snapshot");
            if (!"source".equals(
                    Files.readString(source.resolve("marker"))
            )) {
                return unsupported(
                        "the contained snapshot write-isolation check failed"
                );
            }
            subvolumes.delete(snapshot);
            snapshot = null;
            subvolumes.delete(source);
            source = null;
            Files.delete(probe);
            probe = null;
            return supported(
                    "contained subvolume snapshot, write isolation, deletion, "
                            + "and cleanup passed"
            );
        } catch (IOException | RuntimeException exception) {
            return unsupported(
                    "contained probe failed: " + message(exception)
            );
        } finally {
            cleanupSubvolume(snapshot);
            cleanupSubvolume(source);
            deleteDirectoryIfEmpty(probe);
        }
    }

    private void cleanupSubvolume(Path path) {
        if (path == null || !Files.exists(path)) {
            return;
        }
        try {
            if (subvolumes.isSubvolume(path)) {
                subvolumes.delete(path);
            }
        } catch (IOException ignored) {
            // Preserve a failed probe for diagnosis instead of traversing it.
        }
    }

    private static void deleteDirectoryIfEmpty(Path path) {
        if (path == null || !Files.isDirectory(path)) {
            return;
        }
        try {
            Files.delete(path);
        } catch (IOException ignored) {
            // Preserve a failed probe for diagnosis.
        }
    }

    private static Result supported(String detail) {
        return new Result(true, detail);
    }

    private static Result unsupported(String detail) {
        return new Result(false, detail);
    }

    private static String message(Throwable throwable) {
        return throwable.getMessage() == null
                ? throwable.getClass().getSimpleName()
                : throwable.getMessage();
    }

    record Result(boolean supported, String detail) {
    }
}
