package net.slimelabs.slslite.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
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
          "minigames.yml", "c67c32a6ed9623e673d67c3f160b3db22462ea4b1b07ab5d70a295cf81d75839",
          "adventureMaps.yml", "9bd6f7e8fe6b3858f7c81ea956ace463cce8f2611dce6b57b7f40e4134c925b4",
          "archive.yml", "62d61dd8cafee25c5274a756963efb46318e9e2ae17156684351fbfc2816840f");

  @Test
  void preservesTheThreeHistoricalRegistryFixturesOutsideRuntimeDefinitions() throws Exception {
    Map<String, String> observed = new LinkedHashMap<>();
    for (Map.Entry<String, String> expected : EXPECTED_ROOTS.entrySet()) {
      Path fixture = FIXTURES.resolve(expected.getKey());
      assertTrue(Files.isRegularFile(fixture), () -> "Missing migration fixture " + fixture);
      assertEquals(
          EXPECTED_SHA256.get(expected.getKey()),
          HexFormat.of()
              .formatHex(
                  MessageDigest.getInstance("SHA-256")
                      .digest(canonicalFixtureBytes(Files.readString(fixture)))));
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

  private static byte[] canonicalFixtureBytes(String contents) {
    return contents.replace("\r\n", "\n").replace('\r', '\n').getBytes(StandardCharsets.UTF_8);
  }
}
