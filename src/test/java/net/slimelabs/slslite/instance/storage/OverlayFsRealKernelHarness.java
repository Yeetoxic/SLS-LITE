package net.slimelabs.slslite.instance.storage;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * Opt-in harness for a disposable, privileged Linux test environment.
 *
 * <p>This is intentionally not a normal unit test. Invoke its main method with
 * an empty directory on a native Linux filesystem after compiling test
 * classes. The caller owns cleanup of that disposable directory.</p>
 */
public final class OverlayFsRealKernelHarness {

    private OverlayFsRealKernelHarness() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) {
            throw new IllegalArgumentException(
                    "Expected one disposable test-root argument"
            );
        }
        Path root = Path.of(arguments[0]).toAbsolutePath().normalize();
        if (!Files.isDirectory(root) || !isEmpty(root)) {
            throw new IllegalArgumentException(
                    "Test root must be an existing empty directory: " + root
            );
        }

        Path content = Files.createDirectory(root.resolve("content"));
        Path instances = Files.createDirectory(root.resolve("instances"));
        Path instance = Files.createDirectory(instances.resolve("game.abc123"));
        Path first = Files.createDirectories(content.resolve("worlds/first"));
        Path second = Files.createDirectories(content.resolve("worlds/second"));
        Files.writeString(first.resolve("shared.dat"), "first");
        Files.writeString(second.resolve("shared.dat"), "second");
        Files.writeString(first.resolve("source.dat"), "source");

        OverlayFsLayerManager manager =
                new OverlayFsLayerManager(instances, content);
        Path target = instance.resolve("world");
        try {
            manager.prepare(instance, List.of(new OverlayFsLayerManager.Layer(
                    Path.of("world"),
                    List.of(first, second)
            )));
            requireContent(target.resolve("shared.dat"), "first");
            Files.writeString(target.resolve("source.dat"), "private");
            Files.writeString(target.resolve("new.dat"), "created");

            // A fresh manager represents proxy restart/crash reconciliation.
            manager = new OverlayFsLayerManager(instances, content);
            manager.suspend(instance);
            requireContent(first.resolve("source.dat"), "source");
            if (!Files.isRegularFile(instance.resolve(
                    ".sls-lite-overlay-layers/0/upper/new.dat"
            ))) {
                throw new IllegalStateException(
                        "Private upper-layer file did not survive unmount"
                );
            }

            manager.resume(instance);
            requireContent(target.resolve("source.dat"), "private");
            requireContent(target.resolve("new.dat"), "created");
            manager.suspend(instance);

            run("mount", "-t", "tmpfs", "tmpfs", target.toString());
            try {
                manager.suspend(instance);
                throw new IllegalStateException(
                        "Manager unmounted an unexpected filesystem"
                );
            } catch (java.io.IOException expected) {
                if (!new OverlayFsMountOperations().isMounted(target)) {
                    throw new IllegalStateException(
                            "Unexpected filesystem was not preserved",
                            expected
                    );
                }
            } finally {
                run("umount", target.toString());
            }
            System.out.println("SLS-LITE OverlayFS Java lifecycle PASS");
        } finally {
            try {
                manager.suspend(instance);
            } catch (Exception ignored) {
                // The disposable caller retains the directory for diagnosis.
            }
        }
    }

    private static boolean isEmpty(Path directory) throws Exception {
        try (var entries = Files.list(directory)) {
            return entries.findAny().isEmpty();
        }
    }

    private static void requireContent(Path file, String expected)
            throws Exception {
        String actual = Files.readString(file);
        if (!expected.equals(actual)) {
            throw new IllegalStateException(
                    file + " contained '" + actual + "', expected '" + expected + "'"
            );
        }
    }

    private static void run(String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .inheritIO()
                .start();
        if (process.waitFor() != 0) {
            throw new IllegalStateException(
                    command[0] + " failed with exit code " + process.exitValue()
            );
        }
    }
}
