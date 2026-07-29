package net.slimelabs.slslite.blueprint;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;

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
    void loadsPinnedVSLSAnnotationsFromUpstreamFixture() throws Exception {
        copyResource(
                "compatibility/sls-v0.2.0/example_vsls.yml",
                "archives/example_vsls.yml"
        );

        BlueprintRepository repository = new BlueprintRepository(temporaryDirectory);
        repository.reload();

        Blueprint blueprint = repository.get("slsmp1").orElseThrow();
        assertEquals("archive", blueprint.type());
        assertEquals(1, blueprint.maxPlayers());
        assertEquals(1, blueprint.maxInstances());
        assertTrue(BlueprintLifecyclePolicy.from(blueprint, 180).keepAlive());
        assertEquals(
                Duration.ofSeconds(180),
                BlueprintLifecyclePolicy.from(blueprint, 180).idleTimeout()
        );
        assertTrue(blueprint.annotations().containsKey("vsls"));
    }

    @Test
    void localLimitsOverrideVSLSAnnotationDefaults() throws Exception {
        write("precedence.yml", """
                blueprint:
                  id: precedence
                  name: Precedence
                  type: minigame
                server:
                  software: paper
                  version: "1.21.1"
                  limits:
                    max_players: 12
                    max_instances: 3
                annotations:
                  vsls:
                    max-instances: 5
                    matchmaking:
                      maxPlayers: 40
                """);

        BlueprintRepository repository = new BlueprintRepository(temporaryDirectory);
        repository.reload();

        Blueprint blueprint = repository.get("precedence").orElseThrow();
        assertEquals(12, blueprint.maxPlayers());
        assertEquals(3, blueprint.maxInstances());
    }

    @Test
    void ignoresInvalidVSLSAnnotationValues() throws Exception {
        write("invalid-vsls.yml", """
                blueprint:
                  id: invalid-vsls
                  name: Invalid vSLS annotations
                  type: minigame
                server:
                  software: paper
                  version: "1.21.1"
                annotations:
                  vsls:
                    dont-stop-when-empty: enabled
                    max-instances: unlimited
                    matchmaking:
                      maxPlayers: none
                """);

        BlueprintRepository repository = new BlueprintRepository(temporaryDirectory);
        repository.reload();

        Blueprint blueprint = repository.get("invalid-vsls").orElseThrow();
        assertEquals(20, blueprint.maxPlayers());
        assertEquals(1, blueprint.maxInstances());
        assertFalse(BlueprintLifecyclePolicy.from(blueprint, 180).keepAlive());
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
    void loadsImagePathAndShorthandVolumesWithoutSourceWorlds() throws Exception {
        write("archives/slsmp1.yml", """
                blueprint:
                  id: slsmp1
                  name: SLSMP1
                  type: archive
                server:
                  software: paper
                  version: "1.21.1"
                  image: java_21
                  path: vanilla/1.21.1
                state:
                  volumes:
                    - "world:worlds/archives/SLSMP1:/world:cow"
                    - "nether:worlds/archives/SLSMP1/DIM-1:/world_nether/DIM-1"
                """);

        BlueprintRepository repository = new BlueprintRepository(temporaryDirectory);
        repository.reload();

        Blueprint blueprint = repository.get("slsmp1").orElseThrow();
        assertEquals("java_21", blueprint.image());
        assertEquals("vanilla/1.21.1", blueprint.softwarePath());
        assertEquals(2, blueprint.volumes().size());
        assertEquals("worlds/archives/SLSMP1", blueprint.volumes().get(0).source());
        assertEquals(BlueprintVolume.Mode.COW, blueprint.volumes().get(1).mode());
    }

    @Test
    void loadsNestedYamlConfigPatches() throws Exception {
        write("archive.yml", """
                blueprint:
                  id: archive
                  name: Archive
                  type: archive
                server:
                  software: paper
                  version: "1.21.1"
                  configs:
                    bukkit.yml:
                      parser: yaml
                      find:
                        settings:
                          allow-end: true
                """);

        BlueprintRepository repository = new BlueprintRepository(temporaryDirectory);
        repository.reload();

        Map<String, Object> bukkit = repository.get("archive")
                .orElseThrow()
                .yamlConfigs()
                .get("bukkit.yml");
        @SuppressWarnings("unchecked")
        Map<String, Object> settings = (Map<String, Object>) bukkit.get("settings");
        assertEquals(true, settings.get("allow-end"));
    }

    @Test
    void rejectsUnsafeSoftwarePathAndMalformedVolumeShorthand() throws Exception {
        write("unsafe-path.yml", """
                blueprint:
                  id: unsafe-path
                  name: Unsafe Path
                  type: archive
                server:
                  software: paper
                  version: "1.21.1"
                  path: ../outside
                """);

        BlueprintException unsafePath = assertThrows(
                BlueprintException.class,
                () -> new BlueprintRepository(temporaryDirectory).reload()
        );
        assertTrue(unsafePath.getMessage().contains("server.path"));

        Files.delete(temporaryDirectory.resolve("unsafe-path.yml"));
        write("bad-volume.yml", """
                blueprint:
                  id: bad-volume
                  name: Bad Volume
                  type: archive
                server:
                  software: paper
                  version: "1.21.1"
                state:
                  volumes:
                    - "world:missing-target"
                """);

        BlueprintException badVolume = assertThrows(
                BlueprintException.class,
                () -> new BlueprintRepository(temporaryDirectory).reload()
        );
        assertTrue(badVolume.getMessage().contains("name:source:target[:mode]"));
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
