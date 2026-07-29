package net.slimelabs.slslite.instance;

import net.slimelabs.slslite.blueprint.BlueprintVolume;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.FileSystemException;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstanceDirectoryPreparerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void createsIndependentDirectoryCopy() throws Exception {
        Path source = createSource();
        Path instances = temporaryDirectory.resolve("instances");
        InstanceDirectoryPreparer preparer = new InstanceDirectoryPreparer(instances);

        Path prepared = preparer.prepare("game.x82odk", source);
        Files.writeString(prepared.resolve("config/settings.yml"), "changed");

        assertEquals("original", Files.readString(source.resolve("config/settings.yml")));
        assertEquals("server", Files.readString(prepared.resolve("server.jar")));
    }

    @Test
    void copiesCowVolumeIntoInstanceTarget() throws Exception {
        Path source = createSource();
        Path world = temporaryDirectory.resolve("worlds/minigames/spleef");
        Files.createDirectories(world.resolve("region"));
        Files.writeString(world.resolve("level.dat"), "world");
        Files.writeString(world.resolve("region/r.0.0.mca"), "region");
        InstanceDirectoryPreparer preparer = new InstanceDirectoryPreparer(
                temporaryDirectory.resolve("instances"),
                temporaryDirectory
        );

        Path prepared = preparer.prepare(
                "game.x82odk",
                source,
                List.of(volume("worlds/minigames/spleef", "/world"))
        );
        Files.writeString(prepared.resolve("world/level.dat"), "instance change");

        assertEquals("world", Files.readString(world.resolve("level.dat")));
        assertEquals("region", Files.readString(prepared.resolve("world/region/r.0.0.mca")));
    }

    @Test
    void retriesTransientFilesystemCopyFailures() throws Exception {
        Path source = createSource();
        Path instances = temporaryDirectory.resolve("instances");
        AtomicInteger attempts = new AtomicInteger();
        AtomicLong sleptMillis = new AtomicLong();
        InstanceDirectoryPreparer preparer = new InstanceDirectoryPreparer(
                instances,
                temporaryDirectory,
                (file, target) -> {
                    if (file.getFileName().toString().equals("server.jar")
                            && attempts.incrementAndGet() < 3) {
                        Files.writeString(target, "partial");
                        throw new FileSystemException(
                                file.toString(),
                                target.toString(),
                                "Input/output error"
                        );
                    }
                    Files.copy(
                            file,
                            target,
                            java.nio.file.StandardCopyOption.COPY_ATTRIBUTES
                    );
                },
                sleptMillis::addAndGet
        );

        Path prepared = preparer.prepare("game.x82odk", source);

        assertEquals("server", Files.readString(prepared.resolve("server.jar")));
        assertEquals(3, attempts.get());
        assertEquals(1_000, sleptMillis.get());
    }

    @Test
    void cancellationBetweenFilesRollsBackPartialInstance() throws Exception {
        Path source = createSource();
        Path instances = temporaryDirectory.resolve("instances");
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicInteger copies = new AtomicInteger();
        InstanceDirectoryPreparer preparer = new InstanceDirectoryPreparer(
                instances,
                temporaryDirectory,
                (file, target) -> {
                    Files.copy(file, target);
                    copies.incrementAndGet();
                    cancelled.set(true);
                },
                Thread::sleep
        );

        InstancePreparationException failure = assertThrows(
                InstancePreparationException.class,
                () -> preparer.prepare(
                        "game.x82odk",
                        source,
                        List.of(),
                        cancelled::get
                )
        );

        assertTrue(failure.getMessage().contains("cancelled"));
        assertEquals(1, copies.get());
        assertFalse(Files.exists(instances.resolve("game.x82odk")));
    }

    @Test
    void cancellationInterruptsRetryBackoffAndRollsBack() throws Exception {
        Path source = createSource();
        Path instances = temporaryDirectory.resolve("instances");
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicInteger attempts = new AtomicInteger();
        AtomicLong sleptMillis = new AtomicLong();
        InstanceDirectoryPreparer preparer = new InstanceDirectoryPreparer(
                instances,
                temporaryDirectory,
                (file, target) -> {
                    attempts.incrementAndGet();
                    throw new FileSystemException(
                            file.toString(),
                            target.toString(),
                            "Input/output error"
                    );
                },
                milliseconds -> {
                    sleptMillis.addAndGet(milliseconds);
                    cancelled.set(true);
                }
        );

        InstancePreparationException failure = assertThrows(
                InstancePreparationException.class,
                () -> preparer.prepare(
                        "game.x82odk",
                        source,
                        List.of(),
                        cancelled::get
                )
        );

        assertTrue(failure.getMessage().contains("cancelled"));
        assertEquals(1, attempts.get());
        assertEquals(100, sleptMillis.get());
        assertFalse(Files.exists(instances.resolve("game.x82odk")));
    }

    @Test
    void rollsBackAfterTransientCopyRetryBudgetIsExhausted() throws Exception {
        Path source = createSource();
        Path instances = temporaryDirectory.resolve("instances");
        AtomicInteger attempts = new AtomicInteger();
        InstanceDirectoryPreparer preparer = new InstanceDirectoryPreparer(
                instances,
                temporaryDirectory,
                (file, target) -> {
                    attempts.incrementAndGet();
                    throw new FileSystemException(
                            file.toString(),
                            target.toString(),
                            "Input/output error"
                    );
                },
                ignored -> {
                }
        );

        assertThrows(
                InstancePreparationException.class,
                () -> preparer.prepare("game.x82odk", source)
        );

        assertEquals(5, attempts.get());
        assertFalse(Files.exists(instances.resolve("game.x82odk")));
    }

    @Test
    void rejectsVolumeSourceTraversal() throws Exception {
        Path source = createSource();
        Path contentRoot = temporaryDirectory.resolve("data");
        Path outside = temporaryDirectory.resolve("outside-volume");
        Files.createDirectories(contentRoot);
        Files.createDirectories(outside);
        InstanceDirectoryPreparer preparer = new InstanceDirectoryPreparer(
                temporaryDirectory.resolve("instances"),
                contentRoot
        );

        assertThrows(
                InstancePreparationException.class,
                () -> preparer.prepare(
                        "game.x82odk",
                        source,
                        List.of(volume("../outside-volume", "/world"))
                )
        );
        assertFalse(Files.exists(temporaryDirectory.resolve("instances/game.x82odk")));
    }

    @Test
    void rejectsVolumeTargetTraversal() throws Exception {
        Path source = createSource();
        createVolumeSource("worlds/game");
        InstanceDirectoryPreparer preparer = new InstanceDirectoryPreparer(
                temporaryDirectory.resolve("instances"),
                temporaryDirectory
        );

        assertThrows(
                InstancePreparationException.class,
                () -> preparer.prepare(
                        "game.x82odk",
                        source,
                        List.of(volume("worlds/game", "/../escaped"))
                )
        );
        assertFalse(Files.exists(temporaryDirectory.resolve("escaped")));
    }

    @Test
    void reportsInvalidVolumePathsAsPreparationFailures() throws Exception {
        Path source = createSource();
        createVolumeSource("worlds/game");
        InstanceDirectoryPreparer preparer = new InstanceDirectoryPreparer(
                temporaryDirectory.resolve("instances"),
                temporaryDirectory
        );

        InstancePreparationException exception = assertThrows(
                InstancePreparationException.class,
                () -> preparer.prepare(
                        "game.x82odk",
                        source,
                        List.of(volume("worlds/game", "/world\u0000invalid"))
                )
        );

        assertTrue(exception.getMessage().contains("Invalid volume target"));
        assertFalse(Files.exists(temporaryDirectory.resolve("instances/game.x82odk")));
    }

    @Test
    void rejectsOverlappingVolumeTargets() throws Exception {
        Path source = createSource();
        createVolumeSource("worlds/one");
        createVolumeSource("worlds/two");
        InstanceDirectoryPreparer preparer = new InstanceDirectoryPreparer(
                temporaryDirectory.resolve("instances"),
                temporaryDirectory
        );

        InstancePreparationException exception = assertThrows(
                InstancePreparationException.class,
                () -> preparer.prepare(
                        "game.x82odk",
                        source,
                        List.of(
                                volume("worlds/one", "/world"),
                                volume("worlds/two", "/world/data")
                        )
                )
        );

        assertTrue(exception.getMessage().contains("overlap"));
    }

    @Test
    void rollsBackWhenVolumeCollidesWithSoftwareBase() throws Exception {
        Path source = createSource();
        Files.createDirectories(source.resolve("world"));
        createVolumeSource("worlds/game");
        Path instances = temporaryDirectory.resolve("instances");
        InstanceDirectoryPreparer preparer = new InstanceDirectoryPreparer(
                instances,
                temporaryDirectory
        );

        InstancePreparationException exception = assertThrows(
                InstancePreparationException.class,
                () -> preparer.prepare(
                        "game.x82odk",
                        source,
                        List.of(volume("worlds/game", "/world"))
                )
        );

        assertTrue(exception.getMessage().contains("Unable to prepare instance directory"));
        assertFalse(Files.exists(instances.resolve("game.x82odk")));
    }

    @Test
    void rejectsSymbolicLinkVolumeSource() throws Exception {
        Path source = createSource();
        Path realWorld = createVolumeSource("worlds/real");
        Path linkedWorld = temporaryDirectory.resolve("worlds/linked");
        createSymbolicLinkOrSkip(linkedWorld, realWorld);
        Path instances = temporaryDirectory.resolve("instances");
        InstanceDirectoryPreparer preparer = new InstanceDirectoryPreparer(
                instances,
                temporaryDirectory
        );

        InstancePreparationException exception = assertThrows(
                InstancePreparationException.class,
                () -> preparer.prepare(
                        "game.x82odk",
                        source,
                        List.of(volume("worlds/linked", "/world"))
                )
        );

        assertTrue(exception.getMessage().contains("symbolic links"));
        assertFalse(Files.exists(instances.resolve("game.x82odk")));
    }

    @Test
    void rollsBackWhenVolumeContainsSymbolicLink() throws Exception {
        Path source = createSource();
        Path world = createVolumeSource("worlds/game");
        Path outside = temporaryDirectory.resolve("outside");
        Files.createDirectories(outside);
        Files.writeString(outside.resolve("outside.dat"), "outside");
        createSymbolicLinkOrSkip(world.resolve("linked"), outside);
        Path instances = temporaryDirectory.resolve("instances");
        InstanceDirectoryPreparer preparer = new InstanceDirectoryPreparer(
                instances,
                temporaryDirectory
        );

        InstancePreparationException exception = assertThrows(
                InstancePreparationException.class,
                () -> preparer.prepare(
                        "game.x82odk",
                        source,
                        List.of(volume("worlds/game", "/world"))
                )
        );

        assertTrue(exception.getMessage().contains("Symbolic links"));
        assertFalse(Files.exists(instances.resolve("game.x82odk")));
    }

    @Test
    void rejectsVolumeSourcesInsideManagedInstances() throws Exception {
        Path source = createSource();
        Path instances = temporaryDirectory.resolve("instances");
        Path activeInstance = instances.resolve("other.abc123");
        Files.createDirectories(activeInstance);
        Files.writeString(activeInstance.resolve("level.dat"), "runtime data");
        InstanceDirectoryPreparer preparer = new InstanceDirectoryPreparer(
                instances,
                temporaryDirectory
        );

        InstancePreparationException exception = assertThrows(
                InstancePreparationException.class,
                () -> preparer.prepare(
                        "game.x82odk",
                        source,
                        List.of(volume("instances/other.abc123", "/world"))
                )
        );

        assertTrue(exception.getMessage().contains("instances directory"));
    }

    @Test
    void replacementReappliesCowVolume() throws Exception {
        Path source = createSource();
        Path world = createVolumeSource("worlds/game");
        Files.writeString(world.resolve("level.dat"), "clean");
        Path instances = temporaryDirectory.resolve("instances");
        InstanceDirectoryPreparer preparer = new InstanceDirectoryPreparer(
                instances,
                temporaryDirectory
        );
        Path prepared = preparer.prepare(
                "game.x82odk",
                source,
                List.of(volume("worlds/game", "/world"))
        );
        Files.writeString(prepared.resolve("world/level.dat"), "changed");

        preparer.replace(
                "game.x82odk",
                source,
                List.of(volume("worlds/game", "/world")),
                ignored -> {
                }
        );

        assertEquals("clean", Files.readString(prepared.resolve("world/level.dat")));
    }

    @Test
    void refusesExistingInstanceDirectory() throws Exception {
        Path source = createSource();
        Path instances = temporaryDirectory.resolve("instances");
        Files.createDirectories(instances.resolve("game.x82odk"));
        InstanceDirectoryPreparer preparer = new InstanceDirectoryPreparer(instances);

        assertThrows(
                InstancePreparationException.class,
                () -> preparer.prepare("game.x82odk", source)
        );
    }

    @Test
    void rejectsUnsafeInstanceId() throws Exception {
        Path source = createSource();
        InstanceDirectoryPreparer preparer =
                new InstanceDirectoryPreparer(temporaryDirectory.resolve("instances"));

        assertThrows(
                InstancePreparationException.class,
                () -> preparer.prepare("../outside", source)
        );
        assertFalse(Files.exists(temporaryDirectory.resolve("outside")));
    }

    @Test
    void deletesOnlyNamedInstanceDirectory() throws Exception {
        Path source = createSource();
        Path instances = temporaryDirectory.resolve("instances");
        InstanceDirectoryPreparer preparer = new InstanceDirectoryPreparer(instances);
        Path prepared = preparer.prepare("game.x82odk", source);

        preparer.delete("game.x82odk");

        assertFalse(Files.exists(prepared));
        assertTrue(Files.exists(source));
    }

    @Test
    void replacementRollsBackWhenInitializationFails() throws Exception {
        Path source = createSource();
        Path instances = temporaryDirectory.resolve("instances");
        InstanceDirectoryPreparer preparer = new InstanceDirectoryPreparer(instances);
        Path prepared = preparer.prepare("game.x82odk", source);
        Files.writeString(prepared.resolve("world-data"), "keep me");

        Files.writeString(source.resolve("server.jar"), "replacement");
        assertThrows(
                InstancePreparationException.class,
                () -> preparer.replace(
                        "game.x82odk",
                        source,
                        ignored -> {
                            throw new IllegalStateException("metadata failed");
                        }
                )
        );

        assertEquals("keep me", Files.readString(prepared.resolve("world-data")));
        assertEquals("server", Files.readString(prepared.resolve("server.jar")));
        try (var entries = Files.list(instances)) {
            assertEquals(
                    java.util.List.of("game.x82odk"),
                    entries.map(path -> path.getFileName().toString()).sorted().toList()
            );
        }
    }

    private Path createSource() throws Exception {
        Path source = temporaryDirectory.resolve("software");
        Files.createDirectories(source.resolve("config"));
        Files.writeString(source.resolve("server.jar"), "server");
        Files.writeString(source.resolve("config/settings.yml"), "original");
        return source;
    }

    private Path createVolumeSource(String relative) throws Exception {
        Path source = temporaryDirectory.resolve(relative);
        Files.createDirectories(source);
        Files.writeString(source.resolve("volume.txt"), "volume");
        return source;
    }

    private static BlueprintVolume volume(String source, String target) {
        return new BlueprintVolume(
                "world",
                source,
                target,
                BlueprintVolume.Mode.COW
        );
    }

    private static void createSymbolicLinkOrSkip(Path link, Path target) throws Exception {
        try {
            Files.createSymbolicLink(link, target);
            return;
        } catch (UnsupportedOperationException | java.io.IOException symlinkFailure) {
            boolean windows = System.getProperty("os.name")
                    .toLowerCase(java.util.Locale.ROOT)
                    .contains("windows");
            if (windows && Files.isDirectory(target)) {
                Process process = new ProcessBuilder(
                        "cmd.exe",
                        "/c",
                        "mklink",
                        "/J",
                        link.toString(),
                        target.toString()
                ).redirectErrorStream(true).start();
                String output = new String(
                        process.getInputStream().readAllBytes(),
                        java.nio.charset.StandardCharsets.UTF_8
                );
                if (process.waitFor() == 0 && Files.exists(link)) {
                    return;
                }
                Assumptions.abort(
                        "Symbolic links and junctions are unavailable: " + output.trim()
                );
            }
            Assumptions.abort(
                    "Symbolic links are unavailable: " + symlinkFailure.getMessage()
            );
        }
    }
}
