package net.slimelabs.slslite.blueprint;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class BlueprintParserTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void parsesSingleDocumentWithLocalDefaults() throws Exception {
        Path source = temporaryDirectory.resolve("game.yml");
        Files.writeString(source, """
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
        Files.writeString(source, """
                blueprint:
                  id: game
                  name: Game
                  type: game
                server:
                  software: paper
                  version: 1.21.11
                unsupported: true
                """);

        BlueprintException failure = assertThrows(
                BlueprintException.class,
                () -> new BlueprintParser().parse(source)
        );

        assertTrue(failure.getMessage().contains(source.toString()));
        assertTrue(failure.getMessage().contains("unsupported"));
    }
}
