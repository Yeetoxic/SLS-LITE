package net.slimelabs.slslite.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

class HistoricalMigrationFixtureTest {

  private static final Path FIXTURES = Path.of("DOCS", "HISTORICAL", "legacy-resources");
  private static final Map<String, String> EXPECTED_ROOTS =
      Map.of(
          "minigames.yml", "minigames",
          "adventureMaps.yml", "AdventureMaps",
          "archive.yml", "archives");

  @Test
  void preservesTheThreeHistoricalRegistryFixturesOutsideRuntimeDefinitions() throws Exception {
    Map<String, String> observed = new LinkedHashMap<>();
    for (Map.Entry<String, String> expected : EXPECTED_ROOTS.entrySet()) {
      Path fixture = FIXTURES.resolve(expected.getKey());
      assertTrue(Files.isRegularFile(fixture), () -> "Missing migration fixture " + fixture);
      LoaderOptions options = new LoaderOptions();
      options.setAllowDuplicateKeys(false);
      Object loaded = new Yaml(new SafeConstructor(options)).load(Files.readString(fixture));
      assertTrue(loaded instanceof Map<?, ?>, () -> "Fixture root must be a map: " + fixture);
      Set<String> roots =
          ((Map<?, ?>) loaded)
              .keySet().stream().map(String::valueOf).collect(java.util.stream.Collectors.toSet());
      assertTrue(roots.contains(expected.getValue()));
      observed.put(expected.getKey(), expected.getValue());
    }

    assertEquals(EXPECTED_ROOTS, observed);
    assertTrue(FIXTURES.normalize().startsWith(Path.of("DOCS")));
  }
}
