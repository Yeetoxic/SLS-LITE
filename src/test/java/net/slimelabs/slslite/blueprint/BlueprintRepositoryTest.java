package net.slimelabs.slslite.blueprint;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlueprintRepositoryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsModernStyleBlueprint() throws Exception {
        write("game.yml", """
                blueprint:
                  id: block-hunt
                  name: Block Hunt
                  type: minigame
                server:
                  software: paper
                  version: "26.1"
                  limits:
                    memory_limit: 1536
                    max_players: 32
                    max_instances: 3
                save: false
                annotations:
                  sls-lite:
                    stop-when-empty: true
                """);

        BlueprintRepository repository = new BlueprintRepository(temporaryDirectory);
        repository.reload();

        Blueprint blueprint = repository.get("block-hunt").orElseThrow();
        assertEquals("Block Hunt", blueprint.name());
        assertEquals("minigame", blueprint.type());
        assertEquals("paper", blueprint.software());
        assertEquals("26.1", blueprint.version());
        assertEquals(1536, blueprint.memoryLimitMiB());
        assertEquals(32, blueprint.maxPlayers());
        assertEquals(3, blueprint.maxInstances());
        assertFalse(blueprint.save());
    }

    @Test
    void installsBundledTemplateIntoEmptyDirectory() throws Exception {
        BlueprintRepository repository = new BlueprintRepository(temporaryDirectory);

        repository.initialize();

        assertEquals(1, repository.getAll().size());
        assertEquals("template", repository.getAll().iterator().next().id());
        assertTrue(Files.isRegularFile(temporaryDirectory.resolve("template.yml")));
    }

    @Test
    void rejectsDuplicateBlueprintIds() throws Exception {
        String yaml = """
                blueprint:
                  id: duplicate
                  name: Duplicate
                  type: game
                server:
                  software: paper
                  version: "26.1"
                """;
        write("one.yml", yaml);
        write("two.yml", yaml);

        BlueprintRepository repository = new BlueprintRepository(temporaryDirectory);

        assertThrows(BlueprintException.class, repository::reload);
    }

    @Test
    void rejectsInvalidBlueprintId() throws Exception {
        write("invalid.yml", """
                blueprint:
                  id: Not-Valid
                  name: Invalid
                  type: game
                server:
                  software: paper
                  version: "26.1"
                """);

        BlueprintRepository repository = new BlueprintRepository(temporaryDirectory);

        assertThrows(BlueprintException.class, repository::reload);
    }

    @Test
    void rejectsFractionalMemoryLimit() throws Exception {
        write("fractional.yml", """
                blueprint:
                  id: fractional
                  name: Fractional
                  type: game
                server:
                  software: paper
                  version: "26.1"
                  limits:
                    memory_limit: 768.5
                """);

        BlueprintRepository repository = new BlueprintRepository(temporaryDirectory);

        assertThrows(BlueprintException.class, repository::reload);
    }

    @Test
    void discoversUserDefinedRegistryTypes() throws Exception {
        write("lobby.yml", """
                blueprint:
                  id: main-lobby
                  name: Main Lobby
                  type: lobbies
                server:
                  software: paper
                  version: "26.1"
                """);
        write("adventure.yml", """
                blueprint:
                  id: sky-quest
                  name: Sky Quest
                  type: adventures
                server:
                  software: paper
                  version: "26.1"
                """);

        BlueprintRepository repository = new BlueprintRepository(temporaryDirectory);
        repository.reload();

        assertEquals(java.util.Set.of("lobbies", "adventures"), repository.getTypes());
        assertEquals("sky-quest", repository.get("adventures", "sky-quest").orElseThrow().id());
        assertTrue(repository.get("lobbies", "sky-quest").isEmpty());
        assertEquals(1, repository.getByType("lobbies").size());
    }

    private void write(String name, String content) throws IOException {
        Files.writeString(temporaryDirectory.resolve(name), content);
    }
}
