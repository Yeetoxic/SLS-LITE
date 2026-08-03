package net.slimelabs.slslite.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.atomic.AtomicBoolean;
import net.slimelabs.slslite.blueprint.BlueprintRepository;
import net.slimelabs.slslite.software.SoftwareProfileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DefinitionReloaderTest {

  @TempDir Path temporaryDirectory;

  @Test
  void leavesBothRepositoriesUntouchedWhenCandidatesAreIncompatible() throws Exception {
    Repositories repositories = repositories();
    writeProfile(repositories.profilesPath(), "replacement");
    var transitions = new CopyOnWriteArrayList<DefinitionReloader.DefinitionReloadTransition>();

    assertThrows(
        ConfigurationException.class,
        () ->
            DefinitionReloader.reload(
                repositories.config(),
                repositories.blueprints(),
                repositories.profiles(),
                true,
                true,
                "reload-rejected",
                transitions::add));

    assertEquals("paper", repositories.blueprints().get("test").orElseThrow().software());
    assertEquals("paper", repositories.profiles().getAll().iterator().next().id());
    assertEquals(1, transitions.size());
    assertEquals(DefinitionReloader.ReloadStatus.REJECTED, transitions.getFirst().status());
    assertEquals(
        DefinitionReloader.ReloadFailureCategory.VALIDATION,
        transitions.getFirst().failureCategory());
    assertEquals(0, transitions.getFirst().blueprintsUpdated());
  }

  @Test
  void installsCompatibleBlueprintAndSoftwareCandidatesTogether() throws Exception {
    Repositories repositories = repositories();
    writeProfile(repositories.profilesPath(), "replacement");
    writeBlueprint(repositories.blueprintsPath(), "replacement");

    DefinitionReloadReport report =
        DefinitionReloader.reload(
            repositories.config(), repositories.blueprints(), repositories.profiles(), true, true);

    assertEquals("replacement", repositories.blueprints().get("test").orElseThrow().software());
    assertEquals("replacement", repositories.profiles().getAll().iterator().next().id());
    assertEquals(java.util.List.of("test"), report.blueprints().updated());
    assertEquals(java.util.List.of("replacement"), report.software().added());
    assertEquals(java.util.List.of("paper"), report.software().removed());
  }

  @Test
  void publishesBoundedCommittedDeltaAndIsolatesObserverFailure() throws Exception {
    Repositories repositories = repositories();
    writeProfile(repositories.profilesPath(), "replacement");
    writeBlueprint(repositories.blueprintsPath(), "replacement");
    var transitions = new CopyOnWriteArrayList<DefinitionReloader.DefinitionReloadTransition>();

    DefinitionReloadReport report =
        DefinitionReloader.reload(
            repositories.config(),
            repositories.blueprints(),
            repositories.profiles(),
            true,
            true,
            "reload-committed",
            transition -> {
              transitions.add(transition);
              throw new IllegalStateException("broken observer");
            });

    assertEquals(java.util.List.of("test"), report.blueprints().updated());
    assertEquals(1, transitions.size());
    var transition = transitions.getFirst();
    assertEquals(DefinitionReloader.ReloadStatus.COMMITTED, transition.status());
    assertEquals(DefinitionReloader.ReloadScope.ALL, transition.scope());
    assertEquals(DefinitionReloader.ReloadFailureCategory.NONE, transition.failureCategory());
    assertEquals(1, transition.blueprintsUpdated());
    assertEquals(1, transition.softwareAdded());
    assertEquals(1, transition.softwareRemoved());
  }

  @Test
  void concurrentReadersAlwaysObserveCompatibleCatalog() throws Exception {
    Repositories repositories = repositories();
    AtomicBoolean running = new AtomicBoolean(true);
    CompletableFuture<Void> reader =
        CompletableFuture.runAsync(
            () -> {
              while (running.get()) {
                DefinitionCatalog.Snapshot snapshot =
                    repositories.blueprints().catalog().snapshot();
                snapshot
                    .blueprints()
                    .values()
                    .forEach(
                        blueprint -> {
                          if (!snapshot.softwareProfiles().containsKey(blueprint.software())) {
                            throw new AssertionError(
                                "Observed blueprint without software profile: "
                                    + blueprint.software());
                          }
                        });
              }
            });

    try {
      for (int iteration = 0; iteration < 20; iteration++) {
        String id = iteration % 2 == 0 ? "replacement" : "paper";
        writeProfile(repositories.profilesPath(), id);
        writeBlueprint(repositories.blueprintsPath(), id);
        DefinitionReloader.reload(
            repositories.config(), repositories.blueprints(), repositories.profiles(), true, true);
      }
    } finally {
      running.set(false);
    }
    reader.join();
  }

  @Test
  void resolvesModernSoftwareMemoryAndImageDefaultsAtomically() throws Exception {
    Path blueprintsPath = Files.createDirectories(temporaryDirectory.resolve("modern-blueprints"));
    Path profilesPath = Files.createDirectories(temporaryDirectory.resolve("modern-profiles"));
    Files.writeString(
        blueprintsPath.resolve("test.yml"),
        """
                blueprint:
                  id: test
                  name: Test
                  type: game
                server:
                  software: paper
                  version: "1.21.11"
                """);
    Files.writeString(
        profilesPath.resolve("paper.yml"),
        """
                software:
                  id: paper
                  name: Paper
                  images:
                    java_21: example/java:21
                    java_25: example/java:25
                  mappings:
                    - java_21: ">=1.20.5 <=1.21.11"
                    - java_25: ">=1.21.12"
                    - default: java_25
                  invocation: "java -jar server.jar"
                  stop-command: stop
                  online-signal: Ready
                  limits:
                    memory_limit: 768
                """);

    DefinitionCatalog catalog = new DefinitionCatalog();
    BlueprintRepository blueprints = new BlueprintRepository(blueprintsPath, catalog);
    SoftwareProfileRepository profiles = new SoftwareProfileRepository(profilesPath, catalog);
    profiles.reload();
    blueprints.reload();

    assertEquals(768, blueprints.get("test").orElseThrow().memoryLimitMiB());
    assertEquals("java_21", blueprints.get("test").orElseThrow().image());

    Files.writeString(
        profilesPath.resolve("paper.yml"),
        """
                software:
                  id: paper
                  name: Paper
                  images:
                    java_21: example/java:21
                  mappings:
                    - java_21: ">=1.20.5 <=1.21.11"
                    - default: java_21
                  invocation: "java -jar server.jar"
                  stop-command: stop
                  online-signal: Ready
                """);
    profiles.reload();

    assertEquals(1024, blueprints.get("test").orElseThrow().memoryLimitMiB());
    assertEquals("java_21", blueprints.get("test").orElseThrow().image());
  }

  @Test
  void concurrentPartialReloadsDoNotRevertEachOther() throws Exception {
    Path blueprintsPath = Files.createDirectories(temporaryDirectory.resolve("partial-blueprints"));
    Path profilesPath = Files.createDirectories(temporaryDirectory.resolve("partial-profiles"));
    writeBlueprint(blueprintsPath, "alpha");
    writeNamedProfile(profilesPath, "alpha", "alpha.jar");
    writeNamedProfile(profilesPath, "beta", "beta.jar");
    DefinitionCatalog catalog = new DefinitionCatalog();
    BlueprintRepository blueprints = new BlueprintRepository(blueprintsPath, catalog);
    SoftwareProfileRepository profiles = new SoftwareProfileRepository(profilesPath, catalog);
    profiles.reload();
    blueprints.reload();
    SLSConfig config = config();

    for (int iteration = 0; iteration < 30; iteration++) {
      String selected = iteration % 2 == 0 ? "beta" : "alpha";
      String alphaJar = "alpha-" + iteration + ".jar";
      writeBlueprint(blueprintsPath, selected);
      writeNamedProfile(profilesPath, "alpha", alphaJar);
      CyclicBarrier start = new CyclicBarrier(2);
      CompletableFuture<Void> blueprintReload =
          CompletableFuture.runAsync(
              () -> {
                await(start);
                reload(config, blueprints, profiles, true, false);
              });
      CompletableFuture<Void> softwareReload =
          CompletableFuture.runAsync(
              () -> {
                await(start);
                reload(config, blueprints, profiles, false, true);
              });

      CompletableFuture.allOf(blueprintReload, softwareReload).join();

      assertEquals(selected, blueprints.get("test").orElseThrow().software());
      assertEquals(alphaJar, profiles.get("alpha").orElseThrow().serverJar());
    }
  }

  private Repositories repositories() throws Exception {
    Path blueprintsPath = Files.createDirectories(temporaryDirectory.resolve("blueprints"));
    Path profilesPath = Files.createDirectories(temporaryDirectory.resolve("profiles"));
    writeBlueprint(blueprintsPath, "paper");
    writeProfile(profilesPath, "paper");

    DefinitionCatalog catalog = new DefinitionCatalog();
    BlueprintRepository blueprints = new BlueprintRepository(blueprintsPath, catalog);
    blueprints.reload();
    SoftwareProfileRepository profiles = new SoftwareProfileRepository(profilesPath, catalog);
    profiles.reload();
    return new Repositories(config(), blueprintsPath, profilesPath, blueprints, profiles);
  }

  private void writeBlueprint(Path directory, String software) throws Exception {
    Files.writeString(
        directory.resolve("test.yml"),
        """
                blueprint:
                  id: test
                  name: Test
                  type: game
                server:
                  software: %s
                  version: "1.21.5"
                  limits:
                    memory_limit: 256
                """
            .formatted(software));
  }

  private void writeProfile(Path directory, String id) throws Exception {
    writeProfileFile(directory.resolve("paper.yml"), id, "paper.jar");
  }

  private void writeNamedProfile(Path directory, String id, String serverJar) throws Exception {
    writeProfileFile(directory.resolve(id + ".yml"), id, serverJar);
  }

  private void writeProfileFile(Path destination, String id, String serverJar) throws Exception {
    Files.writeString(
        destination,
        """
                software:
                  id: %s
                  base_directory: software/paper/{version}
                  server_jar: %s
                """
            .formatted(id, serverJar));
  }

  private static void await(CyclicBarrier barrier) {
    try {
      barrier.await();
    } catch (Exception exception) {
      throw new java.util.concurrent.CompletionException(exception);
    }
  }

  private static void reload(
      SLSConfig config,
      BlueprintRepository blueprints,
      SoftwareProfileRepository profiles,
      boolean reloadBlueprints,
      boolean reloadSoftware) {
    try {
      DefinitionReloader.reload(config, blueprints, profiles, reloadBlueprints, reloadSoftware);
    } catch (Exception exception) {
      throw new java.util.concurrent.CompletionException(exception);
    }
  }

  private SLSConfig config() {
    return new SLSConfig(
        1024,
        101,
        25570,
        25670,
        180,
        180,
        new ManagedOutputConfig(false, true, 4096),
        new ForwardingConfig(
            ForwardingMode.NONE, true, temporaryDirectory.resolve("forwarding.secret")),
        new SecurityConfig(false, 600),
        new SLSLimboConfig(true, 96, 30, -1, 5, 2, 30, 120),
        new LobbyConfig(LobbyMode.EXTERNAL, "lobby", "lobby"),
        temporaryDirectory.resolve("instances"));
  }

  private record Repositories(
      SLSConfig config,
      Path blueprintsPath,
      Path profilesPath,
      BlueprintRepository blueprints,
      SoftwareProfileRepository profiles) {}
}
