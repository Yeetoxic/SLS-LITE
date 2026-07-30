package net.slimelabs.slslite.instance.storage;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class OverlayFsLayerManagerTest {

    @TempDir
    Path temporaryDirectory;

    private Path content;
    private Path instances;
    private Path instance;
    private Path firstLower;
    private Path secondLower;

    @BeforeEach
    void setUp() throws Exception {
        content = Files.createDirectory(temporaryDirectory.resolve("content"));
        instances = Files.createDirectory(temporaryDirectory.resolve("instances"));
        instance = Files.createDirectory(instances.resolve("game.abc123"));
        firstLower = Files.createDirectories(content.resolve("worlds/first"));
        secondLower = Files.createDirectories(content.resolve("worlds/second"));
    }

    @Test
    void preparesSuspendsAndResumesFromDurableManifest() throws Exception {
        FakeMountAdapter mounts = new FakeMountAdapter();
        OverlayFsLayerManager manager = manager(mounts);

        manager.prepare(instance, List.of(layer(
                Path.of("world"),
                firstLower,
                secondLower
        )));

        Path target = instance.resolve("world");
        assertTrue(mounts.mounted.contains(target));
        assertEquals(2, mounts.lowerOrder.get(0).size());
        assertTrue(Files.isSameFile(
                firstLower,
                mounts.lowerOrder.get(0).get(0)
        ));
        assertTrue(Files.isSameFile(
                secondLower,
                mounts.lowerOrder.get(0).get(1)
        ));
        assertTrue(Files.isRegularFile(
                instance.resolve(OverlayFsLayerManager.MANIFEST_FILE)
        ));

        manager.suspend(instance);
        assertFalse(mounts.mounted.contains(target));

        OverlayFsLayerManager recovered = manager(mounts);
        recovered.resume(instance);
        assertTrue(mounts.mounted.contains(target));
    }

    @Test
    void refusesToResumeWithDifferentMountImplementation() throws Exception {
        FakeMountAdapter kernelMounts = new FakeMountAdapter();
        OverlayFsLayerManager manager = manager(kernelMounts);
        manager.prepare(instance, List.of(layer(Path.of("world"), firstLower)));
        manager.suspend(instance);

        FakeMountAdapter fuseMounts = new FakeMountAdapter();
        fuseMounts.storageType = "fuse-overlay";
        IOException exception = assertThrows(
                IOException.class,
                () -> manager(fuseMounts).resume(instance)
        );

        assertTrue(exception.getMessage().contains(
                "requires mount adapter overlay"
        ));
        assertTrue(fuseMounts.mounted.isEmpty());
    }

    @Test
    void suspendIsIdempotent() throws Exception {
        FakeMountAdapter mounts = new FakeMountAdapter();
        OverlayFsLayerManager manager = manager(mounts);
        manager.prepare(instance, List.of(layer(
                Path.of("world"),
                firstLower
        )));

        manager.suspend(instance);
        manager.suspend(instance);

        assertTrue(mounts.mounted.isEmpty());
    }

    @Test
    void rollsBackEarlierMountWhenLaterMountFails() throws Exception {
        FakeMountAdapter mounts = new FakeMountAdapter();
        mounts.failMountNumber = 2;
        OverlayFsLayerManager manager = manager(mounts);

        assertThrows(IOException.class, () -> manager.prepare(instance, List.of(
                layer(Path.of("world"), firstLower),
                layer(Path.of("world_nether"), secondLower)
        )));

        assertTrue(mounts.mounted.isEmpty());
        assertTrue(manager.hasManifest(instance));
    }

    @Test
    void rollsBackMountThatSucceedsBeforeHelperReportsFailure()
            throws Exception {
        FakeMountAdapter mounts = new FakeMountAdapter();
        mounts.mountThenFailNumber = 1;
        OverlayFsLayerManager manager = manager(mounts);

        assertThrows(IOException.class, () -> manager.prepare(instance, List.of(
                layer(Path.of("world"), firstLower)
        )));

        assertTrue(mounts.mounted.isEmpty());
        assertTrue(manager.hasManifest(instance));
    }

    @Test
    void preservesManifestWhenUnmountFails() throws Exception {
        FakeMountAdapter mounts = new FakeMountAdapter();
        OverlayFsLayerManager manager = manager(mounts);
        manager.prepare(instance, List.of(layer(
                Path.of("world"),
                firstLower
        )));
        mounts.failUnmount = true;

        assertThrows(IOException.class, () -> manager.suspend(instance));

        assertTrue(manager.hasManifest(instance));
        assertTrue(mounts.mounted.contains(instance.resolve("world")));
    }

    @Test
    void rejectsEscapingAndOverlappingTargets() {
        OverlayFsLayerManager manager = manager(new FakeMountAdapter());

        assertThrows(IOException.class, () -> manager.prepare(instance, List.of(
                layer(Path.of("../outside"), firstLower)
        )));
        assertThrows(IOException.class, () -> manager.prepare(instance, List.of(
                layer(Path.of("world"), firstLower),
                layer(Path.of("world/nether"), secondLower)
        )));
    }

    @Test
    void rejectsLayerWithoutLowerDirectory() {
        assertThrows(
                IllegalArgumentException.class,
                () -> new OverlayFsLayerManager.Layer(
                        Path.of("world"),
                        List.of()
                )
        );
    }

    @Test
    void rejectsLowerDirectoryOutsideManagedContent() throws Exception {
        Path outside = Files.createDirectory(temporaryDirectory.resolve("outside"));
        OverlayFsLayerManager manager = manager(new FakeMountAdapter());

        assertThrows(IOException.class, () -> manager.prepare(instance, List.of(
                layer(Path.of("world"), outside)
        )));
    }

    @Test
    void refusesUnexpectedExistingMountDuringResume() throws Exception {
        FakeMountAdapter mounts = new FakeMountAdapter();
        OverlayFsLayerManager manager = manager(mounts);
        manager.prepare(instance, List.of(layer(
                Path.of("world"),
                firstLower
        )));

        assertThrows(IOException.class, () -> manager.resume(instance));
    }

    @Test
    void rejectsOverlappingTargetsAddedToDurableManifest() throws Exception {
        FakeMountAdapter mounts = new FakeMountAdapter();
        OverlayFsLayerManager manager = manager(mounts);
        manager.prepare(instance, List.of(
                layer(Path.of("world"), firstLower),
                layer(Path.of("world_nether"), secondLower)
        ));
        manager.suspend(instance);

        Path manifest = instance.resolve(OverlayFsLayerManager.MANIFEST_FILE);
        Properties properties = new Properties();
        try (InputStream input = Files.newInputStream(manifest)) {
            properties.load(input);
        }
        properties.setProperty("layer.1.target", "world/nether");
        try (OutputStream output = Files.newOutputStream(manifest)) {
            properties.store(output, "tampered for test");
        }

        assertThrows(IOException.class, () -> manager.resume(instance));
        assertTrue(mounts.mounted.isEmpty());
    }

    @Test
    void decodesEscapedMountInfoPaths() {
        assertEquals(
                Path.of("/storage/with space/back\\slash").toAbsolutePath(),
                OverlayFsMountOperations.decodeMountInfoPath(
                        "/storage/with\\040space/back\\134slash"
                )
        );
    }

    private OverlayFsLayerManager manager(FakeMountAdapter mounts) {
        return new OverlayFsLayerManager(instances, content, mounts);
    }

    private static OverlayFsLayerManager.Layer layer(
            Path target,
            Path... lowers
    ) {
        return new OverlayFsLayerManager.Layer(target, List.of(lowers));
    }

    private static final class FakeMountAdapter
            implements OverlayFsLayerManager.MountAdapter {

        private final Set<Path> mounted = new HashSet<>();
        private final List<List<Path>> lowerOrder = new ArrayList<>();
        private int mountCalls;
        private int failMountNumber = -1;
        private int mountThenFailNumber = -1;
        private boolean failUnmount;
        private String storageType = "overlay";

        @Override
        public String storageType() {
            return storageType;
        }

        @Override
        public void mount(
                List<Path> lowerDirectories,
                Path upperDirectory,
                Path workDirectory,
                Path target
        ) throws IOException {
            mountCalls++;
            if (mountCalls == failMountNumber) {
                throw new IOException("simulated mount failure");
            }
            lowerOrder.add(List.copyOf(lowerDirectories));
            mounted.add(target);
            if (mountCalls == mountThenFailNumber) {
                throw new IOException("simulated post-mount verification failure");
            }
        }

        @Override
        public void unmount(
                Path target,
                Path upperDirectory,
                Path workDirectory
        ) throws IOException {
            if (failUnmount && mounted.contains(target)) {
                throw new IOException("simulated unmount failure");
            }
            mounted.remove(target);
        }

        @Override
        public boolean isMounted(Path target) {
            return mounted.contains(target);
        }

        @Override
        public List<Path> mountPointsBeneath(Path root) {
            return mounted.stream()
                    .filter(path -> path.equals(root) || path.startsWith(root))
                    .toList();
        }
    }
}
