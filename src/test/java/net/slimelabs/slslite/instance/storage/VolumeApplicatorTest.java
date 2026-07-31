package net.slimelabs.slslite.instance.storage;

import net.slimelabs.slslite.blueprint.BlueprintVolume;
import net.slimelabs.slslite.config.StorageStrategy;
import net.slimelabs.slslite.instance.InstancePreparationException;
import net.slimelabs.slslite.instance.storage.BlueprintContentResolver.ResolvedVolume;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VolumeApplicatorTest {

    @TempDir
    Path temporaryDirectory;

    private Path destination;
    private VolumeApplicator applicator;

    @BeforeEach
    void setUp() throws Exception {
        Path instancesRoot = temporaryDirectory.resolve("instances");
        Path contentRoot = temporaryDirectory.resolve("content");
        destination = instancesRoot.resolve("game.abc123");
        Files.createDirectories(destination);
        Files.createDirectories(contentRoot);
        applicator = new VolumeApplicator(
                StorageStrategy.COPY,
                new DirectoryCopyEngine(Files::copy, Thread::sleep, 1),
                new OverlayFsLayerManager(instancesRoot, contentRoot),
                new BtrfsSnapshotManager(instancesRoot, contentRoot),
                null,
                false
        );
    }

    @Test
    void portableVolumesAtSameTargetMergeWithFirstSourcePrecedence()
            throws Exception {
        Path first = temporaryDirectory.resolve("first");
        Path second = temporaryDirectory.resolve("second");
        Files.createDirectories(first);
        Files.createDirectories(second);
        Files.writeString(first.resolve("shared.txt"), "first");
        Files.writeString(first.resolve("first.txt"), "first");
        Files.writeString(second.resolve("shared.txt"), "second");
        Files.writeString(second.resolve("second.txt"), "second");
        Path target = destination.resolve("plugins");

        applicator.apply(
                destination,
                List.of(
                        resolved("first", first, target),
                        resolved("second", second, target)
                ),
                () -> false
        );

        assertEquals("first", Files.readString(target.resolve("shared.txt")));
        assertEquals("first", Files.readString(target.resolve("first.txt")));
        assertEquals("second", Files.readString(target.resolve("second.txt")));
    }

    @Test
    void rejectsInitialTargetCollision() throws Exception {
        Path source = temporaryDirectory.resolve("source");
        Path target = destination.resolve("world");
        Files.createDirectories(source);
        Files.createDirectories(target);

        InstancePreparationException failure = assertThrows(
                InstancePreparationException.class,
                () -> applicator.apply(
                        destination,
                        List.of(resolved("world", source, target)),
                        () -> false
                )
        );

        assertTrue(failure.getMessage().contains(
                "Volume target collides with existing instance content"
        ));
    }

    @Test
    void cancellationStopsBeforeTargetCreation() throws Exception {
        Path source = temporaryDirectory.resolve("source");
        Path target = destination.resolve("world");
        Files.createDirectories(source);
        Files.writeString(source.resolve("level.dat"), "world");

        IOException failure = assertThrows(
                IOException.class,
                () -> applicator.apply(
                        destination,
                        List.of(resolved("world", source, target)),
                        () -> true
                )
        );

        assertTrue(failure.getMessage().contains("cancelled"));
        assertFalse(Files.exists(target));
    }

    private static ResolvedVolume resolved(
            String name,
            Path source,
            Path target
    ) {
        return new ResolvedVolume(
                new BlueprintVolume(
                        name,
                        source.getFileName().toString(),
                        "/" + target.getFileName(),
                        BlueprintVolume.Mode.COW
                ),
                source,
                target
        );
    }
}
