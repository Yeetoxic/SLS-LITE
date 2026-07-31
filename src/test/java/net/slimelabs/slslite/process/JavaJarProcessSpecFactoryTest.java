package net.slimelabs.slslite.process;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.slimelabs.slslite.blueprint.Blueprint;
import net.slimelabs.slslite.software.SoftwareProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class JavaJarProcessSpecFactoryTest {

  @TempDir Path temporaryDirectory;

  @Test
  void buildsShellFreePaperCommandWithExpandedArguments() throws Exception {
    JavaJarProcessSpecFactory factory = new JavaJarProcessSpecFactory(temporaryDirectory);
    Path instanceDirectory = temporaryDirectory.resolve("instances/game-abc123");

    ProcessSpec spec =
        factory.create(
            profile("paper.jar"), blueprint("26.1"), "game-abc123", instanceDirectory, 25571);

    assertEquals("java", spec.command().get(0));
    assertTrue(spec.command().contains("-Xmx1536M"));
    assertTrue(spec.command().contains("-Dsls.port=25571"));
    assertTrue(spec.command().contains("-jar"));
    assertTrue(
        spec.command()
            .contains(
                instanceDirectory.resolve("paper.jar").toAbsolutePath().normalize().toString()));
    assertEquals(instanceDirectory.toAbsolutePath().normalize(), spec.workingDirectory());
  }

  @Test
  void resolvesVersionedSoftwareDirectoryUnderDataDirectory() throws Exception {
    JavaJarProcessSpecFactory factory = new JavaJarProcessSpecFactory(temporaryDirectory);

    Path result = factory.resolveBaseDirectory(profile("paper.jar"), "26.1");

    assertEquals(
        temporaryDirectory.resolve("software/paper/26.1").toAbsolutePath().normalize(), result);
  }

  @Test
  void resolvesSourceAndChannelSoftwareDirectoryPlaceholders() throws Exception {
    JavaJarProcessSpecFactory factory = new JavaJarProcessSpecFactory(temporaryDirectory);
    SoftwareProfile profile =
        new SoftwareProfile(
            "paper",
            net.slimelabs.slslite.software.SoftwareRuntime.JAVA_JAR,
            net.slimelabs.slslite.software.SoftwareConfigurator.PAPER,
            net.slimelabs.slslite.software.SoftwareSource.PAPER,
            net.slimelabs.slslite.software.SoftwareReleaseChannel.BETA,
            true,
            "java",
            Map.of(),
            "software/{source}/{channel}/{version}",
            "paper.jar",
            List.of(),
            List.of(),
            "Done",
            180,
            "stop",
            30);

    assertEquals(
        temporaryDirectory.resolve("software/paper/beta/26.2").toAbsolutePath().normalize(),
        factory.resolveBaseDirectory(profile, "26.2"));
  }

  @Test
  void mapsBlueprintJavaImageToConfiguredLocalRuntime() throws Exception {
    JavaJarProcessSpecFactory factory = new JavaJarProcessSpecFactory(temporaryDirectory);
    SoftwareProfile profile =
        new SoftwareProfile(
            "paper",
            net.slimelabs.slslite.software.SoftwareRuntime.JAVA_JAR,
            net.slimelabs.slslite.software.SoftwareConfigurator.PAPER,
            net.slimelabs.slslite.software.SoftwareSource.MANUAL,
            net.slimelabs.slslite.software.SoftwareReleaseChannel.STABLE,
            false,
            "java",
            Map.of(8, "runtimes/java-8/bin/java"),
            "software/paper/{version}",
            "paper.jar",
            List.of("-Xmx{memory_mib}M"),
            List.of(),
            "Done",
            180,
            "stop",
            30);
    Blueprint blueprint = blueprint("1.14.4", "java_8");

    ProcessSpec spec =
        factory.create(
            profile,
            blueprint,
            "game-abc123",
            temporaryDirectory.resolve("instances/game-abc123"),
            25571);

    assertEquals(
        temporaryDirectory
            .resolve("runtimes/java-8/bin/java")
            .toAbsolutePath()
            .normalize()
            .toString(),
        spec.command().getFirst());
  }

  @Test
  void rejectsUnmappedJavaImageAndUnsafeSoftwareOverridePath() {
    JavaJarProcessSpecFactory factory = new JavaJarProcessSpecFactory(temporaryDirectory);

    ProcessSpecificationException imageFailure =
        assertThrows(
            ProcessSpecificationException.class,
            () ->
                factory.create(
                    profile("paper.jar"),
                    blueprint("1.14.4", "java_8"),
                    "game-abc123",
                    temporaryDirectory.resolve("instances/game-abc123"),
                    25571));
    assertTrue(imageFailure.getMessage().contains("launch.java_versions"));
    assertThrows(
        ProcessSpecificationException.class,
        () -> factory.resolveSoftwareOverridePath("../outside"));
  }

  @Test
  void rejectsUnsafeVersionAndJarTraversal() {
    JavaJarProcessSpecFactory factory = new JavaJarProcessSpecFactory(temporaryDirectory);
    Path instanceDirectory = temporaryDirectory.resolve("instances/game-abc123");

    assertThrows(
        ProcessSpecificationException.class,
        () -> factory.resolveBaseDirectory(profile("paper.jar"), "../outside"));
    assertThrows(
        ProcessSpecificationException.class,
        () ->
            factory.create(
                profile("../outside.jar"),
                blueprint("26.1"),
                "game-abc123",
                instanceDirectory,
                25571));
  }

  @Test
  void rejectsUnknownArgumentPlaceholder() {
    JavaJarProcessSpecFactory factory = new JavaJarProcessSpecFactory(temporaryDirectory);
    SoftwareProfile profile =
        new SoftwareProfile(
            "paper",
            "java",
            "software/paper/{version}",
            "paper.jar",
            List.of("-Xmx{unknown}M"),
            List.of("--nogui"),
            "Done",
            180,
            "stop",
            30);

    assertThrows(
        ProcessSpecificationException.class,
        () ->
            factory.create(
                profile,
                blueprint("26.1"),
                "game-abc123",
                temporaryDirectory.resolve("instances/game-abc123"),
                25571));
  }

  private static SoftwareProfile profile(String serverJar) {
    return new SoftwareProfile(
        "paper",
        "java",
        "software/paper/{version}",
        serverJar,
        List.of("-Xms{memory_mib}M", "-Xmx{memory_mib}M", "-Dsls.port={port}"),
        List.of("--nogui"),
        "Done",
        180,
        "stop",
        30);
  }

  private static Blueprint blueprint(String version) {
    return new Blueprint("game", "Game", "game", "paper", version, 1536, false, Map.of());
  }

  private static Blueprint blueprint(String version, String image) {
    return new Blueprint(
        "game", "Game", "game", "paper", version, image, null, 1536, 20, 1, false, Map.of(),
        Map.of(), List.of());
  }

  @Test
  void includesValidatedBlueprintEnvironmentInProcessSpec() throws Exception {
    Blueprint base = blueprint("1.21.11");
    Blueprint configured =
        new Blueprint(
            base.id(),
            base.name(),
            base.type(),
            base.software(),
            base.version(),
            base.image(),
            base.softwarePath(),
            base.memoryLimitMiB(),
            base.maxPlayers(),
            base.maxInstances(),
            base.save(),
            base.serverProperties(),
            base.yamlConfigs(),
            base.textFileConfigs(),
            base.annotations(),
            base.volumes(),
            base.copies(),
            Map.of("FEATURE_FLAG", "enabled"));
    JavaJarProcessSpecFactory factory = new JavaJarProcessSpecFactory(temporaryDirectory);

    ProcessSpec spec =
        factory.create(
            profile("paper.jar"),
            configured,
            "game.abc123",
            temporaryDirectory.resolve("instances/game.abc123"),
            25571);

    assertEquals(Map.of("FEATURE_FLAG", "enabled"), spec.environment());
  }
}
