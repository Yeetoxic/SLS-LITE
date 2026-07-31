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
  void parsesSingleDocumentWithLocalDefaults() throws Exception {
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
    assertEquals(20, blueprint.maxPlayers());
    assertEquals(1, blueprint.maxInstances());
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
