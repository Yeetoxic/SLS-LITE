package net.slimelabs.slslite.instance;

import net.slimelabs.slslite.blueprint.BlueprintRepository;
import net.slimelabs.slslite.config.ManagedOutputConfig;
import net.slimelabs.slslite.config.ForwardingConfig;
import net.slimelabs.slslite.config.ForwardingMode;
import net.slimelabs.slslite.network.LoopbackPortAllocator;
import net.slimelabs.slslite.process.FixtureProcessMain;
import net.slimelabs.slslite.process.PaperProcessSpecFactory;
import net.slimelabs.slslite.process.ProcessSupervisor;
import net.slimelabs.slslite.resource.ResourceBudget;
import net.slimelabs.slslite.software.SoftwareProfileRepository;
import net.slimelabs.slslite.velocity.BackendRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstanceManagerTest {

    @TempDir
    Path temporaryDirectory;

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
        assertTrue(Files.isRegularFile(
                instance.directory().resolve(TemporaryInstanceLog.RELATIVE_PATH)
        ));
        assertEquals(256, context.budget().reservedMemoryMiB());
        assertTrue(instance.logs(1, 50).lines().contains("FIXTURE READY"));

        assertEquals(0, manager.stop(instance.id()).get(10, TimeUnit.SECONDS));
        awaitCleanup();

        assertTrue(context.backends().registrations.isEmpty());
        assertEquals(0, context.budget().reservedMemoryMiB());
        assertFalse(Files.exists(instance.directory()));
    }

    @Test
    void preservesPersistentInstanceDirectoryAfterStop() throws Exception {
        createContext(true, true);

        ManagedInstance instance = manager.start("fixture");
        instance.readyFuture().get(10, TimeUnit.SECONDS);
        manager.stop(instance.id()).get(10, TimeUnit.SECONDS);
        awaitCleanup();

        assertTrue(Files.isDirectory(instance.directory()));
        InstanceMetadata metadata = new InstanceMetadataStore(
                instance.directory().getParent()
        ).read(instance.directory()).orElseThrow();
        assertTrue(metadata.persistent());
        assertEquals(InstanceState.STOPPED, metadata.state());
        assertEquals(null, metadata.processId());
        assertEquals(java.util.List.of(instance.id()), manager.persistentInstanceIds());
    }

    @Test
    void restartsPersistentInstanceWithSameIdAndDirectory() throws Exception {
        createContext(true, true);

        ManagedInstance original = manager.start("fixture");
        original.readyFuture().get(10, TimeUnit.SECONDS);
        Files.writeString(original.directory().resolve("persistent-marker"), "preserved");

        ManagedInstance restarted = manager.restart(original.id())
                .get(10, TimeUnit.SECONDS);
        restarted.readyFuture().get(10, TimeUnit.SECONDS);

        assertEquals(original.id(), restarted.id());
        assertEquals(original.directory(), restarted.directory());
        assertEquals(original.createdAt(), restarted.createdAt());
        assertTrue(Files.isRegularFile(restarted.directory().resolve("persistent-marker")));
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
        ManagedInstance recovered = manager.restart(original.id())
                .get(10, TimeUnit.SECONDS);
        recovered.readyFuture().get(10, TimeUnit.SECONDS);

        assertEquals(original.id(), recovered.id());
        assertEquals(original.createdAt(), recovered.createdAt());
        assertEquals(InstanceState.READY, recovered.state());
    }

    @Test
    void rejectsRestartForEphemeralInstance() throws Exception {
        createContext(false, true);
        ManagedInstance instance = manager.start("fixture");
        instance.readyFuture().get(10, TimeUnit.SECONDS);

        InstanceOperationException exception = assertThrows(
                InstanceOperationException.class,
                () -> manager.restart(instance.id())
        );

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
        assertEquals(
                "version-two",
                Files.readString(reset.directory().resolve("template-version"))
        );
    }

    @Test
    void sendsNormalizedSingleLineConsoleCommands() throws Exception {
        createContext(false, true);
        ManagedInstance instance = manager.start("fixture");
        instance.readyFuture().get(10, TimeUnit.SECONDS);

        manager.sendCommand(instance.id(), "/say hello");
        assertThrows(
                InstanceOperationException.class,
                () -> manager.sendCommand(instance.id(), "say one\nsay two")
        );
        manager.stop(instance.id()).get(10, TimeUnit.SECONDS);
    }

    @Test
    void releasesAdmissionsWhenProcessFailsBeforeReadiness() throws Exception {
        TestContext context = createContext(false, false);

        ManagedInstance instance = manager.start("fixture");

        assertThrows(
                ExecutionException.class,
                () -> instance.readyFuture().get(10, TimeUnit.SECONDS)
        );
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
    void enforcesBlueprintInstanceLimitForDirectStarts() throws Exception {
        createContext(false, true);
        ManagedInstance first = manager.start("fixture");
        first.readyFuture().get(10, TimeUnit.SECONDS);

        InstanceOperationException exception = assertThrows(
                InstanceOperationException.class,
                () -> manager.start("fixture")
        );

        assertTrue(exception.getMessage().contains("limit of 1"));
        manager.stop(first.id()).get(10, TimeUnit.SECONDS);
    }

    private TestContext createContext(boolean save, boolean includeJar) throws Exception {
        Path blueprintsDirectory = Files.createDirectories(
                temporaryDirectory.resolve("blueprints")
        );
        Path profilesDirectory = Files.createDirectories(
                temporaryDirectory.resolve("profiles")
        );
        Path softwareDirectory = Files.createDirectories(
                temporaryDirectory.resolve("software/paper/fixture")
        );
        if (includeJar) {
            createFixtureJar(softwareDirectory.resolve("fixture.jar"));
        }

        Files.writeString(blueprintsDirectory.resolve("fixture.yml"), """
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
                """.formatted(save));
        Files.writeString(profilesDirectory.resolve("paper.yml"), """
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
                """.formatted(javaExecutable().replace("\\", "\\\\")));

        BlueprintRepository blueprints = new BlueprintRepository(blueprintsDirectory);
        blueprints.reload();
        SoftwareProfileRepository profiles = new SoftwareProfileRepository(profilesDirectory);
        profiles.reload();
        ResourceBudget budget = new ResourceBudget(1024);
        int port = findAvailablePort();
        LoopbackPortAllocator ports = new LoopbackPortAllocator(port, port);
        InstanceDirectoryPreparer preparer = new InstanceDirectoryPreparer(
                temporaryDirectory.resolve("instances")
        );
        ProcessSupervisor supervisor = new ProcessSupervisor(2);
        FakeBackendRegistry backends = new FakeBackendRegistry();
        manager = new InstanceManager(
                blueprints,
                profiles,
                budget,
                new ManagedOutputConfig(false, true, 64),
                new ForwardingConfig(
                        ForwardingMode.NONE,
                        false,
                        temporaryDirectory.resolve("forwarding.secret")
                ),
                ports,
                preparer,
                new PaperProcessSpecFactory(temporaryDirectory),
                supervisor,
                backends,
                LoggerFactory.getLogger(InstanceManagerTest.class)
        );
        return new TestContext(budget, ports, backends);
    }

    private void awaitCleanup() throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!manager.getAll().isEmpty() && System.nanoTime() < deadline) {
            Thread.sleep(20);
        }
        assertTrue(manager.getAll().isEmpty());
    }

    private static void createFixtureJar(Path target) throws Exception {
        Manifest manifest = new Manifest();
        manifest.getMainAttributes().put(Attributes.Name.MANIFEST_VERSION, "1.0");
        manifest.getMainAttributes().put(
                Attributes.Name.MAIN_CLASS,
                FixtureProcessMain.class.getName()
        );
        String classEntry = FixtureProcessMain.class.getName().replace('.', '/') + ".class";
        try (InputStream classBytes = FixtureProcessMain.class.getClassLoader()
                .getResourceAsStream(classEntry);
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
        boolean windows = System.getProperty("os.name")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("windows");
        return Path.of(
                System.getProperty("java.home"),
                "bin",
                windows ? "java.exe" : "java"
        ).toString();
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
            FakeBackendRegistry backends
    ) {
    }

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
