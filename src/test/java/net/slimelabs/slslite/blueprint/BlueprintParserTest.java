package net.slimelabs.slslite.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class BlueprintParserTest {

  @TempDir Path temporaryDirectory;

  @Test
  void parsesSingleDocumentWithUpstreamCapacityDefaults() throws Exception {
    Path source = temporaryDirectory.resolve("game.yml");
    Files.writeString(
        source,
        """
                blueprint:
                  id: game
                  name: Game
                  type: game
                server:
                  software: paper
                  version: 1.21.11
                """);

    Blueprint blueprint = new BlueprintParser().parse(source);

    assertEquals("game", blueprint.id());
    assertEquals(1024, blueprint.memoryLimitMiB());
    assertEquals(10_000, blueprint.maxPlayers());
    assertEquals(Integer.MAX_VALUE, blueprint.maxInstances());
    assertTrue(blueprint.inheritsSoftwareMemory());
    assertTrue(blueprint.inheritsSoftwareImage());
  }

  @Test
  void validationErrorIdentifiesSourceDocument() throws Exception {
    Path source = temporaryDirectory.resolve("invalid.yml");
    Files.writeString(
        source,
        """
                blueprint:
                  id: game
                  name: Game
                  type: game
                server:
                  software: paper
                  version: 1.21.11
                unsupported: true
                """);

    BlueprintException failure =
        assertThrows(BlueprintException.class, () -> new BlueprintParser().parse(source));

    assertTrue(failure.getMessage().contains(source.toString()));
    assertTrue(failure.getMessage().contains("unsupported"));
  }

  @Test
  void rejectsBlueprintLargerThanRepositoryLimit() throws Exception {
    Path source = temporaryDirectory.resolve("oversized.yml");
    Files.write(source, new byte[BlueprintParser.MAX_BLUEPRINT_BYTES + 1]);

    BlueprintException failure =
        assertThrows(BlueprintException.class, () -> new BlueprintParser().parse(source));

    assertTrue(failure.getMessage().contains("Unable to read blueprint"));
  }

  @Test
  void memoryLimitAcceptsOnlyPositiveNumericMibIntegers() throws Exception {
    Path source = temporaryDirectory.resolve("memory.yml");
    Files.writeString(source, blueprintWithMemory("2048"));
    assertEquals(2048, new BlueprintParser().parse(source).memoryLimitMiB());

    for (String invalid : java.util.List.of("\"2048\"", "2.5", "0", "-1", "\"2G\"", "true")) {
      Files.writeString(source, blueprintWithMemory(invalid));
      BlueprintException failure =
          assertThrows(BlueprintException.class, () -> new BlueprintParser().parse(source));
      assertTrue(failure.getMessage().contains("memory_limit"));
    }
  }

  private static String blueprintWithMemory(String memory) {
    return """
        blueprint:
          id: memory
          name: Memory
          type: test
        server:
          software: paper
          version: "1.21.11"
          limits:
            memory_limit: %s
        """
        .formatted(memory);
  }

  @Test
  void rwVolumesRemainExplicitlySharedAcrossLifecyclePolicies() throws Exception {
    Path source = temporaryDirectory.resolve("rw.yml");
    Files.writeString(source, blueprintWithRw(false, 1));
    assertEquals(
        BlueprintVolume.Mode.RW, new BlueprintParser().parse(source).volumes().getFirst().mode());

    Files.writeString(source, blueprintWithRw(true, 2));
    assertEquals(
        BlueprintVolume.Mode.RW, new BlueprintParser().parse(source).volumes().getFirst().mode());
  }

  @Test
  void parsesExplicitPersistentFileMappings() throws Exception {
    Path source = temporaryDirectory.resolve("persistent-files.yml");
    Files.writeString(
        source,
        """
        blueprint:
          id: lobby
          name: Lobby
          type: lobby
        server:
          software: paper
          version: "1.21.11"
        state:
          persistent_files:
            - name: whitelist
              source: volumes/whitelists/lobby/whitelist.json
              target: whitelist.json
        """);

    BlueprintPersistentFile file = new BlueprintParser().parse(source).persistentFiles().getFirst();

    assertEquals("whitelist", file.name());
    assertEquals("volumes/whitelists/lobby/whitelist.json", file.source());
    assertEquals("whitelist.json", file.target());
  }

  @Test
  void rejectsDuplicatePersistentSourcesAndTargetsPortably() throws Exception {
    Path source = temporaryDirectory.resolve("duplicates.yml");
    Files.writeString(
        source,
        """
        blueprint:
          id: lobby
          name: Lobby
          type: lobby
        server:
          software: paper
          version: "1.21.11"
        state:
          persistent_files:
            - name: whitelist
              source: volumes/state/whitelist.json
              target: whitelist.json
            - name: operators
              source: VOLUMES/STATE/WHITELIST.JSON
              target: ops.json
        """);

    BlueprintException failure =
        assertThrows(BlueprintException.class, () -> new BlueprintParser().parse(source));

    assertTrue(failure.getMessage().contains("duplicate persistent file source"));
  }

  @Test
  void confinesPersistentSourcesAndReservesSupervisorTargets() throws Exception {
    Path source = temporaryDirectory.resolve("unsafe-persistent-file.yml");
    String template =
        """
        blueprint:
          id: lobby
          name: Lobby
          type: lobby
        server:
          software: paper
          version: "1.21.11"
        state:
          persistent_files:
            - name: unsafe
              source: %s
              target: %s
        """;
    Files.writeString(source, template.formatted("config.yml", "whitelist.json"));
    BlueprintException unsafeSource =
        assertThrows(BlueprintException.class, () -> new BlueprintParser().parse(source));
    assertTrue(unsafeSource.getMessage().contains("below volumes/"));

    Files.writeString(
        source,
        template.formatted("volumes/whitelists/whitelist.json", ".sls-lite-instance.properties"));
    BlueprintException reservedTarget =
        assertThrows(BlueprintException.class, () -> new BlueprintParser().parse(source));
    assertTrue(reservedTarget.getMessage().contains("reserved SLS-LITE path"));
  }

  private static String blueprintWithRw(boolean save, int maxInstances) {
    return """
        blueprint:
          id: shared
          name: Shared
          type: service
        server:
          software: paper
          version: "1.21.11"
          limits:
            max_instances: %d
        save: %s
        state:
          volumes:
            - name: shared
              source: shared/data
              target: /data
              mode: rw
        """
        .formatted(maxInstances, save);
  }
}
