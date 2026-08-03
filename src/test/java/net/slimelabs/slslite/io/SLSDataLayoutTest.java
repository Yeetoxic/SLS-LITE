package net.slimelabs.slslite.io;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class SLSDataLayoutTest {

  @TempDir Path temporaryDirectory;

  @Test
  void createsEmptyOperatorVolumeDirectories() throws Exception {
    Path dataDirectory = temporaryDirectory.resolve("sls-lite");

    SLSDataLayout.initialize(dataDirectory);

    assertTrue(Files.isDirectory(dataDirectory.resolve("volumes/worlds")));
    assertTrue(Files.isDirectory(dataDirectory.resolve("volumes/plugins")));
    try (var children = Files.list(dataDirectory.resolve("volumes"))) {
      assertEquals(
          Set.of("worlds", "plugins"),
          children.map(path -> path.getFileName().toString()).collect(Collectors.toSet()));
    }
  }

  @Test
  void repeatedInitializationPreservesOperatorContent() throws Exception {
    Path dataDirectory = temporaryDirectory.resolve("sls-lite");
    SLSDataLayout.initialize(dataDirectory);
    Path plugin = dataDirectory.resolve("volumes/plugins/example.jar");
    Files.writeString(plugin, "fixture");

    SLSDataLayout.initialize(dataDirectory);

    assertEquals("fixture", Files.readString(plugin));
  }

  @Test
  void refusesToReplaceAnExistingManagedPath() throws Exception {
    Path dataDirectory = temporaryDirectory.resolve("sls-lite");
    Files.createDirectories(dataDirectory);
    Files.writeString(dataDirectory.resolve("volumes"), "not a directory");

    assertThrows(IOException.class, () -> SLSDataLayout.initialize(dataDirectory));
    assertEquals("not a directory", Files.readString(dataDirectory.resolve("volumes")));
  }
}
