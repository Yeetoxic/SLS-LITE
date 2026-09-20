package net.slimelabs.slslite.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class MixinResolveTest {

  @TempDir Path temporaryDirectory;

  @Test
  void classifiesMixinBlueprintAmbiguousAndUnknownDocuments() throws Exception {
    write(
        "mixin.yml",
        """
        mixin:
          id: shared
        """);
    write(
        "blueprint.yml",
        """
        blueprint:
          id: game
          name: Game
          type: game
        server:
          software: paper
          version: "1.21.11"
        """);
    write(
        "both.yml",
        """
        blueprint:
          id: a
          name: A
          type: game
        mixin:
          id: b
        """);
    write(
        "neither.yml",
        """
        server:
          software: paper
        """);

    BlueprintRepository.LoadResult result = repository().loadIsolated();
    assertTrue(result.accepted().containsKey("game"));
    assertTrue(result.acceptedMixins().containsKey("shared"));
    assertTrue(
        result.rejections().stream()
            .anyMatch(rejection -> rejection.error().contains("both mixin: and blueprint:")));
    assertTrue(
        result.rejections().stream()
            .anyMatch(rejection -> rejection.error().contains("neither mixin: nor blueprint:")));
  }

  @Test
  void flattensExtendsAndAppliesIncludesWithLaterFieldsWinning() throws Exception {
    write(
        "base.yml",
        """
        mixin:
          id: base
        server:
          software: paper
          limits:
            memory_limit: 2048
        state:
          env:
            A: "1"
            B: "1"
        """);
    write(
        "child.yml",
        """
        mixin:
          id: child
        extends:
          - base
        server:
          version: "1.21.11"
          limits:
            max_instances: 3
        state:
          env:
            B: "2"
        """);
    write(
        "game.yml",
        """
        blueprint:
          id: bp
          name: BP
          type: game
        includes:
          - child
        server:
          image: java_21
        state:
          env:
            C: "3"
        """);

    BlueprintRepository.LoadResult result = repository().loadIsolated();
    Blueprint blueprint = result.accepted().get("bp").blueprint();
    assertEquals("paper", blueprint.software());
    assertEquals("1.21.11", blueprint.version());
    assertEquals("java_21", blueprint.image());
    assertEquals(2048, blueprint.memoryLimitMiB());
    assertEquals(3, blueprint.maxInstances());
    assertEquals("1", blueprint.environment().get("A"));
    assertEquals("2", blueprint.environment().get("B"));
    assertEquals("3", blueprint.environment().get("C"));
    assertEquals(List.of("child"), blueprint.includes());
    Mixin child = result.acceptedMixins().get("child").mixin();
    assertEquals(List.of("base"), child.extendsMixins());
    assertEquals("paper", child.overlay().server().software());
    assertEquals("1.21.11", child.overlay().server().version());
  }

  @Test
  void deepMergesNestedAnnotationsWithoutMutatingInputs() {
    Map<String, Object> baseVsls = new LinkedHashMap<>();
    baseVsls.put("max-instances", 4);
    Map<String, Object> base = new LinkedHashMap<>();
    base.put("vsls", baseVsls);
    Map<String, Object> overlayVsls = new LinkedHashMap<>();
    overlayVsls.put("dont-stop-when-empty", true);
    Map<String, Object> overlay = new LinkedHashMap<>();
    overlay.put("vsls", overlayVsls);

    Map<String, Object> merged = OverlayMerger.mergeAnnotations(base, overlay);
    @SuppressWarnings("unchecked")
    Map<String, Object> vsls = (Map<String, Object>) merged.get("vsls");
    assertEquals(4, vsls.get("max-instances"));
    assertEquals(true, vsls.get("dont-stop-when-empty"));
    assertFalse(baseVsls.containsKey("dont-stop-when-empty"));
    assertFalse(overlayVsls.containsKey("max-instances"));
  }

  @Test
  void deepMergesVslsAndSlsLiteAnnotationsThroughIncludes() throws Exception {
    write(
        "vsls-base.yml",
        """
        mixin:
          id: vsls-base
        server:
          software: paper
          version: "1.21.11"
        annotations:
          vsls:
            dont-stop-when-empty: true
            max-instances: 4
            on-join:
              - run: say from mixin
          sls-lite:
            keep-alive: true
          maintainer: mixin
        """);
    write(
        "game.yml",
        """
        blueprint:
          id: bp
          name: BP
          type: game
        includes:
          - vsls-base
        annotations:
          vsls:
            max-instances: 1
            matchmaking:
              maxPlayers: 8
          sls-lite:
            max-players: 12
          maintainer: blueprint
        """);

    Blueprint blueprint = repository().loadIsolated().accepted().get("bp").blueprint();
    @SuppressWarnings("unchecked")
    Map<String, Object> vsls = (Map<String, Object>) blueprint.annotations().get("vsls");
    assertEquals(true, vsls.get("dont-stop-when-empty"));
    assertEquals(1, vsls.get("max-instances"));
    @SuppressWarnings("unchecked")
    Map<String, Object> matchmaking = (Map<String, Object>) vsls.get("matchmaking");
    assertEquals(8, matchmaking.get("maxPlayers"));
    assertEquals(1, ((List<?>) vsls.get("on-join")).size());
    assertEquals("blueprint", blueprint.annotations().get("maintainer"));
    assertEquals(12, blueprint.maxPlayers());
    assertTrue(BlueprintLifecyclePolicy.from(blueprint, 180).keepAlive());
  }

  @Test
  void rejectsMixinCyclesAndDependentBlueprints() throws Exception {
    write(
        "a.yml",
        """
        mixin:
          id: mixin_configs
        extends:
          - mixin_plugins
        """);
    write(
        "b.yml",
        """
        mixin:
          id: mixin_plugins
        extends:
          - mixin_configs
        """);
    write(
        "game.yml",
        """
        blueprint:
          id: bp
          name: BP
          type: game
        includes:
          - mixin_configs
        server:
          software: paper
          version: "1.21.11"
        """);

    BlueprintRepository.LoadResult result = repository().loadIsolated();
    assertTrue(result.acceptedMixins().isEmpty());
    assertFalse(result.accepted().containsKey("bp"));
    assertTrue(
        result.rejections().stream()
            .anyMatch(rejection -> rejection.error().contains("mixin inheritance cycle detected")));
    assertTrue(
        result.rejections().stream()
            .anyMatch(rejection -> rejection.error().contains("includes unknown mixin")));
  }

  @Test
  void formatMixinCycleReportsTheLoopPath() {
    assertEquals(
        "mixin_configs -> mixin_plugins -> mixin_configs",
        DefinitionResolver.formatMixinCycle(
            List.of("mixin_configs", "mixin_plugins"), "mixin_configs"));
    assertEquals("b -> c -> b", DefinitionResolver.formatMixinCycle(List.of("a", "b", "c"), "b"));
  }

  @Test
  void rejectsUnknownIncludesDuplicateIdsAndDuplicateExtends() throws Exception {
    write(
        "game.yml",
        """
        blueprint:
          id: bp
          name: BP
          type: game
        includes:
          - missing
        server:
          software: paper
          version: "1.21.11"
        """);
    write(
        "dup-a.yml",
        """
        mixin:
          id: shared
        """);
    write(
        "dup-b.yml",
        """
        mixin:
          id: shared
        """);
    write(
        "extends.yml",
        """
        mixin:
          id: child
        extends:
          - shared
          - shared
        """);
    write(
        "orphan.yml",
        """
        mixin:
          id: orphan
        extends:
          - missing_parent
        """);

    BlueprintRepository.LoadResult result = repository().loadIsolated();
    assertTrue(
        result.rejections().stream()
            .anyMatch(rejection -> rejection.error().contains("includes unknown mixin")));
    assertTrue(
        result.rejections().stream()
            .anyMatch(rejection -> rejection.error().contains("Duplicate mixin id 'shared'")));
    assertTrue(
        result.rejections().stream()
            .anyMatch(rejection -> rejection.error().contains("duplicate mixin id \"shared\"")));
    assertTrue(
        result.rejections().stream()
            .anyMatch(rejection -> rejection.error().contains("unknown mixin \"missing_parent\"")));
  }

  @Test
  void replacesWholeConfigFilesAndMergesVolumesCopiesAndPersistentFiles() throws Exception {
    write(
        "plugins.yml",
        """
        mixin:
          id: plugins
        server:
          configs:
            server.properties:
              parser: properties
              find:
                motd: mixin
                max-players: 10
        state:
          volumes:
            - name: world
              source: volumes/worlds/base
              target: /world
              mode: cow
            - name: plugins
              source: volumes/plugins/shared
              target: /plugins
              mode: ro
          copy:
            - volumes/plugins/Shared.jar:plugins/Shared.jar
          persistent_files:
            - name: whitelist
              source: volumes/whitelists/mixin/whitelist.json
              target: whitelist.json
            - name: ops
              source: volumes/ops/mixin/ops.json
              target: ops.json
        """);
    write(
        "game.yml",
        """
        blueprint:
          id: arcade
          name: Arcade
          type: minigame
        includes:
          - plugins
        server:
          software: paper
          version: "1.21.11"
          configs:
            server.properties:
              parser: properties
              find:
                motd: arcade
        state:
          volumes:
            - name: world
              source: volumes/worlds/arcade
              target: /world
              mode: cow
          copy:
            - volumes/plugins/Arcade.jar:plugins/Arcade.jar
          persistent_files:
            - name: whitelist
              source: volumes/whitelists/arcade/whitelist.json
              target: whitelist.json
        """);

    Blueprint blueprint = repository().loadIsolated().accepted().get("arcade").blueprint();
    assertEquals("arcade", blueprint.serverProperties().get("motd"));
    assertNull(blueprint.serverProperties().get("max-players"));
    assertEquals(2, blueprint.volumes().size());
    assertEquals("volumes/worlds/arcade", blueprint.volumes().getFirst().source());
    assertEquals("plugins", blueprint.volumes().get(1).name());
    assertEquals(2, blueprint.copies().size());
    assertEquals("plugins/Shared.jar", blueprint.copies().getFirst().target());
    assertEquals("plugins/Arcade.jar", blueprint.copies().get(1).target());
    assertEquals(2, blueprint.persistentFiles().size());
    assertEquals(
        "volumes/whitelists/arcade/whitelist.json",
        blueprint.persistentFiles().getFirst().source());
    assertEquals("ops", blueprint.persistentFiles().get(1).name());
  }

  @Test
  void rejectsSaveOnMixinsAndKeepsBlueprintSave() throws Exception {
    write(
        "saved.yml",
        """
        mixin:
          id: saved
        save: true
        """);
    write(
        "limits.yml",
        """
        mixin:
          id: limits
        server:
          software: paper
          version: "1.21.11"
        """);
    write(
        "game.yml",
        """
        blueprint:
          id: arcade
          name: Arcade
          type: minigame
        includes:
          - limits
        save: true
        """);

    BlueprintRepository.LoadResult result = repository().loadIsolated();
    assertTrue(
        result.rejections().stream().anyMatch(rejection -> rejection.path().equals("saved.yml")));
    Blueprint blueprint = result.accepted().get("arcade").blueprint();
    assertTrue(blueprint.save());
  }

  @Test
  void rejectsMountsOnMixins() throws Exception {
    write(
        "mounted.yml",
        """
        mixin:
          id: mounted
        state:
          mounts:
            - source: volumes/worlds/shared
              target: /world
        """);

    BlueprintRepository.LoadResult result = repository().loadIsolated();
    assertTrue(
        result.rejections().stream()
            .anyMatch(rejection -> rejection.error().contains("'state.mounts' is not available")));
  }

  @Test
  void allowsBlueprintsToOmitServerWhenAMixinSuppliesSoftwareAndVersion() throws Exception {
    write(
        "paper.yml",
        """
        mixin:
          id: paper_base
        server:
          software: paper
          version: "1.21.11"
        """);
    write(
        "game.yml",
        """
        blueprint:
          id: arcade
          name: Arcade
          type: minigame
        includes:
          - paper_base
        """);

    Blueprint blueprint = repository().loadIsolated().accepted().get("arcade").blueprint();
    assertEquals("paper", blueprint.software());
    assertEquals("1.21.11", blueprint.version());
    assertTrue(blueprint.inheritsSoftwareMemory());
    assertTrue(blueprint.inheritsSoftwareImage());
  }

  @Test
  void parseHelperRejectsIncludesWithoutRepositoryResolution() throws Exception {
    Path source = temporaryDirectory.resolve("included.yml");
    Files.writeString(
        source,
        """
        blueprint:
          id: arcade
          name: Arcade
          type: minigame
        includes:
          - paper_base
        server:
          software: paper
          version: "1.21.11"
        """);
    BlueprintException failure =
        org.junit.jupiter.api.Assertions.assertThrows(
            BlueprintException.class, () -> new BlueprintParser().parse(source));
    assertTrue(failure.getMessage().contains("includes require mixin resolution"));
  }

  private BlueprintRepository repository() {
    return new BlueprintRepository(temporaryDirectory);
  }

  private void write(String name, String contents) throws Exception {
    Path file = temporaryDirectory.resolve(name);
    Files.createDirectories(file.getParent());
    Files.writeString(file, contents);
  }
}
