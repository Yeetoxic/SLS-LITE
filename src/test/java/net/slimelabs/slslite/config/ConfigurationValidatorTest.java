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
                25570,
                25670,
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
