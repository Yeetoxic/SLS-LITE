package net.slimelabs.slslite.instance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.jar.Attributes;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;
import java.util.jar.Manifest;
import net.slimelabs.slslite.blueprint.BlueprintRepository;
import net.slimelabs.slslite.config.ForwardingConfig;
import net.slimelabs.slslite.config.ForwardingMode;
import net.slimelabs.slslite.config.ManagedOutputConfig;
import net.slimelabs.slslite.instance.diagnostics.InstanceOutput;
import net.slimelabs.slslite.instance.lifecycle.InstancePhaseTimings;
import net.slimelabs.slslite.instance.metadata.InstanceMetadataStore;
import net.slimelabs.slslite.instance.model.InstanceLaunchOverrides;
import net.slimelabs.slslite.instance.model.InstanceMetadata;
import net.slimelabs.slslite.instance.model.InstanceState;
import net.slimelabs.slslite.instance.storage.InstanceDirectoryPreparer;
import net.slimelabs.slslite.network.LoopbackPortAllocator;
import net.slimelabs.slslite.process.FixtureProcessMain;
import net.slimelabs.slslite.process.JavaJarProcessSpecFactory;
import net.slimelabs.slslite.process.ProcessSupervisor;
import net.slimelabs.slslite.resource.ResourceBudget;
import net.slimelabs.slslite.software.SoftwareProfileRepository;
import net.slimelabs.slslite.velocity.BackendRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class InstanceManagerTest {

  @TempDir Path temporaryDirectory;

  private InstanceManager manager;

  @AfterEach
  void shutdown() {
    if (manager != null) {
      manager.shutdown(Duration.ofSeconds(3));
    }
  }

  @Test
  void preparesRegistersStopsAndCleansEphemeralInstance() throws Exception {
    TestContext context = createContext(false, true);

    ManagedInstance instance = manager.start("fixture");
    instance.readyFuture().get(10, TimeUnit.SECONDS);

    assertEquals(InstanceState.READY, instance.state());
    assertTrue(context.backends().registrations.containsKey(instance.id()));
    assertTrue(Files.isRegularFile(instance.directory().resolve("server.properties")));
    assertTrue(
        Files.isRegularFile(instance.directory().resolve(InstanceOutput.TEMPORARY_RELATIVE_PATH)));
    assertEquals(256, context.budget().reservedMemoryMiB());
    assertTrue(instance.logs(1, 50).lines().contains("FIXTURE READY"));
    for (InstancePhaseTimings.Phase phase :
        java.util.List.of(
            InstancePhaseTimings.Phase.DISPATCH_QUEUE,
            InstancePhaseTimings.Phase.SOFTWARE_RESOLUTION,
            InstancePhaseTimings.Phase.FILE_PREPARATION,
            InstancePhaseTimings.Phase.CONFIGURATION,
            InstancePhaseTimings.Phase.PROCESS_LAUNCH,
            InstancePhaseTimings.Phase.READINESS,
            InstancePhaseTimings.Phase.REGISTRATION)) {
      assertTrue(
          instance.timings().elapsedNanos(phase).isPresent(), () -> "Missing timing for " + phase);
    }

    assertEquals(0, manager.stop(instance.id()).get(10, TimeUnit.SECONDS));
    awaitCleanup();

    assertTrue(context.backends().registrations.isEmpty());
    assertEquals(0, context.budget().reservedMemoryMiB());
    assertFalse(Files.exists(instance.directory()));
    assertTrue(instance.timings().elapsedNanos(InstancePhaseTimings.Phase.SHUTDOWN).isPresent());
    assertTrue(instance.timings().elapsedNanos(InstancePhaseTimings.Phase.CLEANUP).isPresent());
  }

  @Test
  void preservesPersistentInstanceDirectoryAfterStop() throws Exception {
    createContext(true, true);

    ManagedInstance instance = manager.start("fixture");
    instance.readyFuture().get(10, TimeUnit.SECONDS);
    manager.stop(instance.id()).get(10, TimeUnit.SECONDS);
    awaitCleanup();

    assertTrue(Files.isDirectory(instance.directory()));
    InstanceMetadata metadata =
        new InstanceMetadataStore(instance.directory().getParent())
            .read(instance.directory())
            .orElseThrow();
    assertTrue(metadata.persistent());
    assertEquals(InstanceState.STOPPED, metadata.state());
    assertEquals(null, metadata.processId());
    assertEquals(java.util.List.of(instance.id()), manager.persistentInstanceIds());
  }

  @Test
  void forceKillReleasesAdmissionsAndPreservesPersistentStorage() throws Exception {
    TestContext context = createContext(true, true);
    ManagedInstance instance = manager.start("fixture");
    instance.readyFuture().get(10, TimeUnit.SECONDS);

    int exitCode = manager.kill(instance.id()).get(10, TimeUnit.SECONDS);
    awaitCleanup();

    assertTrue(exitCode != 0);
    assertTrue(Files.isDirectory(instance.directory()));
    assertTrue(context.backends().registrations.isEmpty());
    assertTrue(context.ports().reservations().isEmpty());
    assertEquals(0, context.budget().reservedMemoryMiB());
    assertTrue(manager.getAll().isEmpty());
  }

  @Test
  void repeatedStopAndConcurrentKillShareOneTerminalCleanup() throws Exception {
    TestContext context = createContext(false, true);
    ManagedInstance instance = manager.start("fixture");
    instance.readyFuture().get(10, TimeUnit.SECONDS);

    var firstStop = manager.stop(instance.id());
    var repeatedStop = manager.stop(instance.id());
    var kill = manager.kill(instance.id());

    assertSame(firstStop, repeatedStop);
    assertSame(firstStop, kill);
    firstStop.handle((ignored, failure) -> null).get(10, TimeUnit.SECONDS);
    awaitCleanup();

    assertTrue(context.backends().registrations.isEmpty());
    assertTrue(context.ports().reservations().isEmpty());
    assertEquals(0, context.budget().reservedMemoryMiB());
    assertFalse(Files.exists(instance.directory()));
  }

  @Test
  void persistentAdministrativeRacesReturnOneStableConflictAndPreserveOwnership() throws Exception {
    TestContext context = createContext(true, true);
    ManagedInstance original = manager.start("fixture");
    original.readyFuture().get(10, TimeUnit.SECONDS);

    var restart = manager.restart(original.id());
    InstanceOperationException resetConflict =
        assertThrows(InstanceOperationException.class, () -> manager.reset(original.id()));
    InstanceOperationException deleteConflict =
        assertThrows(InstanceOperationException.class, () -> manager.delete(original.id()));

    assertEquals(resetConflict.getMessage(), deleteConflict.getMessage());
    assertTrue(resetConflict.getMessage().contains("already in progress"));
    ManagedInstance replacement = restart.get(10, TimeUnit.SECONDS);
    replacement.readyFuture().get(10, TimeUnit.SECONDS);
    assertEquals(original.id(), replacement.id());
    assertEquals(original.directory(), replacement.directory());
    assertEquals(1, context.backends().registrations.size());
    assertEquals(1, context.ports().reservations().size());
    assertEquals(256, context.budget().reservedMemoryMiB());

    manager.stop(replacement.id()).get(10, TimeUnit.SECONDS);
    awaitCleanup();
    assertTrue(context.backends().registrations.isEmpty());
    assertTrue(context.ports().reservations().isEmpty());
    assertEquals(0, context.budget().reservedMemoryMiB());
    assertTrue(Files.isDirectory(original.directory()));
  }

  @Test
  void forceKillDuringStartupReleasesAllAdmissions() throws Exception {
    TestContext context = createContext(false, true);
    ManagedInstance instance = manager.start("fixture");

    manager.kill(instance.id()).get(10, TimeUnit.SECONDS);
    awaitCleanup();

    assertTrue(context.backends().registrations.isEmpty());
    assertTrue(context.ports().reservations().isEmpty());
    assertEquals(0, context.budget().reservedMemoryMiB());
    assertFalse(Files.exists(instance.directory()));
  }

  @Test
  void deletesActivePersistentInstanceAfterItsCleanShutdown() throws Exception {
    TestContext context = createContext(true, true);
    ManagedInstance instance = manager.start("fixture");
    instance.readyFuture().get(10, TimeUnit.SECONDS);

    InstanceDeletionResult deleted = manager.delete(instance.id()).get(10, TimeUnit.SECONDS);

    assertEquals(instance.id(), deleted.instanceId());
    assertTrue(deleted.tombstoneCleaned());
    assertFalse(Files.exists(instance.directory()));
    assertTrue(context.backends().registrations.isEmpty());
    assertEquals(0, context.budget().reservedMemoryMiB());
  }

  @Test
  void deletesStoppedPersistentInstanceOwnedByMetadata() throws Exception {
    createContext(true, true);
    ManagedInstance instance = manager.start("fixture");
    instance.readyFuture().get(10, TimeUnit.SECONDS);
    manager.stop(instance.id()).get(10, TimeUnit.SECONDS);
    awaitCleanup();

    InstanceDeletionResult deleted = manager.delete(instance.id()).get(10, TimeUnit.SECONDS);

    assertTrue(deleted.tombstoneCleaned());
    assertFalse(Files.exists(instance.directory()));
    assertTrue(manager.persistentInstanceIds().isEmpty());
  }

  @Test
  void rejectsDeletingAnUnownedDirectory() throws Exception {
    createContext(true, true);
    Path unowned =
        Files.createDirectories(temporaryDirectory.resolve("instances").resolve("unowned.abc123"));

    InstanceOperationException exception =
        assertThrows(InstanceOperationException.class, () -> manager.delete("unowned.abc123"));

    assertTrue(exception.getMessage().contains("No persistent SLS-LITE instance exists"));
    assertTrue(Files.isDirectory(unowned));
  }

  @Test
  void restartsPersistentInstanceWithSameIdAndDirectory() throws Exception {
    createContext(true, true);
    Path template = temporaryDirectory.resolve("software/paper/fixture/template-version");
    Files.writeString(template, "version-one");

    ManagedInstance original = manager.start("fixture");
    original.readyFuture().get(10, TimeUnit.SECONDS);
    Files.writeString(original.directory().resolve("persistent-marker"), "preserved");
    Files.writeString(template, "version-two");

    ManagedInstance restarted = manager.restart(original.id()).get(10, TimeUnit.SECONDS);
    restarted.readyFuture().get(10, TimeUnit.SECONDS);

    assertEquals(original.id(), restarted.id());
    assertEquals(original.directory(), restarted.directory());
    assertEquals(original.createdAt(), restarted.createdAt());
    assertTrue(Files.isRegularFile(restarted.directory().resolve("persistent-marker")));
    assertEquals(
        "version-one", Files.readString(restarted.directory().resolve("template-version")));
    assertEquals(InstanceState.READY, restarted.state());
  }

  @Test
  void restartsStoppedPersistentInstanceAfterManagerRecreation() throws Exception {
    createContext(true, true);
    ManagedInstance original = manager.start("fixture");
    original.readyFuture().get(10, TimeUnit.SECONDS);
    manager.stop(original.id()).get(10, TimeUnit.SECONDS);
    awaitCleanup();
    manager.shutdown(Duration.ofSeconds(3));

    createContext(true, true);
    ManagedInstance recovered = manager.restart(original.id()).get(10, TimeUnit.SECONDS);
    recovered.readyFuture().get(10, TimeUnit.SECONDS);

    assertEquals(original.id(), recovered.id());
    assertEquals(original.createdAt(), recovered.createdAt());
    assertEquals(InstanceState.READY, recovered.state());
  }

  @Test
  void createOverridesSurviveManagerRecreationRestartAndReset() throws Exception {
    TestContext context = createContext(false, true);
    InstanceLaunchOverrides overrides =
        new InstanceLaunchOverrides(384, true, "persistent-seed", 10, 8, false);
    ManagedInstance original = manager.create("fixture", overrides);
    original.readyFuture().get(10, TimeUnit.SECONDS);

    assertEquals(384, original.blueprint().memoryLimitMiB());
    assertTrue(original.blueprint().save());
    assertEquals(384, context.budget().reservedMemoryMiB());
    assertTrue(
        Files.readString(original.directory().resolve("server.properties"))
            .contains("level-seed=persistent-seed"));
    manager.stop(original.id()).get(10, TimeUnit.SECONDS);
    awaitCleanup();
    manager.shutdown(Duration.ofSeconds(3));

    context = createContext(false, true);
    ManagedInstance restarted = manager.restart(original.id()).get(10, TimeUnit.SECONDS);
    restarted.readyFuture().get(10, TimeUnit.SECONDS);
    assertEquals(overrides, restarted.launchOverrides());
    assertEquals(384, restarted.blueprint().memoryLimitMiB());
    assertEquals(384, context.budget().reservedMemoryMiB());
    Files.writeString(restarted.directory().resolve("operator-data"), "reset removes this");

    ManagedInstance reset = manager.reset(restarted.id()).get(10, TimeUnit.SECONDS);
    reset.readyFuture().get(10, TimeUnit.SECONDS);
    String properties = Files.readString(reset.directory().resolve("server.properties"));
    assertEquals(overrides, reset.launchOverrides());
    assertFalse(Files.exists(reset.directory().resolve("operator-data")));
    assertTrue(properties.contains("level-seed=persistent-seed"));
    assertTrue(properties.contains("view-distance=10"));
    assertTrue(properties.contains("simulation-distance=8"));
    assertTrue(properties.contains("enable-command-block=false"));
  }

  @Test
  void rejectsChangedPersistentDefinitionUntilExplicitReset() throws Exception {
    TestContext context = createContext(true, true);
    ManagedInstance original = manager.start("fixture");
    original.readyFuture().get(10, TimeUnit.SECONDS);
    manager.stop(original.id()).get(10, TimeUnit.SECONDS);
    awaitCleanup();

    Path profile = temporaryDirectory.resolve("profiles/paper.yml");
    Files.writeString(
        profile, Files.readString(profile).replace("timeout_seconds: 5", "timeout_seconds: 6"));
    context.profiles().reload();

    InstanceOperationException rejected =
        assertThrows(InstanceOperationException.class, () -> manager.restart(original.id()));
    assertTrue(rejected.getMessage().contains("reset"));

    ManagedInstance reset = manager.reset(original.id()).get(10, TimeUnit.SECONDS);
    reset.readyFuture().get(10, TimeUnit.SECONDS);
    assertEquals(original.id(), reset.id());
  }

  @Test
  void rejectsPersistenceFlagChangeWithoutDeletingExistingData() throws Exception {
    TestContext context = createContext(true, true);
    ManagedInstance original = manager.start("fixture");
    original.readyFuture().get(10, TimeUnit.SECONDS);
    manager.stop(original.id()).get(10, TimeUnit.SECONDS);
    awaitCleanup();
    Files.writeString(original.directory().resolve("world-data"), "player changes");

    Path blueprint = temporaryDirectory.resolve("blueprints/fixture.yml");
    Files.writeString(blueprint, Files.readString(blueprint).replace("save: true", "save: false"));
    context.blueprints().reload();

    InstanceOperationException rejected =
        assertThrows(InstanceOperationException.class, () -> manager.restart(original.id()));

    assertTrue(rejected.getMessage().contains("reset"));
    assertEquals("player changes", Files.readString(original.directory().resolve("world-data")));
  }

  @Test
  void migratesLegacyPersistentMetadataWithoutResettingData() throws Exception {
    createContext(true, true);
    ManagedInstance original = manager.start("fixture");
    original.readyFuture().get(10, TimeUnit.SECONDS);
    manager.stop(original.id()).get(10, TimeUnit.SECONDS);
    awaitCleanup();

    Path metadataPath = original.directory().resolve(InstanceMetadataStore.FILE_NAME);
    java.util.Properties values = new java.util.Properties();
    try (var input = Files.newInputStream(metadataPath)) {
      values.load(input);
    }
    values.setProperty("schema", "1");
    values.remove("software_id");
    values.remove("software_version");
    values.remove("definition_fingerprint");
    try (var output = Files.newOutputStream(metadataPath)) {
      values.store(output, "Legacy fixture");
    }

    Files.writeString(original.directory().resolve("world-data"), "player changes");
    ManagedInstance restarted = manager.restart(original.id()).get(10, TimeUnit.SECONDS);
    restarted.readyFuture().get(10, TimeUnit.SECONDS);

    assertEquals(original.id(), restarted.id());
    assertEquals("player changes", Files.readString(restarted.directory().resolve("world-data")));
    java.util.Properties migrated = new java.util.Properties();
    try (var input = Files.newInputStream(metadataPath)) {
      migrated.load(input);
    }
    assertEquals("4", migrated.getProperty("schema"));
    assertTrue(migrated.containsKey("definition_fingerprint"));
  }

  @Test
  void rejectsLegacyMigrationWhenBlueprintIsNoLongerPersistent() throws Exception {
    TestContext context = createContext(true, true);
    ManagedInstance original = manager.start("fixture");
    original.readyFuture().get(10, TimeUnit.SECONDS);
    manager.stop(original.id()).get(10, TimeUnit.SECONDS);
    awaitCleanup();
    Files.writeString(original.directory().resolve("world-data"), "player changes");

    Path metadataPath = original.directory().resolve(InstanceMetadataStore.FILE_NAME);
    java.util.Properties values = new java.util.Properties();
    try (var input = Files.newInputStream(metadataPath)) {
      values.load(input);
    }
    values.setProperty("schema", "1");
    values.remove("software_id");
    values.remove("software_version");
    values.remove("definition_fingerprint");
    try (var output = Files.newOutputStream(metadataPath)) {
      values.store(output, "Legacy fixture");
    }

    Path blueprint = temporaryDirectory.resolve("blueprints/fixture.yml");
    Files.writeString(blueprint, Files.readString(blueprint).replace("save: true", "save: false"));
    context.blueprints().reload();

    InstanceOperationException rejected =
        assertThrows(InstanceOperationException.class, () -> manager.restart(original.id()));

    assertTrue(rejected.getMessage().contains("restore save: true"));
    assertEquals("player changes", Files.readString(original.directory().resolve("world-data")));
  }

  @Test
  void rejectsRestartForEphemeralInstance() throws Exception {
    createContext(false, true);
    ManagedInstance instance = manager.start("fixture");
    instance.readyFuture().get(10, TimeUnit.SECONDS);

    InstanceOperationException exception =
        assertThrows(InstanceOperationException.class, () -> manager.restart(instance.id()));

    assertTrue(exception.getMessage().contains("ephemeral"));
  }

  @Test
  void resetsPersistentInstanceFromTemplateAndKeepsItsId() throws Exception {
    createContext(true, true);
    Path template = temporaryDirectory.resolve("software/paper/fixture/template-version");
    Files.writeString(template, "version-one");

    ManagedInstance original = manager.start("fixture");
    original.readyFuture().get(10, TimeUnit.SECONDS);
    Files.writeString(original.directory().resolve("world-data"), "player changes");
    Files.writeString(template, "version-two");

    ManagedInstance reset = manager.reset(original.id()).get(10, TimeUnit.SECONDS);
    reset.readyFuture().get(10, TimeUnit.SECONDS);

    assertEquals(original.id(), reset.id());
    assertEquals(original.createdAt(), reset.createdAt());
    assertFalse(Files.exists(reset.directory().resolve("world-data")));
    assertEquals("version-two", Files.readString(reset.directory().resolve("template-version")));
  }

  @Test
  void resetRestoresBlueprintCowVolume() throws Exception {
    createContext(true, true, true);

    ManagedInstance original = manager.start("fixture");
    original.readyFuture().get(10, TimeUnit.SECONDS);
    Path instanceWorld = original.directory().resolve("world/level.dat");
    assertEquals("clean world", Files.readString(instanceWorld));
    Files.writeString(instanceWorld, "player changes");

    ManagedInstance reset = manager.reset(original.id()).get(10, TimeUnit.SECONDS);
    reset.readyFuture().get(10, TimeUnit.SECONDS);

    assertEquals("clean world", Files.readString(reset.directory().resolve("world/level.dat")));
    assertEquals(
        "clean world", Files.readString(temporaryDirectory.resolve("worlds/fixture/level.dat")));
  }

  @Test
  void sendsNormalizedSingleLineConsoleCommands() throws Exception {
    createContext(false, true);
    ManagedInstance instance = manager.start("fixture");
    instance.readyFuture().get(10, TimeUnit.SECONDS);

    manager.sendCommand(instance.id(), "/say hello");
    assertThrows(
        InstanceOperationException.class,
        () -> manager.sendCommand(instance.id(), "say one\nsay two"));
    manager.stop(instance.id()).get(10, TimeUnit.SECONDS);
  }

  @Test
  void releasesAdmissionsWhenProcessFailsBeforeReadiness() throws Exception {
    TestContext context = createContext(false, false);

    ManagedInstance instance = manager.start("fixture");

    assertThrows(ExecutionException.class, () -> instance.readyFuture().get(10, TimeUnit.SECONDS));
    awaitCleanup();
    assertEquals(0, context.budget().reservedMemoryMiB());
    assertTrue(context.ports().reservations().isEmpty());
    assertTrue(context.backends().registrations.isEmpty());
  }

  @Test
  void stopDuringStartupReleasesAllAdmissions() throws Exception {
    TestContext context = createContext(false, true);
    ManagedInstance instance = manager.start("fixture");

    assertEquals(0, manager.stop(instance.id()).get(10, TimeUnit.SECONDS));
    awaitCleanup();

    assertTrue(context.backends().registrations.isEmpty());
    assertTrue(context.ports().reservations().isEmpty());
    assertEquals(0, context.budget().reservedMemoryMiB());
    assertFalse(Files.exists(instance.directory()));
  }

  @Test
  void proxyShutdownDuringPreparationDoesNotLaunchAfterSupervisorCloses() throws Exception {
    TestContext context = createContext(false, true, true);
    ManagedInstance instance = manager.start("fixture");

    manager.shutdown(Duration.ofSeconds(3));
    manager = null;

    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while ((!context.ports().reservations().isEmpty()
            || context.budget().reservedMemoryMiB() != 0
            || Files.exists(instance.directory()))
        && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }

    assertTrue(instance.readyFuture().isCompletedExceptionally());
    assertTrue(context.backends().registrations.isEmpty());
    assertTrue(context.ports().reservations().isEmpty());
    assertEquals(0, context.budget().reservedMemoryMiB());
    assertFalse(Files.exists(instance.directory()));
  }

  @Test
  void enforcesBlueprintInstanceLimitForDirectStarts() throws Exception {
    createContext(false, true);
    ManagedInstance first = manager.start("fixture");
    first.readyFuture().get(10, TimeUnit.SECONDS);

    InstanceOperationException exception =
        assertThrows(InstanceOperationException.class, () -> manager.start("fixture"));

    assertTrue(exception.getMessage().contains("limit of 1"));
    manager.stop(first.id()).get(10, TimeUnit.SECONDS);
  }

  @Test
  void maintenanceModeBlocksOnlyNewCreationAndIsIdempotent() throws Exception {
    TestContext context = createContext(false, true);
    ManagedInstance active = manager.start("fixture");
    active.readyFuture().get(10, TimeUnit.SECONDS);

    var enabled = manager.setMaintenance(true, "host upgrade");
    var repeated = manager.setMaintenance(true, "host upgrade");

    assertEquals(enabled, repeated);
    assertTrue(manager.maintenanceStatus().enabled());
    InstanceOperationException blocked =
        assertThrows(InstanceOperationException.class, () -> manager.start("fixture"));
    assertTrue(blocked.getMessage().contains("host upgrade"));
    assertEquals(256, context.budget().reservedMemoryMiB());
    assertEquals(1, context.ports().reservations().size());

    assertEquals(0, manager.stop(active.id()).get(10, TimeUnit.SECONDS));
    awaitCleanup();
    assertTrue(manager.getAll().isEmpty());

    manager.setMaintenance(false, "");
    ManagedInstance accepted = manager.start("fixture");
    accepted.readyFuture().get(10, TimeUnit.SECONDS);
    assertEquals(0, manager.stop(accepted.id()).get(10, TimeUnit.SECONDS));
  }

  @Test
  void boundedCrashRecoveryRestartsPersistentInstanceAndExhaustsItsBudget() throws Exception {
    TestContext context = createContext(true, true);
    enableCrashRecovery(context.blueprints(), 1);

    ManagedInstance original = manager.start("fixture");
    original.readyFuture().get(10, TimeUnit.SECONDS);
    manager.sendCommand(original.id(), "crash");

    ManagedInstance recovered = awaitReplacement(original);
    recovered.readyFuture().get(10, TimeUnit.SECONDS);
    assertEquals(original.id(), recovered.id());
    assertEquals(original.directory(), recovered.directory());
    assertEquals(256, context.budget().reservedMemoryMiB());

    manager.sendCommand(recovered.id(), "crash");
    recovered.stoppedFuture().handle((ignored, failure) -> null).get(10, TimeUnit.SECONDS);
    awaitCleanup();
    Thread.sleep(1_200);

    assertTrue(manager.getAll().isEmpty());
    assertTrue(context.backends().registrations.isEmpty());
    assertTrue(context.ports().reservations().isEmpty());
    assertEquals(0, context.budget().reservedMemoryMiB());
    assertTrue(Files.isDirectory(original.directory()));
  }

  @Test
  void intentionalStopSuppressesConfiguredCrashRecovery() throws Exception {
    TestContext context = createContext(true, true);
    enableCrashRecovery(context.blueprints(), 2);
    ManagedInstance instance = manager.start("fixture");
    instance.readyFuture().get(10, TimeUnit.SECONDS);

    assertEquals(0, manager.stop(instance.id()).get(10, TimeUnit.SECONDS));
    awaitCleanup();
    Thread.sleep(1_200);

    assertTrue(manager.getAll().isEmpty());
    assertEquals(0, context.budget().reservedMemoryMiB());
    assertTrue(context.ports().reservations().isEmpty());
  }

  private TestContext createContext(boolean save, boolean includeJar) throws Exception {
    return createContext(save, includeJar, false);
  }

  private TestContext createContext(boolean save, boolean includeJar, boolean includeVolume)
      throws Exception {
    Path blueprintsDirectory = Files.createDirectories(temporaryDirectory.resolve("blueprints"));
    Path profilesDirectory = Files.createDirectories(temporaryDirectory.resolve("profiles"));
    Path softwareDirectory =
        Files.createDirectories(temporaryDirectory.resolve("software/paper/fixture"));
    if (includeJar) {
      createFixtureJar(softwareDirectory.resolve("fixture.jar"));
    }
    if (includeVolume) {
      Path world = Files.createDirectories(temporaryDirectory.resolve("worlds/fixture"));
      Files.writeString(world.resolve("level.dat"), "clean world");
    }

    Files.writeString(
        blueprintsDirectory.resolve("fixture.yml"),
        """
                blueprint:
                  id: fixture
                  name: Fixture
                  type: test
                server:
                  software: paper
                  version: fixture
                  limits:
                    memory_limit: 256
                save: %s
                %s
                """
            .formatted(
                save,
                includeVolume
                    ? """
                        state:
                          volumes:
                            - name: world
                              source: worlds/fixture
                              target: /world
                              mode: cow
                        """
                    : ""));
    Files.writeString(
        profilesDirectory.resolve("paper.yml"),
        """
                software:
                  id: paper
                  base_directory: software/paper/{version}
                  server_jar: fixture.jar
                launch:
                  java: "%s"
                  jvm_arguments: []
                  server_arguments:
                    - "ready-stop"
                readiness:
                  pattern: "FIXTURE READY"
                  timeout_seconds: 5
                shutdown:
                  command: stop
                  timeout_seconds: 2
                """
            .formatted(javaExecutable().replace("\\", "\\\\")));

    BlueprintRepository blueprints = new BlueprintRepository(blueprintsDirectory);
    blueprints.reload();
    SoftwareProfileRepository profiles = new SoftwareProfileRepository(profilesDirectory);
    profiles.reload();
    ResourceBudget budget = new ResourceBudget(1024);
    int port = findAvailablePort();
    LoopbackPortAllocator ports = new LoopbackPortAllocator(port, port);
    InstanceDirectoryPreparer preparer =
        new InstanceDirectoryPreparer(temporaryDirectory.resolve("instances"), temporaryDirectory);
    ProcessSupervisor supervisor = new ProcessSupervisor(2);
    FakeBackendRegistry backends = new FakeBackendRegistry();
    manager =
        new InstanceManager(
            blueprints,
            profiles,
            budget,
            new ManagedOutputConfig(false, true, 64),
            new ForwardingConfig(
                ForwardingMode.NONE, false, temporaryDirectory.resolve("forwarding.secret")),
            ports,
            preparer,
            new JavaJarProcessSpecFactory(temporaryDirectory),
            supervisor,
            backends,
            LoggerFactory.getLogger(InstanceManagerTest.class));
    return new TestContext(budget, ports, backends, blueprints, profiles);
  }

  private void awaitCleanup() throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
    while (!manager.getAll().isEmpty() && System.nanoTime() < deadline) {
      Thread.sleep(20);
    }
    assertTrue(manager.getAll().isEmpty());
  }

  private ManagedInstance awaitReplacement(ManagedInstance original) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
    while (System.nanoTime() < deadline) {
      ManagedInstance candidate =
          manager.getAll().stream()
              .filter(instance -> instance.id().equals(original.id()) && instance != original)
              .findFirst()
              .orElse(null);
      if (candidate != null) {
        return candidate;
      }
      Thread.sleep(20);
    }
    throw new AssertionError("Automatic recovery did not start a replacement instance");
  }

  private void enableCrashRecovery(BlueprintRepository repository, int maximumAttempts)
      throws Exception {
    Files.writeString(
        temporaryDirectory.resolve("blueprints/fixture.yml"),
        """
                blueprint:
                  id: fixture
                  name: Fixture
                  type: test
                server:
                  software: paper
                  version: fixture
                  limits:
                    memory_limit: 256
                save: true
                annotations:
                  sls-lite:
                    restart-on-crash: true
                    restart-max-attempts: %d
                    restart-initial-backoff-seconds: 1
                    restart-max-backoff-seconds: 1
                    restart-stable-after-seconds: 60
                """
            .formatted(maximumAttempts));
    repository.reload();
  }

  private static void createFixtureJar(Path target) throws Exception {
    Manifest manifest = new Manifest();
    manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
    manifest
        .getMainAttributes()
        .put(Attributes.Name.MAIN_CLASS, FixtureProcessMain.class.getName());
    String classEntry = FixtureProcessMain.class.getName().replace('.', '/') + ".class";
    try (InputStream classBytes =
            FixtureProcessMain.class.getClassLoader().getResourceAsStream(classEntry);
        JarOutputStream jar = new JarOutputStream(Files.newOutputStream(target), manifest)) {
      if (classBytes == null) {
        throw new IllegalStateException("Fixture class bytes are unavailable");
      }
      jar.putNextEntry(new JarEntry(classEntry));
      classBytes.transferTo(jar);
      jar.closeEntry();
    }
  }

  private static String javaExecutable() {
    boolean windows =
        System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("windows");
    return Path.of(System.getProperty("java.home"), "bin", windows ? "java.exe" : "java")
        .toString();
  }

  private static int findAvailablePort() throws Exception {
    try (ServerSocket socket = new ServerSocket()) {
      socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
      return socket.getLocalPort();
    }
  }

  private record TestContext(
      ResourceBudget budget,
      LoopbackPortAllocator ports,
      FakeBackendRegistry backends,
      BlueprintRepository blueprints,
      SoftwareProfileRepository profiles) {}

  private static final class FakeBackendRegistry implements BackendRegistry {

    private final Map<String, InetSocketAddress> registrations = new ConcurrentHashMap<>();

    @Override
    public void register(String name, InetSocketAddress address) {
      if (registrations.putIfAbsent(name, address) != null) {
        throw new IllegalStateException("Duplicate registration: " + name);
      }
    }

    @Override
    public void unregister(String name) {
      registrations.remove(name);
    }
  }
}
