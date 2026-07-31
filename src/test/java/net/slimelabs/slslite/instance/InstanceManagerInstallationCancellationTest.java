package net.slimelabs.slslite.instance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import net.slimelabs.slslite.blueprint.BlueprintRepository;
import net.slimelabs.slslite.config.ForwardingConfig;
import net.slimelabs.slslite.config.ForwardingMode;
import net.slimelabs.slslite.config.ManagedOutputConfig;
import net.slimelabs.slslite.install.InstallationArtifact;
import net.slimelabs.slslite.install.SoftwareInstallationProvider;
import net.slimelabs.slslite.install.SoftwareInstallationService;
import net.slimelabs.slslite.instance.storage.InstanceDirectoryPreparer;
import net.slimelabs.slslite.network.LoopbackPortAllocator;
import net.slimelabs.slslite.process.JavaJarProcessSpecFactory;
import net.slimelabs.slslite.process.ProcessSupervisor;
import net.slimelabs.slslite.resource.ResourceBudget;
import net.slimelabs.slslite.software.SoftwareProfile;
import net.slimelabs.slslite.software.SoftwareProfileRepository;
import net.slimelabs.slslite.software.SoftwareSource;
import net.slimelabs.slslite.velocity.BackendRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

final class InstanceManagerInstallationCancellationTest {

  @TempDir private Path temporaryDirectory;

  private InstanceManager manager;
  private SoftwareInstallationService installations;

  @AfterEach
  void close() {
    if (manager != null) {
      manager.shutdown(Duration.ofSeconds(3));
    }
    if (installations != null) {
      installations.close();
    }
  }

  @Test
  void stoppingInstanceCancelsOnlyItsWaitOnSharedInstallation() throws Exception {
    Path blueprintsDirectory = Files.createDirectories(temporaryDirectory.resolve("blueprints"));
    Path profilesDirectory = Files.createDirectories(temporaryDirectory.resolve("profiles"));
    Files.writeString(
        blueprintsDirectory.resolve("fixture.yml"),
        """
                blueprint:
                  id: fixture
                  name: Fixture
                  type: test
                server:
                  software: paper
                  version: 1.21.11
                  limits:
                    memory_limit: 256
                save: false
                """);
    Files.writeString(
        profilesDirectory.resolve("paper.yml"),
        """
                software:
                  id: paper
                  source: paper
                  accept_eula: true
                  base_directory: software/paper/{version}
                  server_jar: fixture.jar
                launch:
                  java: java
                  jvm_arguments: []
                  server_arguments: []
                readiness:
                  pattern: "READY"
                  timeout_seconds: 30
                shutdown:
                  command: stop
                  timeout_seconds: 2
                """);

    BlueprintRepository blueprints = new BlueprintRepository(blueprintsDirectory);
    blueprints.reload();
    SoftwareProfileRepository profiles = new SoftwareProfileRepository(profilesDirectory);
    profiles.reload();
    SoftwareProfile profile = profiles.get("paper").orElseThrow();
    JavaJarProcessSpecFactory paths = new JavaJarProcessSpecFactory(temporaryDirectory);
    BlockingProvider provider = new BlockingProvider();
    installations =
        new SoftwareInstallationService(
            paths, List.of(provider), LoggerFactory.getLogger(getClass()));
    ResourceBudget budget = new ResourceBudget(1024);
    int port = findAvailablePort();
    LoopbackPortAllocator ports = new LoopbackPortAllocator(port, port);
    manager =
        new InstanceManager(
            blueprints,
            profiles,
            budget,
            new ManagedOutputConfig(false, false, 64),
            new ForwardingConfig(
                ForwardingMode.NONE, false, temporaryDirectory.resolve("forwarding.secret")),
            ports,
            new InstanceDirectoryPreparer(
                temporaryDirectory.resolve("instances"), temporaryDirectory),
            paths,
            new ProcessSupervisor(2),
            new NoopBackendRegistry(),
            installations,
            LoggerFactory.getLogger(getClass()));

    ManagedInstance instance = manager.start("fixture");
    assertTrue(provider.started.await(5, TimeUnit.SECONDS));
    var sharedInstallation = installations.ensureInstalled(profile, "1.21.11");

    assertEquals(0, manager.stop(instance.id()).get(3, TimeUnit.SECONDS));
    assertTrue(ports.reservations().isEmpty());
    assertEquals(0, budget.reservedMemoryMiB());
    assertFalse(sharedInstallation.isDone());

    provider.release.countDown();
    assertTrue(Files.isDirectory(sharedInstallation.get(5, TimeUnit.SECONDS)));
  }

  private static int findAvailablePort() throws Exception {
    try (ServerSocket socket = new ServerSocket()) {
      socket.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), 0));
      return socket.getLocalPort();
    }
  }

  private static final class BlockingProvider implements SoftwareInstallationProvider {

    private final CountDownLatch started = new CountDownLatch(1);
    private final CountDownLatch release = new CountDownLatch(1);

    @Override
    public SoftwareSource source() {
      return SoftwareSource.PAPER;
    }

    @Override
    public InstallationArtifact install(
        SoftwareProfile profile,
        String version,
        Path stagingDirectory,
        java.util.function.Consumer<String> log)
        throws Exception {
      started.countDown();
      if (!release.await(10, TimeUnit.SECONDS)) {
        throw new IllegalStateException("test installation was not released");
      }
      Path jar = stagingDirectory.resolve(profile.serverJar());
      Files.writeString(jar, "fixture");
      byte[] contents = Files.readAllBytes(jar);
      return new InstallationArtifact(
          contents.length,
          "SHA-256",
          HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contents)));
    }
  }

  private static final class NoopBackendRegistry implements BackendRegistry {

    @Override
    public void register(String name, InetSocketAddress address) {}

    @Override
    public void unregister(String name) {}
  }
}
