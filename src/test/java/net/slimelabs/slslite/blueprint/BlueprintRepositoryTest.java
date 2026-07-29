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
                  configs:
                    server.properties:
                      parser: properties
                      find:
                        enable-command-block: true
                        view-distance: 8
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
        assertEquals("true", blueprint.serverProperties().get("enable-command-block"));
        assertEquals("8", blueprint.serverProperties().get("view-distance"));
        assertFalse(blueprint.save());
    }

    @Test
    void loadsModernStateVolumes() throws Exception {
        write("world.yml", """
                blueprint:
                  id: survival
                  name: Survival
                  type: games
                server:
                  software: paper
                  version: "26.1"
                state:
                  volumes:
                    - name: world
                      source: worlds/survival/main
                      target: /world
                      mode: cow
                """);

        BlueprintRepository repository = new BlueprintRepository(temporaryDirectory);
        repository.reload();

        BlueprintVolume volume = repository.get("survival")
                .orElseThrow()
                .volumes()
                .getFirst();
        assertEquals("world", volume.name());
        assertEquals("worlds/survival/main", volume.source());
        assertEquals("/world", volume.target());
        assertEquals(BlueprintVolume.Mode.COW, volume.mode());
    }

    @Test
    void rejectsUnsupportedVolumeMode() throws Exception {
        write("world.yml", """
                blueprint:
                  id: survival
                  name: Survival
                  type: games
                server:
                  software: paper
                  version: "26.1"
                state:
                  volumes:
                    - name: world
                      source: worlds/survival/main
                      target: /world
                      mode: rw
                """);

        BlueprintException exception = assertThrows(
                BlueprintException.class,
                () -> new BlueprintRepository(temporaryDirectory).reload()
        );

        assertTrue(exception.getMessage().contains("must be 'cow'"));
    }

    @Test
    void rejectsDuplicateVolumeNames() throws Exception {
        write("world.yml", """
                blueprint:
                  id: survival
                  name: Survival
                  type: games
                server:
                  software: paper
                  version: "26.1"
                state:
                  volumes:
                    - name: world
                      source: worlds/one
                      target: /world
                      mode: cow
                    - name: world
                      source: worlds/two
                      target: /world_nether
                      mode: cow
                """);

        BlueprintException exception = assertThrows(
                BlueprintException.class,
                () -> new BlueprintRepository(temporaryDirectory).reload()
        );

        assertTrue(exception.getMessage().contains("duplicate volume name 'world'"));
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
    void loadsBlueprintsFromCategoryDirectories() throws Exception {
        write("minigames/block-hunt.yml", """
                blueprint:
                  id: block-hunt
                  name: Block Hunt
                  type: minigames
                server:
                  software: paper
                  version: "26.1"
                """);
        write("lobbies/main.yml", """
                blueprint:
                  id: main-lobby
                  name: Main Lobby
                  type: lobbies
                server:
                  software: paper
                  version: "26.1"
                """);

        BlueprintRepository repository = new BlueprintRepository(temporaryDirectory);
        repository.initialize();

        assertEquals(2, repository.getAll().size());
        assertTrue(repository.get("minigames", "block-hunt").isPresent());
        assertTrue(repository.get("lobbies", "main-lobby").isPresent());
        assertFalse(Files.exists(temporaryDirectory.resolve("template.yml")));
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
        write("minigames/one.yml", yaml);
        write("archives/two.yml", yaml);

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
    void rejectsUnknownStructuralKey() throws Exception {
        write("typo.yml", """
                blueprint:
                  id: typo
                  name: Typo
                  type: game
                server:
                  software: paper
                  version: "26.1"
                  limits:
                    max_intances: 2
                """);
        BlueprintRepository repository = new BlueprintRepository(temporaryDirectory);

        BlueprintException exception = assertThrows(
                BlueprintException.class,
                repository::reload
        );

        assertTrue(exception.getMessage().contains("server.limits.max_intances"));
        assertTrue(exception.getMessage().contains("server.limits.max_instances"));
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
        Path target = temporaryDirectory.resolve(name);
        Files.createDirectories(target.getParent());
        Files.writeString(target, content);
    }
}
