package net.slimelabs.slslite.instance.storage;

import net.slimelabs.slslite.blueprint.BlueprintVolume;
import net.slimelabs.slslite.config.StorageStrategy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Opt-in Btrfs lifecycle gate for an empty disposable Btrfs directory.
 */
public final class BtrfsPreparerRealKernelHarness {

    private BtrfsPreparerRealKernelHarness() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) {
            throw new IllegalArgumentException(
                    "Expected one empty disposable Btrfs root"
            );
        }
        Path root = Path.of(arguments[0]).toAbsolutePath().normalize();
        if (!Files.isDirectory(root)) {
            throw new IllegalArgumentException(
                    "Disposable Btrfs root does not exist: " + root
            );
        }
        try (var entries = Files.list(root)) {
            if (entries.findAny().isPresent()) {
                throw new IllegalArgumentException(
                        "Disposable Btrfs root must be empty: " + root
                );
            }
        }

        Path content = root.resolve("content");
        Path instances = content.resolve("instances");
        Path software = content.resolve("software/paper");
        Path world = content.resolve("worlds/adventure");
        Files.createDirectories(software);
        Files.createDirectories(world.getParent());
        run("btrfs", "subvolume", "create", world.toString());
        Files.writeString(software.resolve("server.jar"), "server");
        Files.writeString(world.resolve("level.dat"), "source-v1");
        byte[] bulk = new byte[8 * 1_024 * 1_024];
        java.util.Arrays.fill(bulk, (byte) 0x5a);
        Files.write(world.resolve("bulk.bin"), bulk);

        InstanceDirectoryPreparer preparer = new InstanceDirectoryPreparer(
                instances,
                content,
                StorageStrategy.BTRFS,
                StorageStrategy.BTRFS
        );
        BlueprintVolume volume = new BlueprintVolume(
                "world",
                "worlds/adventure",
                "/world",
                BlueprintVolume.Mode.COW
        );

        Path prepared = preparer.prepare(
                "game.x82odk",
                software,
                List.of(volume)
        );
        assertSubvolume(prepared.resolve("world"));
        assertSharedExtents(
                world.resolve("bulk.bin"),
                prepared.resolve("world/bulk.bin")
        );
        Files.writeString(prepared.resolve("world/level.dat"), "instance");
        assertEquals("source-v1", Files.readString(world.resolve("level.dat")));

        Files.writeString(world.resolve("level.dat"), "source-v2");
        preparer.replace(
                "game.x82odk",
                software,
                List.of(volume),
                ignored -> {
                }
        );
        assertSubvolume(prepared.resolve("world"));
        assertEquals(
                "source-v2",
                Files.readString(prepared.resolve("world/level.dat"))
        );

        preparer.delete("game.x82odk");
        if (Files.exists(prepared)) {
            throw new IllegalStateException(
                    "Btrfs instance survived transactional deletion"
            );
        }
        assertEquals("source-v2", Files.readString(world.resolve("level.dat")));
        System.out.println(
                "Btrfs preparer real-kernel lifecycle passed"
        );
    }

    private static void assertSubvolume(Path path) throws Exception {
        run("btrfs", "subvolume", "show", path.toString());
    }

    private static void assertSharedExtents(Path source, Path snapshot)
            throws Exception {
        Process process = new ProcessBuilder(
                "btrfs",
                "filesystem",
                "du",
                "--raw",
                source.toString(),
                snapshot.toString()
        )
                .redirectErrorStream(true)
                .start();
        String output = new String(
                process.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8
        );
        if (!process.waitFor(30, TimeUnit.SECONDS)
                || process.exitValue() != 0) {
            process.destroyForcibly();
            throw new IllegalStateException(
                    "Unable to inspect Btrfs shared extents: " + output
            );
        }
        long sharedLines = output.lines()
                .map(String::strip)
                .filter(line -> !line.isBlank())
                .filter(line -> Character.isDigit(line.charAt(0)))
                .map(line -> line.split("\\s+", 4))
                .filter(columns -> columns.length == 4)
                .filter(columns -> Long.parseLong(columns[2]) > 0)
                .count();
        if (sharedLines != 2) {
            throw new IllegalStateException(
                    "Expected source and snapshot to report shared extents: "
                            + output
            );
        }
    }

    private static void assertEquals(String expected, String actual) {
        if (!expected.equals(actual)) {
            throw new IllegalStateException(
                    "Expected '" + expected + "' but found '" + actual + "'"
            );
        }
    }

    private static void run(String... command) throws Exception {
        Process process = new ProcessBuilder(command)
                .inheritIO()
                .start();
        if (!process.waitFor(30, TimeUnit.SECONDS)) {
            process.destroyForcibly();
            throw new IllegalStateException(
                    "Command timed out: " + String.join(" ", command)
            );
        }
        if (process.exitValue() != 0) {
            throw new IllegalStateException(
                    "Command failed: " + String.join(" ", command)
            );
        }
    }
}
