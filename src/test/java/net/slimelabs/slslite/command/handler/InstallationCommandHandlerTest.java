package net.slimelabs.slslite.command.handler;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.command.CommandSource;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.slimelabs.slslite.command.CommandAuthorizer;
import net.slimelabs.slslite.command.CommandPermissions;
import net.slimelabs.slslite.install.InstallationKey;
import net.slimelabs.slslite.install.InstallationSnapshot;
import net.slimelabs.slslite.install.InstallationState;
import net.slimelabs.slslite.install.SoftwareCacheCleanupReport;
import net.slimelabs.slslite.instance.ServerController;
import net.slimelabs.slslite.security.AdministratorStore;
import net.slimelabs.slslite.software.SoftwareProfile;
import net.slimelabs.slslite.software.SoftwareProfileRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstallationCommandHandlerTest {

  @TempDir Path temporaryDirectory;

  private CommandAuthorizer authorizer;

  @BeforeEach
  void setUp() throws Exception {
    AdministratorStore administrators = new AdministratorStore(temporaryDirectory);
    administrators.initialize();
    authorizer = new CommandAuthorizer(administrators);
  }

  @Test
  void unavailableServicePreservesPermissionAndAvailabilityMessages() {
    InstallationCommandHandler handler =
        new InstallationCommandHandler(
            null, null, (InstallationCommandHandler.InstallationStatusSource) null, authorizer);
    List<Component> deniedMessages = new ArrayList<>();
    handler.execute(source(Set.of(), deniedMessages), new String[] {"install", "info"});
    assertTrue(
        plainText(deniedMessages.getFirst())
            .contains("do not have permission to inspect software installation"));

    List<Component> permittedMessages = new ArrayList<>();
    handler.execute(
        source(Set.of(CommandPermissions.ADMIN), permittedMessages),
        new String[] {"install", "info"});
    assertTrue(
        plainText(permittedMessages.getFirst())
            .contains("/sls install is not available in this SLS-LITE build yet"));
  }

  @Test
  void suggestionsPreservePermissionAndSubcommandSurface() {
    InstallationCommandHandler handler =
        new InstallationCommandHandler(
            null, null, (InstallationCommandHandler.InstallationStatusSource) null, authorizer);

    assertEquals(
        List.of(),
        handler.suggestions(source(Set.of(), new ArrayList<>()), new String[] {"install", ""}));
    assertEquals(
        List.of("info", "logs", "warmup", "cleanup"),
        handler.suggestions(
            source(Set.of(CommandPermissions.ADMIN), new ArrayList<>()),
            new String[] {"install", ""}));
  }

  @Test
  void logsRemainBoundedToLatestTenRetainedLines() {
    List<String> logs =
        java.util.stream.IntStream.rangeClosed(1, 12).mapToObj(index -> "line-" + index).toList();
    InstallationSnapshot snapshot =
        new InstallationSnapshot(
            new InstallationKey("paper", "1.21.1"),
            InstallationState.FAILED,
            "download failed",
            Instant.parse("2026-01-01T00:00:00Z"),
            Instant.parse("2026-01-01T00:00:01Z"),
            logs);
    InstallationCommandHandler.InstallationStatusSource statuses =
        new InstallationCommandHandler.InstallationStatusSource() {
          @Override
          public List<InstallationSnapshot> snapshots() {
            return List.of(snapshot);
          }

          @Override
          public InstallationSnapshot snapshot(String softwareId, String version) {
            return "paper".equals(softwareId) && "1.21.1".equals(version) ? snapshot : null;
          }
        };
    InstallationCommandHandler handler =
        new InstallationCommandHandler(null, null, statuses, authorizer);
    List<Component> messages = new ArrayList<>();

    handler.execute(
        source(Set.of(CommandPermissions.ADMIN), messages),
        new String[] {"install", "logs", "paper", "1.21.1"});

    List<String> text = messages.stream().map(InstallationCommandHandlerTest::plainText).toList();
    assertEquals(12, text.size());
    assertTrue(text.get(1).contains("latest 10 of 12"));
    assertFalse(text.contains("line-1"));
    assertFalse(text.contains("line-2"));
    assertEquals("line-3", text.get(2));
    assertEquals("line-12", text.getLast());
  }

  @Test
  void warmupInvokesInstallerAndReportsCompletion() throws Exception {
    SoftwareProfileRepository profiles = softwareProfiles();
    AtomicReference<String> requested = new AtomicReference<>();
    InstallationCommandHandler.InstallationStatusSource statuses =
        new InstallationCommandHandler.InstallationStatusSource() {
          @Override
          public List<InstallationSnapshot> snapshots() {
            return List.of();
          }

          @Override
          public InstallationSnapshot snapshot(String softwareId, String version) {
            return null;
          }

          @Override
          public CompletableFuture<Path> warmup(SoftwareProfile profile, String version) {
            requested.set(profile.id() + ":" + version);
            return CompletableFuture.completedFuture(temporaryDirectory);
          }
        };
    InstallationCommandHandler handler =
        new InstallationCommandHandler(
            new net.slimelabs.slslite.blueprint.BlueprintRepository(
                temporaryDirectory.resolve("blueprints")),
            profiles,
            statuses,
            authorizer);
    List<Component> messages = new ArrayList<>();
    try {
      handler.execute(
          source(Set.of(CommandPermissions.ADMIN), messages),
          new String[] {"install", "warmup", "paper", "1.21.11"});
    } finally {
      handler.close();
    }

    assertEquals("paper:1.21.11", requested.get());
    assertTrue(
        messages.stream()
            .map(InstallationCommandHandlerTest::plainText)
            .anyMatch(message -> message.contains("Software cache ready")));
  }

  @Test
  void cleanupRunsOffThreadAndBoundsCandidateMessages() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    List<SoftwareCacheCleanupReport.Entry> entries =
        java.util.stream.IntStream.range(0, 25)
            .mapToObj(
                index ->
                    new SoftwareCacheCleanupReport.Entry(
                        new InstallationKey("paper", "1." + index),
                        temporaryDirectory.resolve("cache-" + index)))
            .toList();
    InstallationCommandHandler.InstallationStatusSource statuses =
        new InstallationCommandHandler.InstallationStatusSource() {
          @Override
          public List<InstallationSnapshot> snapshots() {
            return List.of();
          }

          @Override
          public InstallationSnapshot snapshot(String softwareId, String version) {
            return null;
          }

          @Override
          public SoftwareCacheCleanupReport cleanup(
              Duration minimumAge,
              boolean dryRun,
              boolean confirmed,
              Set<InstallationKey> protectedKeys,
              java.util.Collection<SoftwareProfile> knownProfiles)
              throws Exception {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            return new SoftwareCacheCleanupReport(true, entries, List.of(), 25, 0, 0, 0, false);
          }
        };
    ServerController instances =
        (ServerController)
            Proxy.newProxyInstance(
                ServerController.class.getClassLoader(),
                new Class<?>[] {ServerController.class},
                (ignored, method, arguments) ->
                    "protectedSoftwareVersions".equals(method.getName())
                        ? Set.of()
                        : defaultValue(method.getReturnType()));
    InstallationCommandHandler handler =
        new InstallationCommandHandler(
            new net.slimelabs.slslite.blueprint.BlueprintRepository(
                temporaryDirectory.resolve("blueprints")),
            new SoftwareProfileRepository(temporaryDirectory.resolve("profiles")),
            statuses,
            authorizer,
            instances);
    List<Component> messages = new java.util.concurrent.CopyOnWriteArrayList<>();
    try {
      handler.execute(
          source(Set.of(CommandPermissions.ADMIN), messages),
          new String[] {"install", "cleanup", "24"});
      assertTrue(entered.await(5, TimeUnit.SECONDS));
      assertTrue(messages.isEmpty());
      release.countDown();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      while (messages.size() < 23 && System.nanoTime() < deadline) {
        Thread.sleep(10);
      }
    } finally {
      release.countDown();
      handler.close();
    }

    List<String> text = messages.stream().map(InstallationCommandHandlerTest::plainText).toList();
    assertEquals(23, text.size());
    assertTrue(text.getLast().contains("5 more candidate"));
  }

  private SoftwareProfileRepository softwareProfiles() throws Exception {
    Path directory = Files.createDirectories(temporaryDirectory.resolve("software-profiles"));
    Files.writeString(
        directory.resolve("paper.yml"),
        """
        software:
          id: paper
          source: paper
          accept_eula: true
          base_directory: software/paper/{version}
          server_jar: paper.jar
        """);
    SoftwareProfileRepository repository = new SoftwareProfileRepository(directory);
    repository.reload();
    return repository;
  }

  private static CommandSource source(Set<String> permissions, List<Component> messages) {
    return (CommandSource)
        Proxy.newProxyInstance(
            CommandSource.class.getClassLoader(),
            new Class<?>[] {CommandSource.class},
            (ignored, method, arguments) ->
                switch (method.getName()) {
                  case "hasPermission" -> permissions.contains(arguments[0]);
                  case "sendMessage" -> {
                    messages.add((Component) arguments[0]);
                    yield null;
                  }
                  default -> defaultValue(method.getReturnType());
                });
  }

  private static String plainText(Component component) {
    StringBuilder output = new StringBuilder();
    appendPlainText(component, output);
    return output.toString();
  }

  private static void appendPlainText(Component component, StringBuilder output) {
    if (component instanceof TextComponent text) {
      output.append(text.content());
    }
    component.children().forEach(child -> appendPlainText(child, output));
  }

  private static Object defaultValue(Class<?> type) {
    if (!type.isPrimitive()) {
      return null;
    }
    if (type == boolean.class) {
      return false;
    }
    if (type == int.class || type == short.class || type == byte.class) {
      return 0;
    }
    if (type == long.class) {
      return 0L;
    }
    if (type == float.class) {
      return 0F;
    }
    if (type == double.class) {
      return 0D;
    }
    return '\0';
  }
}
