package net.slimelabs.slslite.blueprint;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
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
                "fixtures/compatibility/sls-v0.2.0/example_vsls.yml",
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
                List.of(
                        "say hello {PLAYER_NAME}",
                        "playsound minecraft:block.note_block.bell ambient "
                                + "{PLAYER_NAME} ~ ~ ~ 1000 0"
                ),
                VSLSBlueprintAnnotations.onJoinCommands(blueprint.annotations())
        );
        assertEquals(
                Duration.ofSeconds(180),
                BlueprintLifecyclePolicy.from(blueprint, 180).idleTimeout()
        );
        assertTrue(blueprint.annotations().containsKey("vsls"));
    }

    @Test
    void loadsPinnedModernVolumeContractFixture() throws Exception {
        copyResource(
                "fixtures/compatibility/sls-v0.2.0/example_volumes.yml",
                "compatibility/example_volumes.yml"
        );

        BlueprintRepository repository = new BlueprintRepository(temporaryDirectory);
        repository.reload();

        Blueprint blueprint = repository.get("volume-contract").orElseThrow();
        assertEquals(5, blueprint.volumes().size());
        assertEquals("plugins", blueprint.volumes().get(1).name());
        assertEquals("plugins", blueprint.volumes().get(2).name());
        assertEquals("/plugins", blueprint.volumes().get(1).target());
        assertEquals("/plugins", blueprint.volumes().get(2).target());
        assertEquals(BlueprintVolume.Mode.RO, blueprint.volumes().get(3).mode());
        assertEquals(BlueprintVolume.Mode.RW, blueprint.volumes().get(4).mode());
    }

    @Test
    void loadsPinnedModernStateContractFixture() throws Exception {
        copyResource(
                "fixtures/compatibility/sls-v0.2.0/example_state.yml",
                "compatibility/example_state.yml"
        );

        BlueprintRepository repository = new BlueprintRepository(temporaryDirectory);
        repository.reload();

        Blueprint blueprint = repository.get("state-contract").orElseThrow();
        assertEquals(2, blueprint.copies().size());
        assertEquals(
                "plugins/example/config.yml",
                blueprint.copies().get(0).target()
        );
        assertEquals("true", blueprint.environment().get("FEATURE_FLAG"));
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
    void rejectsInvalidVSLSAnnotationTypes() throws Exception {
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

        BlueprintException exception = assertThrows(
                BlueprintException.class,
                () -> new BlueprintRepository(temporaryDirectory).reload()
        );

        assertTrue(exception.getMessage().contains(
                "annotations.vsls.dont-stop-when-empty must be a boolean"
        ));
    }

    @Test
    void rejectsFractionalAndOverflowingVSLSCapacityAnnotations() throws Exception {
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
                    max-instances: 1.5
                """);

        BlueprintException fractional = assertThrows(
                BlueprintException.class,
                () -> new BlueprintRepository(temporaryDirectory).reload()
        );
        assertTrue(fractional.getMessage().contains(
                "annotations.vsls.max-instances must be a positive integer"
        ));

        Files.delete(temporaryDirectory.resolve("invalid-vsls.yml"));
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
                    matchmaking:
                      maxPlayers: 2147483648
                """);

        BlueprintException overflow = assertThrows(
                BlueprintException.class,
                () -> new BlueprintRepository(temporaryDirectory).reload()
        );
        assertTrue(overflow.getMessage().contains(
                "annotations.vsls.matchmaking.maxPlayers must be between 1"
        ));
    }

    @Test
    void rejectsMalformedVSLSNamespaceAndGameType() throws Exception {
        write("invalid-vsls.yml", """
                blueprint:
                  id: invalid-vsls
                  name: Invalid vSLS annotations
                  type: minigame
                server:
                  software: paper
                  version: "1.21.1"
                annotations:
                  vsls: invalid
                """);

        BlueprintException namespace = assertThrows(
                BlueprintException.class,
                () -> new BlueprintRepository(temporaryDirectory).reload()
        );
        assertTrue(namespace.getMessage().contains(
                "annotations.vsls must be an object"
        ));

        Files.delete(temporaryDirectory.resolve("invalid-vsls.yml"));
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
                    matchmaking:
                      gameType: 42
                """);

        BlueprintException gameType = assertThrows(
                BlueprintException.class,
                () -> new BlueprintRepository(temporaryDirectory).reload()
        );
        assertTrue(gameType.getMessage().contains(
                "annotations.vsls.matchmaking.gameType must be a non-blank string"
        ));
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
    void loadsModernTextFileConfigPatches() throws Exception {
        write("survival.yml", """
                blueprint:
                  id: survival
                  name: Survival
                  type: survival
                server:
                  software: paper
                  version: "1.21.11"
                  configs:
                    whitelist.json:
                      parser: file
                      find:
                        "[]": '[{"name":"protoxon"}]'
                """);

        BlueprintRepository repository = new BlueprintRepository(temporaryDirectory);
        repository.reload();

        assertEquals(
                Map.of("[]", "[{\"name\":\"protoxon\"}]"),
                repository.get("survival")
                        .orElseThrow()
                        .textFileConfigs()
                        .get("whitelist.json")
        );
    }

    @Test
    void loadsModernStateCopyAndEnvironment() throws Exception {
        write("copy-env.yml", """
                blueprint:
                  id: copy-env
                  name: Copy And Env
                  type: compatibility
                server:
                  software: paper
                  version: "1.21.11"
                state:
                  copy:
                    - source: files/config.yml
                      target: plugins/example/config.yml
                    - "files/icon.png:server-icon.png"
                  env:
                    FEATURE_FLAG: "true"
                    PUBLIC_ENDPOINT: "https://example.test"
                """);

        BlueprintRepository repository = new BlueprintRepository(temporaryDirectory);
        repository.reload();
        Blueprint blueprint = repository.get("copy-env").orElseThrow();

        assertEquals(2, blueprint.copies().size());
        assertEquals("files/config.yml", blueprint.copies().get(0).source());
        assertEquals("server-icon.png", blueprint.copies().get(1).target());
        assertEquals(
                Map.of(
                        "FEATURE_FLAG", "true",
                        "PUBLIC_ENDPOINT", "https://example.test"
                ),
                blueprint.environment()
        );
    }

    @Test
    void rejectsDangerousOrMalformedStateEnvironment() throws Exception {
        write("unsafe-env.yml", """
                blueprint:
                  id: unsafe-env
                  name: Unsafe Env
                  type: compatibility
                server:
                  software: paper
                  version: "1.21.11"
                state:
                  env:
                    JAVA_TOOL_OPTIONS: "-javaagent:untrusted.jar"
                """);

        BlueprintException protectedVariable = assertThrows(
                BlueprintException.class,
                () -> new BlueprintRepository(temporaryDirectory).reload()
        );
        assertTrue(protectedVariable.getMessage().contains(
                "environment variable is protected"
        ));

        Files.delete(temporaryDirectory.resolve("unsafe-env.yml"));
        write("unsafe-env.yml", """
                blueprint:
                  id: unsafe-env
                  name: Unsafe Env
                  type: compatibility
                server:
                  software: paper
                  version: "1.21.11"
                state:
                  env:
                    INVALID-NAME: "value"
                """);

        BlueprintException invalidName = assertThrows(
                BlueprintException.class,
                () -> new BlueprintRepository(temporaryDirectory).reload()
        );
        assertTrue(invalidName.getMessage().contains(
                "Invalid blueprint environment variable name"
        ));
    }

    @Test
    void rejectsUnsafeStateCopyPaths() throws Exception {
        write("unsafe-copy.yml", """
                blueprint:
                  id: unsafe-copy
                  name: Unsafe Copy
                  type: compatibility
                server:
                  software: paper
                  version: "1.21.11"
                state:
                  copy:
                    - "../outside.txt:plugins/outside.txt"
                """);

        BlueprintException exception = assertThrows(
                BlueprintException.class,
                () -> new BlueprintRepository(temporaryDirectory).reload()
        );
        assertTrue(exception.getMessage().contains("contained relative path"));
    }

    @Test
    void rejectsUnsafeTextFileReplacementDefinitions() throws Exception {
        write("survival.yml", """
                blueprint:
                  id: survival
                  name: Survival
                  type: survival
                server:
                  software: paper
                  version: "1.21.11"
                  configs:
                    whitelist.json:
                      parser: file
                      find:
                        "": replacement
                """);

        BlueprintException emptyPrefix = assertThrows(
                BlueprintException.class,
                () -> new BlueprintRepository(temporaryDirectory).reload()
        );
        assertTrue(emptyPrefix.getMessage().contains("non-empty single-line prefixes"));

        Files.delete(temporaryDirectory.resolve("survival.yml"));
        write("survival.yml", """
                blueprint:
                  id: survival
                  name: Survival
                  type: survival
                server:
                  software: paper
                  version: "1.21.11"
                  configs:
                    whitelist.json:
                      parser: file
                      find:
                        "[]":
                          invalid: object
                """);

        BlueprintException objectValue = assertThrows(
                BlueprintException.class,
                () -> new BlueprintRepository(temporaryDirectory).reload()
        );
        assertTrue(objectValue.getMessage().contains(
                "must be a string, number, or boolean"
        ));

        Files.delete(temporaryDirectory.resolve("survival.yml"));
        write("survival.yml", """
                blueprint:
                  id: survival
                  name: Survival
                  type: survival
                server:
                  software: paper
                  version: "1.21.11"
                  configs:
                    server.txt:
                      parser: file
                      find:
                        "server-": first
                        "server-port=": second
                """);

        BlueprintException overlapping = assertThrows(
                BlueprintException.class,
                () -> new BlueprintRepository(temporaryDirectory).reload()
        );
        assertTrue(overlapping.getMessage().contains("prefixes overlap"));
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
    void loadsModernVolumeModes() throws Exception {
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
                      mode: ro
                    - data:worlds/survival/data:/data:rw
                    - plugins:plugins/common:/plugins
                """);

        BlueprintRepository repository = new BlueprintRepository(temporaryDirectory);
        repository.reload();
        Blueprint blueprint = repository
                .getAll()
                .iterator()
                .next();

        assertEquals(BlueprintVolume.Mode.RO, blueprint.volumes().get(0).mode());
        assertEquals(BlueprintVolume.Mode.RW, blueprint.volumes().get(1).mode());
        assertEquals(BlueprintVolume.Mode.COW, blueprint.volumes().get(2).mode());
    }

    @Test
    void rejectsUnknownVolumeMode() throws Exception {
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
                      mode: bind
                """);

        BlueprintException exception = assertThrows(
                BlueprintException.class,
                () -> new BlueprintRepository(temporaryDirectory).reload()
        );

        assertTrue(exception.getMessage().contains("must be cow, ro, or rw"));
    }

    @Test
    void allowsRepeatedVolumeNamesForModernCowMerges() throws Exception {
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
                      target: /world
                      mode: cow
                """);

        BlueprintRepository repository = new BlueprintRepository(temporaryDirectory);
        repository.reload();
        Blueprint blueprint = repository
                .getAll()
                .iterator()
                .next();

        assertEquals(2, blueprint.volumes().size());
        assertEquals("world", blueprint.volumes().get(0).name());
        assertEquals("world", blueprint.volumes().get(1).name());
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
    void reportsTheFullPathForMalformedLimitsSection() throws Exception {
        write("malformed-limits.yml", """
                blueprint:
                  id: malformed-limits
                  name: Malformed Limits
                  type: test
                server:
                  software: paper
                  version: "1.21.11"
                  limits: invalid
                """);
        BlueprintRepository repository =
                new BlueprintRepository(temporaryDirectory);

        BlueprintException exception = assertThrows(
                BlueprintException.class,
                repository::reload
        );

        assertTrue(exception.getMessage().contains("server.limits"));
        assertTrue(exception.getMessage().contains("must be an object"));
    }

    @Test
    void preservesNullValuesInArbitraryAnnotations() throws Exception {
        write("annotations.yml", """
                blueprint:
                  id: annotations
                  name: Annotations
                  type: game
                server:
                  software: paper
                  version: "1.21.11"
                annotations:
                  integration:
                    optional: null
                    values:
                      - first
                      - null
                """);

        BlueprintRepository repository = new BlueprintRepository(temporaryDirectory);
        repository.reload();

        Map<?, ?> integration = (Map<?, ?>) repository.get("annotations")
                .orElseThrow()
                .annotations()
                .get("integration");
        assertTrue(integration.containsKey("optional"));
        assertEquals(null, integration.get("optional"));
        assertEquals(
                java.util.Arrays.asList("first", null),
                integration.get("values")
        );
    }

    @Test
    void rejectsHostMountsWithALocalModeAlternative() throws Exception {
        write("mounts.yml", """
                blueprint:
                  id: mounts
                  name: Mounts
                  type: game
                server:
                  software: paper
                  version: "1.21.11"
                state:
                  mounts:
                    - /host/path:/home/container:ro
                """);

        BlueprintRepository repository = new BlueprintRepository(temporaryDirectory);
        BlueprintException exception = assertThrows(
                BlueprintException.class,
                repository::reload
        );

        assertTrue(exception.getMessage().contains("not available in local mode"));
        assertTrue(exception.getMessage().contains("mode cow or ro"));
    }

    @Test
    void rejectsUnsafeOnJoinCommandShape() throws Exception {
        write("unsafe-on-join.yml", """
                blueprint:
                  id: unsafe-on-join
                  name: Unsafe on-join
                  type: game
                server:
                  software: paper
                  version: "1.21.11"
                annotations:
                  vsls:
                    on-join:
                      - run: |-
                          say first
                          say second
                """);

        BlueprintRepository repository = new BlueprintRepository(temporaryDirectory);
        BlueprintException exception = assertThrows(
                BlueprintException.class,
                repository::reload
        );

        assertTrue(exception.getMessage().contains("on-join[0].run"));
        assertTrue(exception.getMessage().contains("one line"));
    }

    @Test
    void readsMatchmakingGameTypeWithoutChangingRegistryType() throws Exception {
        write("pool.yml", """
                blueprint:
                  id: pool-map
                  name: Pool Map
                  type: minigame
                server:
                  software: paper
                  version: "1.21.11"
                annotations:
                  vsls:
                    matchmaking:
                      gameType: party
                """);

        BlueprintRepository repository = new BlueprintRepository(temporaryDirectory);
        repository.reload();

        Blueprint blueprint = repository.get("pool-map").orElseThrow();
        assertEquals("minigame", blueprint.type());
        assertEquals(
                "party",
                VSLSBlueprintAnnotations.gameType(blueprint.annotations()).orElseThrow()
        );
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
