package net.slimelabs.slslite.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

class BlueprintCorpusCompatibilityIT {

  @Test
  void loadsConfiguredModernBlueprintCorpus() throws Exception {
    String configured = System.getProperty("sls.compatibility.blueprints");
    assertNotNull(configured, "Set -Dsls.compatibility.blueprints=<directory>");

    Path corpus = Path.of(configured);
    BlueprintRepository repository = new BlueprintRepository(corpus);
    repository.reload();

    Path manifest = corpus.resolve("EXPECTED_BLUEPRINT_IDS.txt");
    assertTrue(Files.isRegularFile(manifest), "Corpus ID manifest is missing");
    Set<String> expectedIds;
    try (var lines = Files.lines(manifest)) {
      expectedIds =
          lines
              .map(String::trim)
              .filter(line -> !line.isEmpty() && !line.startsWith("#"))
              .collect(Collectors.toUnmodifiableSet());
    }
    Set<String> actualIds =
        repository.getAll().stream().map(Blueprint::id).collect(Collectors.toUnmodifiableSet());

    assertEquals(expectedIds, actualIds, "Blueprint corpus IDs differ from its manifest");
    System.out.printf(
        "Loaded %d blueprints across registries %s%n",
        repository.getAll().size(), repository.getTypes());
  }
}
