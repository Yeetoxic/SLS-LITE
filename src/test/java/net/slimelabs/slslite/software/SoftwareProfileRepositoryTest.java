package net.slimelabs.slslite.software;

import net.slimelabs.slslite.config.ConfigurationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
        assertEquals("java", profile.javaExecutable());
        assertEquals("software/paper/{version}", profile.baseDirectory());
        assertEquals("paper.jar", profile.serverJar());
        assertEquals(List.of("-Xms{memory_mib}M", "-Xmx{memory_mib}M"),
                profile.jvmArguments());
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
        assertEquals("runtimes/java-25/bin/java", profile.javaExecutable());
        assertEquals("Server ready", profile.readinessPattern());
        assertEquals(90, profile.startupTimeoutSeconds());
        assertEquals("end", profile.stopCommand());
        assertEquals(15, profile.stopTimeoutSeconds());
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
        Files.writeString(temporaryDirectory.resolve(name), content);
    }
}
