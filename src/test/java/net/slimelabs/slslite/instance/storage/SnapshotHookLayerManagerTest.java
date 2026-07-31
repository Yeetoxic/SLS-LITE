package net.slimelabs.slslite.instance.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import net.slimelabs.slslite.blueprint.BlueprintVolume;
import net.slimelabs.slslite.config.StorageStrategy;
import net.slimelabs.slslite.instance.InstancePreparationException;
import net.slimelabs.slslite.instance.metadata.InstanceMetadataStore;
import net.slimelabs.slslite.instance.model.InstanceMetadata;
import net.slimelabs.slslite.instance.model.InstanceState;
import net.slimelabs.slslite.instance.reconcile.InstanceReconciler;
import net.slimelabs.slslite.instance.reconcile.InstanceReconciliationReport;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class SnapshotHookLayerManagerTest {

  @TempDir Path temporaryDirectory;

  @Test
  void preparesSuspendsResumesAndDeletesProviderLayer() throws Exception {
    Fixture fixture = fixture();

    fixture.manager.prepare(fixture.instance, fixture.source, Path.of("world"));
    Path target = fixture.instance.resolve("world");
    Files.writeString(target.resolve("level.dat"), "private");
    fixture.manager.suspend(fixture.instance);

    assertFalse(Files.exists(target));
    assertEquals("source", Files.readString(fixture.source.resolve("level.dat")));

    fixture.manager.resume(fixture.instance);
    assertEquals("private", Files.readString(target.resolve("level.dat")));

    fixture.manager.delete(fixture.instance);
    assertFalse(Files.exists(target));
    assertFalse(fixture.manager.hasManifest(fixture.instance));
  }

  @Test
  void failedPrepareCleansProviderStateAndManifest() throws Exception {
    Fixture fixture = fixture();
    fixture.hook.failPrepare = true;

    assertThrows(
        IOException.class,
        () -> fixture.manager.prepare(fixture.instance, fixture.source, Path.of("world")));

    assertFalse(Files.exists(fixture.instance.resolve("world")));
    assertFalse(fixture.manager.hasManifest(fixture.instance));
  }

  @Test
  void rejectsPersistentSnapshotWhenHelperIsNoLongerConfigured() throws Exception {
    Fixture fixture = fixture();
    fixture.manager.prepare(fixture.instance, fixture.source, Path.of("world"));
    fixture.manager.suspend(fixture.instance);
    InstanceDirectoryPreparer copyPreparer =
        new InstanceDirectoryPreparer(fixture.instances, fixture.content);

    InstancePreparationException exception =
        assertThrows(
            InstancePreparationException.class,
            () -> copyPreparer.resume(fixture.instance.getFileName().toString()));
    assertTrue(exception.getCause().getMessage().contains("prepared with snapshot-hook"));

    InstancePreparationException deleteException =
        assertThrows(
            InstancePreparationException.class,
            () -> copyPreparer.delete(fixture.instance.getFileName().toString()));
    assertTrue(deleteException.getCause().getMessage().contains("prepared with snapshot-hook"));
    assertTrue(Files.isDirectory(fixture.instance));
    assertTrue(SnapshotHookLayerManager.manifestExists(fixture.instance));
  }

  @Test
  void preparerRoutesCowVolumeThroughExplicitSnapshotHook() throws Exception {
    Fixture fixture = fixture();
    Path software = Files.createDirectories(fixture.content.resolve("software"));
    Files.writeString(software.resolve("server.jar"), "server");
    InstanceDirectoryPreparer preparer =
        new InstanceDirectoryPreparer(
            fixture.instances,
            fixture.content,
            new PortableFileCopyOperation(),
            Thread::sleep,
            StorageStrategy.SNAPSHOT_HOOK,
            new OverlayFsLayerManager(fixture.instances, fixture.content),
            new BtrfsSnapshotManager(fixture.instances, fixture.content),
            false,
            2,
            fixture.manager);

    Path prepared =
        preparer.prepare(
            "game.z82odk",
            software,
            List.of(
                new BlueprintVolume("world", "worlds/source", "/world", BlueprintVolume.Mode.COW)));
    Files.writeString(prepared.resolve("world/level.dat"), "instance");

    assertEquals("source", Files.readString(fixture.source.resolve("level.dat")));
    preparer.delete("game.z82odk");
    assertFalse(Files.exists(prepared));
  }

  @Test
  void preparerRejectsSymbolicLinksInsideSnapshotHookCowSource() throws Exception {
    Fixture fixture = fixture();
    Path software = Files.createDirectories(fixture.content.resolve("software"));
    Files.writeString(software.resolve("server.jar"), "server");
    Path outside = Files.createDirectories(temporaryDirectory.resolve("outside"));
    createSymbolicLinkOrSkip(fixture.source.resolve("escape"), outside);
    InstanceDirectoryPreparer preparer =
        new InstanceDirectoryPreparer(
            fixture.instances,
            fixture.content,
            new PortableFileCopyOperation(),
            Thread::sleep,
            StorageStrategy.SNAPSHOT_HOOK,
            new OverlayFsLayerManager(fixture.instances, fixture.content),
            new BtrfsSnapshotManager(fixture.instances, fixture.content),
            false,
            2,
            fixture.manager);

    InstancePreparationException failure =
        assertThrows(
            InstancePreparationException.class,
            () ->
                preparer.prepare(
                    "game.z82odk",
                    software,
                    List.of(
                        new BlueprintVolume(
                            "world", "worlds/source", "/world", BlueprintVolume.Mode.COW))));

    assertTrue(failure.getMessage().contains("Symbolic links"));
    assertFalse(Files.exists(fixture.instances.resolve("game.z82odk")));
  }

  @Test
  void reconciliationDeletesStaleEphemeralProviderLayer() throws Exception {
    Fixture fixture = fixture();
    Path software = Files.createDirectories(fixture.content.resolve("software"));
    Files.writeString(software.resolve("server.jar"), "server");
    InstanceDirectoryPreparer preparer =
        new InstanceDirectoryPreparer(
            fixture.instances,
            fixture.content,
            new PortableFileCopyOperation(),
            Thread::sleep,
            StorageStrategy.SNAPSHOT_HOOK,
            new OverlayFsLayerManager(fixture.instances, fixture.content),
            new BtrfsSnapshotManager(fixture.instances, fixture.content),
            false,
            1,
            fixture.manager);
    Path prepared =
        preparer.prepare(
            "game.z82odk",
            software,
            List.of(
                new BlueprintVolume("world", "worlds/source", "/world", BlueprintVolume.Mode.COW)));
    new InstanceMetadataStore(fixture.instances)
        .write(
            prepared,
            new InstanceMetadata(
                "game.z82odk",
                "game",
                false,
                InstanceState.PREPARING,
                Instant.parse("2026-07-29T12:00:00Z"),
                null,
                null));

    InstanceReconciliationReport report =
        new InstanceReconciler(preparer, LoggerFactory.getLogger(getClass())).reconcile();

    assertEquals(1, report.removedEphemeral());
    assertEquals(0, report.failures());
    assertFalse(Files.exists(prepared));
  }

  private Fixture fixture() throws Exception {
    Path content = temporaryDirectory.resolve("content");
    Path instances = content.resolve("instances");
    Path instance = instances.resolve("game.x82odk");
    Path source = content.resolve("worlds/source");
    Path providerState = temporaryDirectory.resolve("provider-state");
    Files.createDirectories(instance);
    Files.createDirectories(source);
    Files.createDirectories(providerState);
    Files.writeString(source.resolve("level.dat"), "source");
    FakeHook hook = new FakeHook(providerState);
    return new Fixture(
        content,
        instances,
        instance,
        source,
        hook,
        new SnapshotHookLayerManager(instances, content, hook));
  }

  private static void createSymbolicLinkOrSkip(Path link, Path target) throws Exception {
    try {
      Files.createSymbolicLink(link, target);
    } catch (UnsupportedOperationException | IOException exception) {
      Assumptions.abort("Symbolic links are unavailable: " + exception.getMessage());
    }
  }

  private record Fixture(
      Path content,
      Path instances,
      Path instance,
      Path source,
      FakeHook hook,
      SnapshotHookLayerManager manager) {}

  private static final class FakeHook implements SnapshotHookLayerManager.HookAdapter {

    private final Path providerState;
    private final Map<Path, Path> suspended = new HashMap<>();
    private boolean failPrepare;

    private FakeHook(Path providerState) {
      this.providerState = providerState;
    }

    @Override
    public void prepare(Path source, Path target) throws IOException {
      copyTree(source, target);
      if (failPrepare) {
        throw new IOException("intentional provider failure");
      }
    }

    @Override
    public void suspend(Path source, Path target) throws IOException {
      if (!Files.exists(target)) {
        return;
      }
      Path state = providerState.resolve(Integer.toHexString(target.toString().hashCode()));
      Files.move(target, state);
      suspended.put(target, state);
    }

    @Override
    public void resume(Path source, Path target) throws IOException {
      Path state = suspended.remove(target);
      if (state != null) {
        Files.move(state, target);
      }
    }

    @Override
    public void delete(Path source, Path target) throws IOException {
      Path state = suspended.remove(target);
      if (state != null && Files.exists(state)) {
        deleteTree(state);
      }
      if (Files.exists(target)) {
        deleteTree(target);
      }
    }

    private static void copyTree(Path source, Path target) throws IOException {
      try (var paths = Files.walk(source)) {
        for (Path path : paths.toList()) {
          Path destination = target.resolve(source.relativize(path));
          if (Files.isDirectory(path)) {
            Files.createDirectories(destination);
          } else {
            Files.copy(path, destination);
          }
        }
      }
    }

    private static void deleteTree(Path root) throws IOException {
      try (var paths = Files.walk(root)) {
        for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
          Files.delete(path);
        }
      }
    }
  }
}
