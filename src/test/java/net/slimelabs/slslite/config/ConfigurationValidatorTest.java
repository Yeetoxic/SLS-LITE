package net.slimelabs.slslite.config;

import net.slimelabs.slslite.blueprint.BlueprintRepository;
import net.slimelabs.slslite.software.SoftwareProfileRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;

class ConfigurationValidatorTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void acceptsResolvableBlueprintWithinBudget() throws Exception {
        Repositories repositories = repositories("paper", 1024, 2048);

        assertDoesNotThrow(() -> ConfigurationValidator.validate(
                repositories.config(),
                repositories.blueprints(),
                repositories.profiles()
        ));
    }

    @Test
    void rejectsMissingSoftwareProfile() throws Exception {
        Repositories repositories = repositories("unknown", 1024, 2048);

        assertThrows(ConfigurationException.class, () -> ConfigurationValidator.validate(
                repositories.config(),
                repositories.blueprints(),
                repositories.profiles()
        ));
    }

    @Test
    void rejectsBlueprintOverHostBudget() throws Exception {
        Repositories repositories = repositories("paper", 4096, 2048);

        assertThrows(ConfigurationException.class, () -> ConfigurationValidator.validate(
                repositories.config(),
                repositories.blueprints(),
                repositories.profiles()
        ));
    }

    @Test
    void rejectsMissingManagedLobbyBlueprint() throws Exception {
        Repositories repositories = repositories("paper", 1024, 2048);
        SLSConfig managedLobby = new SLSConfig(
                repositories.config().totalMemoryMiB(),
                repositories.config().maxManagedProcesses(),
                repositories.config().portRangeStart(),
                repositories.config().portRangeEnd(),
                repositories.config().queueTimeoutSeconds(),
                repositories.config().idleShutdownSeconds(),
                repositories.config().managedOutput(),
                repositories.config().forwarding(),
                repositories.config().security(),
                repositories.config().limbo(),
                new LobbyConfig(LobbyMode.MANAGED, "lobby", "missing"),
                repositories.config().instancesDirectory()
        );

        assertThrows(ConfigurationException.class, () -> ConfigurationValidator.validate(
                managedLobby,
                repositories.blueprints(),
                repositories.profiles()
        ));
    }

    @Test
    void rejectsManagedLobbyAndLimboOverCombinedBudget() throws Exception {
        Repositories repositories = repositories("paper", 1024, 1024);
        SLSConfig config = withManagedLobby(
                repositories.config(),
                "game",
                "test",
                repositories.config().limbo()
        );

        assertThrows(ConfigurationException.class, () -> ConfigurationValidator.validate(
                config,
                repositories.blueprints(),
                repositories.profiles()
        ));
    }

    @Test
    void acceptsManagedLobbyAndLimboAtExactCombinedBudget() throws Exception {
        Repositories repositories = repositories("paper", 1024, 1120);
        SLSConfig config = withManagedLobby(
                repositories.config(),
                "game",
                "test",
                repositories.config().limbo()
        );

        assertDoesNotThrow(() -> ConfigurationValidator.validate(
                config,
                repositories.blueprints(),
                repositories.profiles()
        ));
    }

    @Test
    void rejectsModernForwardingModeMismatch() throws Exception {
        Repositories repositories = repositories("paper", 1024, 2048);
        SLSConfig config = withForwarding(
                repositories.config(),
                new ForwardingConfig(
                        ForwardingMode.MODERN,
                        false,
                        temporaryDirectory.resolve("forwarding.secret")
                )
        );

        assertThrows(ConfigurationException.class, () -> ConfigurationValidator.validate(
                config,
                repositories.blueprints(),
                repositories.profiles(),
                true
        ));
    }

    @Test
    void allowsNoneForwardingModeWithoutOnlineModeMatch() throws Exception {
        Repositories repositories = repositories("paper", 1024, 2048);

        assertDoesNotThrow(() -> ConfigurationValidator.validate(
                repositories.config(),
                repositories.blueprints(),
                repositories.profiles(),
                false
        ));
    }

    private SLSConfig withManagedLobby(
            SLSConfig source,
            String registry,
            String server,
            SLSLimboConfig limbo
    ) {
        return new SLSConfig(
                source.totalMemoryMiB(),
                source.maxManagedProcesses(),
                source.portRangeStart(),
                source.portRangeEnd(),
                source.queueTimeoutSeconds(),
                source.idleShutdownSeconds(),
                source.managedOutput(),
                source.forwarding(),
                source.security(),
                limbo,
                new LobbyConfig(LobbyMode.MANAGED, registry, server),
                source.instancesDirectory()
        );
    }

    private SLSConfig withForwarding(SLSConfig source, ForwardingConfig forwarding) {
        return new SLSConfig(
                source.totalMemoryMiB(),
                source.maxManagedProcesses(),
                source.portRangeStart(),
                source.portRangeEnd(),
                source.queueTimeoutSeconds(),
                source.idleShutdownSeconds(),
                source.managedOutput(),
                forwarding,
                source.security(),
                source.limbo(),
                source.lobby(),
                source.instancesDirectory()
        );
    }

    private Repositories repositories(
            String softwareId,
            int blueprintMemory,
            int totalMemory
    ) throws Exception {
        Path blueprintsPath = Files.createDirectory(temporaryDirectory.resolve("blueprints"));
        Path profilesPath = Files.createDirectory(temporaryDirectory.resolve("profiles"));

        Files.writeString(blueprintsPath.resolve("test.yml"), """
                blueprint:
                  id: test
                  name: Test
                  type: game
                server:
                  software: %s
                  version: "26.1"
                  limits:
                    memory_limit: %d
                """.formatted(softwareId, blueprintMemory));
        Files.writeString(profilesPath.resolve("paper.yml"), """
                software:
                  id: paper
                  base_directory: software/paper/{version}
                  server_jar: paper.jar
                """);

        BlueprintRepository blueprints = new BlueprintRepository(blueprintsPath);
        blueprints.reload();
        SoftwareProfileRepository profiles = new SoftwareProfileRepository(profilesPath);
        profiles.reload();
        SLSConfig config = new SLSConfig(
                totalMemory,
                101,
                25570,
                25670,
                180,
                180,
                new ManagedOutputConfig(false, true, 4096),
                new ForwardingConfig(
                        ForwardingMode.NONE,
                        true,
                        temporaryDirectory.resolve("forwarding.secret")
                ),
                new SecurityConfig(false, 600),
                new SLSLimboConfig(true, 96, 30, -1, 5, 2, 30, 120),
                new LobbyConfig(LobbyMode.EXTERNAL, "lobby", "lobby"),
                temporaryDirectory.resolve("instances")
        );
        return new Repositories(config, blueprints, profiles);
    }

    private record Repositories(
            SLSConfig config,
            BlueprintRepository blueprints,
            SoftwareProfileRepository profiles
    ) {
    }
}
