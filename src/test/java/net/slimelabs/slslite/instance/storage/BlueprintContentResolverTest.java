package net.slimelabs.slslite.instance.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import net.slimelabs.slslite.blueprint.BlueprintCopy;
import net.slimelabs.slslite.blueprint.BlueprintVolume;
import net.slimelabs.slslite.instance.InstancePreparationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BlueprintContentResolverTest {

  @TempDir Path temporaryDirectory;

  private Path contentRoot;
  private Path destination;
  private BlueprintContentResolver resolver;

  @BeforeEach
  void setUp() throws Exception {
    contentRoot = temporaryDirectory.resolve("content");
    Path instancesRoot = temporaryDirectory.resolve("instances");
    destination = instancesRoot.resolve("game.abc123");
    Files.createDirectories(contentRoot);
    resolver = new BlueprintContentResolver(instancesRoot, contentRoot);
  }

  @Test
  void resolvesContainedVolumeAndCopyTargets() throws Exception {
    Path world = contentRoot.resolve("worlds/lobby");
    Path settings = contentRoot.resolve("state/settings.yml");
    Files.createDirectories(world);
    Files.createDirectories(settings.getParent());
    Files.writeString(world.resolve("level.dat"), "world");
    Files.writeString(settings, "settings");

    var volumes =
        resolver.resolveVolumes(
            List.of(
                new BlueprintVolume("world", "worlds/lobby", "/world", BlueprintVolume.Mode.COW)),
            destination);
    var copies =
        resolver.resolveCopies(
            List.of(new BlueprintCopy("state/settings.yml", "config/settings.yml")), destination);

    assertEquals(world.toRealPath(), volumes.getFirst().source());
    assertEquals(destination.resolve("world"), volumes.getFirst().target());
    assertEquals(settings.toRealPath(), copies.getFirst().source());
    assertEquals(destination.resolve("config/settings.yml"), copies.getFirst().target());
  }

  @Test
  void rejectsOverlappingDistinctVolumeTargets() throws Exception {
    Files.createDirectories(contentRoot.resolve("worlds/one"));
    Files.createDirectories(contentRoot.resolve("worlds/two"));

    InstancePreparationException failure =
        assertThrows(
            InstancePreparationException.class,
            () ->
                resolver.resolveVolumes(
                    List.of(
                        new BlueprintVolume(
                            "one", "worlds/one", "/world", BlueprintVolume.Mode.COW),
                        new BlueprintVolume(
                            "two", "worlds/two", "/world/data", BlueprintVolume.Mode.COW)),
                    destination));

    assertTrue(failure.getMessage().contains("Volume targets overlap"));
  }

  @Test
  void rejectsCopyTargetTraversal() throws Exception {
    Path source = contentRoot.resolve("state/settings.yml");
    Files.createDirectories(source.getParent());
    Files.writeString(source, "settings");

    InstancePreparationException failure =
        assertThrows(
            InstancePreparationException.class,
            () ->
                resolver.resolveCopies(
                    List.of(new BlueprintCopy("state/settings.yml", "../outside.yml")),
                    destination));

    assertTrue(failure.getMessage().contains("Copy target must stay inside the instance"));
  }

  @Test
  void rejectsSharedWritableVolumeMode() throws Exception {
    Files.createDirectories(contentRoot.resolve("shared"));

    InstancePreparationException failure =
        assertThrows(
            InstancePreparationException.class,
            () ->
                resolver.resolveVolumes(
                    List.of(
                        new BlueprintVolume(
                            "shared", "shared", "/shared", BlueprintVolume.Mode.RW)),
                    destination));

    assertTrue(failure.getMessage().contains("uses mode rw"));
  }
}
