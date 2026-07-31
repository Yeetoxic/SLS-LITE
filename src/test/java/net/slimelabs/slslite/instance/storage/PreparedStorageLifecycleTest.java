package net.slimelabs.slslite.instance.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PreparedStorageLifecycleTest {

    @TempDir
    Path temporaryDirectory;

    private Path instancesRoot;
    private Path instance;
    private FakeMountAdapter mounts;
    private PreparedStorageLifecycle lifecycle;

    @BeforeEach
    void setUp() throws Exception {
        instancesRoot = temporaryDirectory.resolve("instances");
        Path contentRoot = temporaryDirectory.resolve("content");
        instance = instancesRoot.resolve("game.abc123");
        Files.createDirectories(instance);
        Files.createDirectories(contentRoot);
        mounts = new FakeMountAdapter();
        lifecycle = new PreparedStorageLifecycle(
                new OverlayFsLayerManager(
                        instancesRoot,
                        contentRoot,
                        mounts
                ),
                new BtrfsSnapshotManager(instancesRoot, contentRoot),
                null
        );
    }

    @Test
    void deletesPlainDirectoryTree() throws Exception {
        Path nested = instance.resolve("world/region");
        Files.createDirectories(nested);
        Files.writeString(nested.resolve("r.0.0.mca"), "region");

        lifecycle.delete(instance);

        assertFalse(Files.exists(instance));
    }

    @Test
    void missingDirectoryCanBeDeletedRepeatedly() {
        Path missing = instancesRoot.resolve("missing.abc123");

        assertDoesNotThrow(() -> lifecycle.delete(missing));
        assertDoesNotThrow(() -> lifecycle.delete(missing));
    }

    @Test
    void snapshotManifestRequiresConfiguredHelperBeforeDeletion()
            throws Exception {
        Files.writeString(
                instance.resolve(SnapshotHookLayerManager.MANIFEST_FILE),
                "provider=test"
        );

        IOException failure = assertThrows(
                IOException.class,
                () -> lifecycle.delete(instance)
        );

        assertTrue(failure.getMessage().contains(
                "restore that configured helper"
        ));
        assertTrue(Files.exists(instance));
    }

    @Test
    void unexpectedMountPreventsPlainDirectoryDeletion() {
        mounts.mountPoints.add(instance.resolve("world"));

        IOException failure = assertThrows(
                IOException.class,
                () -> lifecycle.delete(instance)
        );

        assertTrue(failure.getMessage().contains("mount remains"));
        assertTrue(Files.exists(instance));
    }

    private static final class FakeMountAdapter
            implements OverlayFsLayerManager.MountAdapter {

        private final List<Path> mountPoints = new ArrayList<>();

        @Override
        public void mount(
                List<Path> lowerDirectories,
                Path upperDirectory,
                Path workDirectory,
                Path target
        ) {
        }

        @Override
        public void unmount(
                Path target,
                Path upperDirectory,
                Path workDirectory
        ) {
        }

        @Override
        public boolean isMounted(Path target) {
            return mountPoints.contains(target);
        }

        @Override
        public List<Path> mountPointsBeneath(Path root) {
            return mountPoints.stream()
                    .filter(path -> path.startsWith(root))
                    .toList();
        }
    }
}
