package net.slimelabs.slslite.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
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
  private static final Map<String, String> EXPECTED_SHA256 =
      Map.of(
          "minigames.yml", "7b6e7cfad0df0ee1fc43b4cddc1dbf1d384bac31011e39d521203a28c56840fb",
          "adventureMaps.yml", "9bd9991aa6b7d53212e528611d28b79be9a58ddcce28bc70db0f91cad062a832",
          "archive.yml", "c092e396d757b021b13b4ccede52692974f1b6950972dc6881529e17898d6603");

  @Test
  void preservesTheThreeHistoricalRegistryFixturesOutsideRuntimeDefinitions() throws Exception {
    Map<String, String> observed = new LinkedHashMap<>();
    for (Map.Entry<String, String> expected : EXPECTED_ROOTS.entrySet()) {
      Path fixture = FIXTURES.resolve(expected.getKey());
      assertTrue(Files.isRegularFile(fixture), () -> "Missing migration fixture " + fixture);
      assertEquals(
          EXPECTED_SHA256.get(expected.getKey()),
          HexFormat.of()
              .formatHex(MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(fixture))));
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
