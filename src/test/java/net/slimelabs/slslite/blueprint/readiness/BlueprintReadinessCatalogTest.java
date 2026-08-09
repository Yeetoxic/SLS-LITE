package net.slimelabs.slslite.blueprint.readiness;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import net.slimelabs.slslite.api.BlueprintReadinessFinding;
import net.slimelabs.slslite.api.BlueprintReadinessStatus;
import net.slimelabs.slslite.blueprint.Blueprint;
import net.slimelabs.slslite.blueprint.BlueprintCopy;
import net.slimelabs.slslite.blueprint.BlueprintVolume;
import net.slimelabs.slslite.config.SLSConfig;
import net.slimelabs.slslite.config.SLSConfigRepository;
import net.slimelabs.slslite.config.StorageStrategy;
import net.slimelabs.slslite.instance.InstanceOperationException;
import net.slimelabs.slslite.process.JavaJarProcessSpecFactory;
import net.slimelabs.slslite.software.SoftwareConfigurator;
import net.slimelabs.slslite.software.SoftwareProfile;
import net.slimelabs.slslite.software.SoftwareReleaseChannel;
import net.slimelabs.slslite.software.SoftwareRuntime;
import net.slimelabs.slslite.software.SoftwareSource;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BlueprintReadinessCatalogTest {

  @TempDir Path root;
  private SLSConfig config;
  private JavaJarProcessSpecFactory processSpecs;

  @BeforeEach
  void setUp() throws Exception {
    SLSConfigRepository repository = new SLSConfigRepository(root);
    repository.initialize();
    config = repository.get();
    processSpecs = new JavaJarProcessSpecFactory(root);
  }

  @Test
  void acceptsACompleteManualBaseAndMappedContent() throws Exception {
    SoftwareProfile profile = profile("manual", SoftwareSource.MANUAL, false, javaExecutable());
    installManualBase(profile, "1.0");
    Files.createDirectories(root.resolve("volumes/worlds/example"));
    Files.createDirectories(root.resolve("volumes/plugins"));
    Files.writeString(root.resolve("volumes/plugins/example.jar"), "jar");
    Blueprint blueprint =
        blueprint(
            "ready",
            List.of(
                new BlueprintVolume(
                    "world", "volumes/worlds/example", "/world", BlueprintVolume.Mode.COW)),
            List.of(new BlueprintCopy("volumes/plugins/example.jar", "plugins/example.jar")));
    BlueprintReadinessCatalog catalog = catalog(Set.of());

    BlueprintReadinessSummary summary = catalog.refresh(List.of(blueprint), List.of(profile));

    assertEquals(new BlueprintReadinessSummary(1, 0, 0), summary);
    catalog.requireReady("ready");
  }

  @Test
  void reportsMissingSourcesAndRejectsNewAdmissionImmediately() {
    SoftwareProfile profile = profile("manual", SoftwareSource.MANUAL, false, javaExecutable());
    Blueprint blueprint =
        blueprint(
            "missing",
            List.of(
                new BlueprintVolume(
                    "world", "volumes/worlds/missing", "/world", BlueprintVolume.Mode.COW)),
            List.of(new BlueprintCopy("volumes/plugins/missing.jar", "plugins/missing.jar")));
    BlueprintReadinessCatalog catalog = catalog(Set.of());

    catalog.refresh(List.of(blueprint), List.of(profile));

    BlueprintReadinessReport report = catalog.get("missing").orElseThrow();
    assertEquals(BlueprintReadinessState.ACTION_NEEDED, report.state());
    assertTrue(report.issues().stream().anyMatch(issue -> issue.code().equals("software-base")));
    assertTrue(report.issues().stream().anyMatch(issue -> issue.code().equals("content-source")));
    assertThrows(InstanceOperationException.class, () -> catalog.requireReady("missing"));
  }

  @Test
  void rejectsSymlinkedContentWithoutFollowingIt() throws Exception {
    SoftwareProfile profile = profile("manual", SoftwareSource.MANUAL, false, javaExecutable());
    installManualBase(profile, "1.0");
    Path outside = Files.createDirectories(root.resolve("outside"));
    Path link = root.resolve("volumes/worlds/linked");
    Files.createDirectories(link.getParent());
    try {
      Files.createSymbolicLink(link, outside);
    } catch (UnsupportedOperationException | java.io.IOException exception) {
      Assumptions.abort("Symbolic links are unavailable: " + exception.getMessage());
    }
    Blueprint blueprint =
        blueprint(
            "linked",
            List.of(
                new BlueprintVolume(
                    "world", "volumes/worlds/linked", "/world", BlueprintVolume.Mode.COW)),
            List.of());
    BlueprintReadinessCatalog catalog = catalog(Set.of());

    catalog.refresh(List.of(blueprint), List.of(profile));

    assertEquals(
        BlueprintReadinessState.ACTION_NEEDED, catalog.get("linked").orElseThrow().state());
  }

  @Test
  void distinguishesEulaActionFromAProviderThatCanInstall() {
    SoftwareProfile gated = profile("paper", SoftwareSource.PAPER, false, javaExecutable());
    SoftwareProfile accepted = profile("paper", SoftwareSource.PAPER, true, javaExecutable());
    Blueprint blueprint = blueprint("provider", List.of(), List.of());
    BlueprintReadinessCatalog catalog = catalog(Set.of(SoftwareSource.PAPER));

    catalog.refresh(List.of(blueprint), List.of(gated));
    assertEquals(
        BlueprintReadinessState.ACTION_NEEDED, catalog.get("provider").orElseThrow().state());

    catalog.refresh(List.of(blueprint), List.of(accepted));
    assertEquals(BlueprintReadinessState.READY, catalog.get("provider").orElseThrow().state());
  }

  @Test
  void treatsAnOptionalUnavailableProviderAsTemporaryWithoutNetworkAccess() {
    SoftwareProfile accepted = profile("paper", SoftwareSource.PAPER, true, javaExecutable());
    Blueprint blueprint = blueprint("provider", List.of(), List.of());
    BlueprintReadinessCatalog catalog = catalog(Set.of());

    catalog.refresh(List.of(blueprint), List.of(accepted));

    assertEquals(
        BlueprintReadinessState.TEMPORARILY_UNAVAILABLE,
        catalog.get("provider").orElseThrow().state());
  }

  @Test
  void reportsTheSelectedMissingJavaRuntimePerBlueprint() throws Exception {
    SoftwareProfile profile =
        profile("manual", SoftwareSource.MANUAL, false, "definitely-not-a-java-runtime");
    installManualBase(profile, "1.0");
    BlueprintReadinessCatalog catalog = catalog(Set.of());

    catalog.refresh(List.of(blueprint("java", List.of(), List.of())), List.of(profile));

    BlueprintReadinessReport report = catalog.get("java").orElseThrow();
    assertEquals(BlueprintReadinessState.ACTION_NEEDED, report.state());
    assertTrue(report.issues().stream().anyMatch(issue -> issue.code().equals("java-runtime")));
  }

  @Test
  void refreshReplacesTheSnapshotAndLargeCatalogInspectionDoesNotTraverseSources()
      throws Exception {
    SoftwareProfile profile = profile("manual", SoftwareSource.MANUAL, false, javaExecutable());
    installManualBase(profile, "1.0");
    Path source = Files.createDirectories(root.resolve("volumes/worlds/large"));
    try {
      Files.createSymbolicLink(source.resolve("unvisited-child"), root.resolve("outside"));
    } catch (UnsupportedOperationException | java.io.IOException exception) {
      Files.writeString(source.resolve("unvisited-child"), "preflight must not inspect children");
    }
    List<Blueprint> many = new ArrayList<>();
    for (int index = 0; index < 300; index++) {
      many.add(
          blueprint(
              "large-" + index,
              List.of(
                  new BlueprintVolume(
                      "world", "volumes/worlds/large", "/world", BlueprintVolume.Mode.COW)),
              List.of()));
    }
    BlueprintReadinessCatalog catalog = catalog(Set.of());

    assertEquals(300, catalog.refresh(many, List.of(profile)).ready());
    assertEquals(0, catalog.refresh(List.of(), List.of(profile)).total());
    assertTrue(catalog.reports().isEmpty());
  }

  @Test
  void mergesExtensionFindingsIntoInspectionAndAdmissionWithoutAffectingSiblings()
      throws Exception {
    SoftwareProfile profile = profile("manual", SoftwareSource.MANUAL, false, javaExecutable());
    installManualBase(profile, "1.0");
    ExtensionBlueprintReadinessRegistry extensions =
        new ExtensionBlueprintReadinessRegistry(org.slf4j.helpers.NOPLogger.NOP_LOGGER);
    Blueprint annotated =
        new Blueprint(
            "extended",
            "extended",
            "test",
            "manual",
            "1.0",
            null,
            null,
            512,
            10,
            1,
            false,
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of("example-plugin", Map.of("database", "primary")),
            List.of(),
            List.of(),
            Map.of(),
            false,
            false);
    Blueprint sibling = blueprint("sibling", List.of(), List.of());
    BlueprintReadinessCatalog catalog =
        new BlueprintReadinessCatalog(
            config, root, processSpecs, Set.of(), StorageStrategy.COPY, extensions);
    var registration =
        extensions.register(
            "example-plugin",
            (view, annotations) ->
                List.of(
                    new BlueprintReadinessFinding(
                        "database",
                        BlueprintReadinessStatus.ACTION_NEEDED,
                        "configure the primary database")));

    BlueprintReadinessSummary summary =
        catalog.refresh(List.of(annotated, sibling), List.of(profile));

    assertEquals(
        new BlueprintReadinessSummary(1, 1, 0),
        summary,
        () -> catalog.get("extended").orElseThrow().toString());
    BlueprintReadinessReport report = catalog.get("extended").orElseThrow();
    assertTrue(
        report.issues().stream()
            .anyMatch(
                issue ->
                    issue.code().equals("extension.example-plugin.database")
                        && issue.message().contains("[example-plugin]")));
    assertThrows(InstanceOperationException.class, () -> catalog.requireReady("extended"));
    catalog.requireReady("sibling");

    registration.close();
    assertEquals(BlueprintReadinessState.READY, catalog.get("extended").orElseThrow().state());
    extensions.close();
  }

  private BlueprintReadinessCatalog catalog(Set<SoftwareSource> providers) {
    return new BlueprintReadinessCatalog(
        config, root, processSpecs, providers, StorageStrategy.COPY);
  }

  private void installManualBase(SoftwareProfile profile, String version) throws Exception {
    Path base = processSpecs.resolveBaseDirectory(profile, version);
    Files.createDirectories(base);
    Files.writeString(base.resolve(profile.serverJar()), "jar");
  }

  private static Blueprint blueprint(
      String id, List<BlueprintVolume> volumes, List<BlueprintCopy> copies) {
    return new Blueprint(
        id,
        id,
        "test",
        id.equals("provider") ? "paper" : "manual",
        "1.0",
        null,
        null,
        512,
        10,
        1,
        false,
        Map.of(),
        Map.of(),
        Map.of(),
        Map.of(),
        volumes,
        copies,
        Map.of(),
        false,
        false);
  }

  private static SoftwareProfile profile(
      String id, SoftwareSource source, boolean acceptEula, String javaExecutable) {
    return new SoftwareProfile(
        id,
        id,
        SoftwareRuntime.JAVA_JAR,
        SoftwareConfigurator.PAPER,
        source,
        SoftwareReleaseChannel.STABLE,
        acceptEula,
        javaExecutable,
        Map.of(),
        "software/" + id + "/{version}",
        "server.jar",
        List.of(),
        List.of(),
        Map.of(),
        "Done",
        30,
        "stop",
        10,
        512,
        Map.of(),
        List.of(),
        null,
        Map.of());
  }

  private static String javaExecutable() {
    String executable =
        System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
    return Path.of(System.getProperty("java.home"), "bin", executable).toString();
  }
}
