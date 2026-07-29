package net.slimelabs.slslite.software;

import net.slimelabs.slslite.config.ConfigurationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoftwareProfileRepositoryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void installsAndLoadsBundledPaperProfile() throws Exception {
        SoftwareProfileRepository repository =
                new SoftwareProfileRepository(temporaryDirectory);

        repository.initialize();

        SoftwareProfile profile = repository.get("paper").orElseThrow();
        assertEquals(SoftwareRuntime.JAVA_JAR, profile.runtime());
        assertEquals(SoftwareConfigurator.PAPER, profile.configurator());
        assertEquals(SoftwareSource.PAPER, profile.source());
        assertEquals(SoftwareReleaseChannel.STABLE, profile.channel());
        assertEquals("java", profile.javaExecutable());
        assertEquals("software/paper/{version}", profile.baseDirectory());
        assertEquals("paper.jar", profile.serverJar());
        assertEquals(List.of("-Xms128M", "-Xmx{memory_mib}M"),
                profile.jvmArguments());
        assertEquals(List.of(), profile.serverArguments());
        assertEquals("stop", profile.stopCommand());
        assertTrue(Files.isRegularFile(temporaryDirectory.resolve("paper.yml")));
    }

    @Test
    void loadsExplicitLaunchAndLifecycleSettings() throws Exception {
        write("custom.yml", """
                software:
                  id: custom
                  base_directory: software/custom/{version}
                  server_jar: custom.jar
                launch:
                  java: runtimes/java-25/bin/java
                  java_versions:
                    "21": runtimes/java-21/bin/java
                    "25": runtimes/java-25/bin/java
                  jvm_arguments:
                    - "-Xmx{memory_mib}M"
                  server_arguments:
                    - "--nogui"
                readiness:
                  pattern: "Server ready"
                  timeout_seconds: 90
                shutdown:
                  command: "end"
                  timeout_seconds: 15
                """);
        SoftwareProfileRepository repository =
                new SoftwareProfileRepository(temporaryDirectory);

        repository.reload();

        SoftwareProfile profile = repository.get("custom").orElseThrow();
        assertEquals(SoftwareSource.MANUAL, profile.source());
        assertEquals("runtimes/java-25/bin/java", profile.javaExecutable());
        assertEquals(
                "runtimes/java-21/bin/java",
                profile.javaExecutables().get(21)
        );
        assertEquals("Server ready", profile.readinessPattern());
        assertEquals(90, profile.startupTimeoutSeconds());
        assertEquals("end", profile.stopCommand());
        assertEquals(15, profile.stopTimeoutSeconds());
    }

    @Test
    void adaptsPinnedModernPaperDefinition() throws Exception {
        copyResource(
                "compatibility/sls-v0.2.0/software/paper.yml",
                "upstream/minecraft/paper.yml"
        );
        SoftwareProfileRepository repository =
                new SoftwareProfileRepository(temporaryDirectory);

        repository.reload();

        SoftwareProfile profile = repository.get("paper").orElseThrow();
        assertEquals("Paper", profile.name());
        assertEquals(SoftwareSource.PAPER, profile.source());
        assertEquals(SoftwareConfigurator.PAPER, profile.configurator());
        assertEquals("software/paper/{version}", profile.baseDirectory());
        assertEquals("server.jar", profile.serverJar());
        assertEquals("java", profile.javaExecutable());
        assertTrue(profile.jvmArguments().contains("-Xmx{memory_mib}M"));
        assertTrue(profile.jvmArguments().stream()
                .noneMatch(argument -> argument.startsWith("-XX:MaxRAMPercentage=")));
        assertEquals("{port}", profile.serverProperties().get("server-port"));
        assertEquals("{port}", profile.serverProperties().get("query.port"));
        assertTrue(Pattern.compile(profile.readinessPattern())
                .matcher("Done (1.2s)! For help, type \"help\"").find());
        assertEquals(600, profile.startupTimeoutSeconds());
        assertEquals("stop", profile.stopCommand());
        assertFalse(profile.acceptEula());
    }

    @Test
    void rejectsShellSyntaxInModernInvocation() throws Exception {
        write("shell.yml", """
                software:
                  id: unsafe
                  name: Unsafe
                  images:
                    java_21: example/java:21
                  invocation: "java -jar server.jar && touch escaped"
                  stop-command: stop
                  online-signal: Ready
                """);
        SoftwareProfileRepository repository =
                new SoftwareProfileRepository(temporaryDirectory);

        ConfigurationException exception = assertThrows(
                ConfigurationException.class,
                repository::reload
        );

        assertTrue(exception.getMessage().contains("unsupported shell syntax"));
    }

    @Test
    void rejectsUnsupportedModernSoftwareConfigTarget() throws Exception {
        write("unsupported-config.yml", """
                software:
                  id: custom
                  name: Custom
                  images:
                    java_21: example/java:21
                  invocation: "java -jar server.jar"
                  stop-command: stop
                  online-signal: Ready
                  configs:
                    paper-global.yml:
                      parser: yaml
                      find:
                        proxies.velocity.enabled: true
                """);
        SoftwareProfileRepository repository =
                new SoftwareProfileRepository(temporaryDirectory);

        ConfigurationException exception = assertThrows(
                ConfigurationException.class,
                repository::reload
        );

        assertTrue(exception.getMessage().contains("software.configs.paper-global.yml"));
    }

    @Test
    void rejectsInvalidReadinessPattern() throws Exception {
        write("invalid.yml", """
                software:
                  id: invalid
                  base_directory: software/invalid
                  server_jar: server.jar
                readiness:
                  pattern: "["
                """);
        SoftwareProfileRepository repository =
                new SoftwareProfileRepository(temporaryDirectory);

        assertThrows(ConfigurationException.class, repository::reload);
    }

    @Test
    void rejectsPathTraversal() throws Exception {
        write("invalid.yml", """
                software:
                  id: invalid
                  base_directory: ../outside
                  server_jar: server.jar
                """);
        SoftwareProfileRepository repository =
                new SoftwareProfileRepository(temporaryDirectory);

        assertThrows(ConfigurationException.class, repository::reload);
    }

    @Test
    void rejectsUnknownStructuralKey() throws Exception {
        write("typo.yml", """
                software:
                  id: typo
                  base_directory: software/typo
                  server_jar: server.jar
                readiness:
                  timeout_second: 30
                """);
        SoftwareProfileRepository repository =
                new SoftwareProfileRepository(temporaryDirectory);

        ConfigurationException exception = assertThrows(
                ConfigurationException.class,
                repository::reload
        );

        assertTrue(exception.getMessage().contains("readiness.timeout_second"));
        assertTrue(exception.getMessage().contains("readiness.timeout_seconds"));
    }

    private void write(String name, String content) throws Exception {
        Path target = temporaryDirectory.resolve(name);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }

    private void copyResource(String resource, String targetName) throws IOException {
        Path target = temporaryDirectory.resolve(targetName);
        Files.createDirectories(target.getParent());
        try (InputStream input = getClass().getClassLoader().getResourceAsStream(resource)) {
            if (input == null) {
                throw new IOException("Missing test resource: " + resource);
            }
            Files.copy(input, target);
        }
    }
}
