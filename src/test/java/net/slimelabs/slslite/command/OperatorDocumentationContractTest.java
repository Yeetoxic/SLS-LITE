package net.slimelabs.slslite.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

final class OperatorDocumentationContractTest {

  private static final Path PROJECT = Path.of("").toAbsolutePath().normalize();
  private static final Pattern CONFIGURATION_ROW =
      Pattern.compile("^\\| `([^`]+)` \\| ([^|]+) \\|", Pattern.MULTILINE);

  @Test
  void commandAndPermissionDocumentationCoversTheVersionedRuntimeContract() throws IOException {
    String commands = read("DOCS/Commands.md");
    String compatibility = read("DOCS/SLS_Command_Compatibility.md");
    String combined = commands + System.lineSeparator() + compatibility;

    for (VSLSCommandContract.Branch branch : VSLSCommandContract.BRANCHES) {
      assertTrue(
          combined.contains("/sls " + branch.root())
              || compatibility.contains("| `" + branch.root()),
          () -> "Command documentation is missing root: " + branch.root());
      for (String permission : branch.permissionNodes()) {
        assertTrue(
            commands.contains("`" + permission + "`"),
            () -> "Permission reference is missing: " + permission);
      }
      for (String modifier : branch.modifiers()) {
        assertTrue(
            combined.contains(modifier),
            () -> "Command documentation is missing modifier: " + modifier);
      }
    }

    assertTrue(commands.contains("`" + CommandPermissions.ADMIN + "`"));
  }

  @Test
  void compatibilityDocumentationRemainsPinnedToTheCommandContract() throws IOException {
    for (String document : Set.of("DOCS/Compatibility.md", "DOCS/SLS_Command_Compatibility.md")) {
      String content = read(document);
      assertTrue(
          content.contains(VSLSCommandContract.RELEASE),
          () -> document + " is missing pinned release " + VSLSCommandContract.RELEASE);
      assertTrue(
          content.contains(VSLSCommandContract.COMMIT),
          () -> document + " is missing pinned commit " + VSLSCommandContract.COMMIT);
    }

    String compatibility = read("DOCS/SLS_Command_Compatibility.md");
    for (VSLSCommandContract.Branch branch : VSLSCommandContract.BRANCHES) {
      if (branch.availability() == VSLSCommandContract.Availability.LOCAL_MODE_RESPONSE
          || branch.availability() == VSLSCommandContract.Availability.BUILD_RESPONSE) {
        assertTrue(
            compatibility.contains("| `" + branch.root()),
            () -> "Compatibility matrix is missing unavailable root: " + branch.root());
      }
    }
  }

  @Test
  void configurationReferenceMatchesEveryCanonicalDefaultLeaf() throws IOException {
    Map<String, String> defaults = flattenBundledDefaults();
    Map<String, String> documented = configurationRows(read("DOCS/Configuration.md"));

    Set<String> expectedKeys = new LinkedHashSet<>(defaults.keySet());
    expectedKeys.add("storage.snapshot_hook.executable");
    assertEquals(
        expectedKeys,
        documented.keySet(),
        "Configuration reference keys must match the canonical bundled default");

    defaults.forEach(
        (key, value) ->
            assertTrue(
                documented.get(key).contains("`" + value + "`"),
                () ->
                    "Configuration reference default for "
                        + key
                        + " must contain canonical value "
                        + value));
    assertTrue(documented.get("storage.snapshot_hook.executable").contains("unset"));
  }

  private static Map<String, String> flattenBundledDefaults() throws IOException {
    LoaderOptions options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    Yaml yaml = new Yaml(new SafeConstructor(options));
    Object loaded;
    try (var input =
        Files.newInputStream(PROJECT.resolve("src/main/resources/defaults/host/config.yml"))) {
      loaded = yaml.load(input);
    }

    Map<String, String> flattened = new LinkedHashMap<>();
    flatten("", castMap(loaded), flattened);
    return flattened;
  }

  private static void flatten(
      String prefix, Map<String, Object> values, Map<String, String> flattened) {
    values.forEach(
        (key, value) -> {
          String path = prefix.isEmpty() ? key : prefix + "." + key;
          if (value instanceof Map<?, ?> nested) {
            flatten(path, castMap(nested), flattened);
          } else {
            flattened.put(path, String.valueOf(value));
          }
        });
  }

  private static Map<String, String> configurationRows(String document) {
    Map<String, String> rows = new LinkedHashMap<>();
    Matcher matcher = CONFIGURATION_ROW.matcher(document);
    while (matcher.find()) {
      rows.put(matcher.group(1), matcher.group(2).trim());
    }
    return rows;
  }

  private static Map<String, Object> castMap(Object value) {
    if (!(value instanceof Map<?, ?> map)) {
      throw new IllegalArgumentException("Expected a YAML mapping");
    }
    Map<String, Object> converted = new LinkedHashMap<>();
    map.forEach((key, nested) -> converted.put(String.valueOf(key), nested));
    return converted;
  }

  private static String read(String relativePath) throws IOException {
    Path path = PROJECT.resolve(relativePath);
    assertTrue(Files.isRegularFile(path), () -> "Missing project document: " + path);
    return Files.readString(path);
  }
}
