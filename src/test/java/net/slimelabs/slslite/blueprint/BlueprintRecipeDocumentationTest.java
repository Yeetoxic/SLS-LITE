package net.slimelabs.slslite.blueprint;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

final class BlueprintRecipeDocumentationTest {

  private static final Path PROJECT = Path.of("").toAbsolutePath().normalize();
  private static final Pattern YAML_BLOCK =
      Pattern.compile("```yaml\\R(.*?)\\R```", Pattern.DOTALL);
  private static final Set<String> EXPECTED_RECIPES =
      Set.of(
          "disposable_world",
          "persistent_smp",
          "one_plugin",
          "plugin_bundle",
          "merged_bundles",
          "whitelisted_smp",
          "configured_game",
          "cow_layers",
          "archive_copy",
          "shared_directory",
          "imported_paper");

  @TempDir Path temporaryDirectory;

  @Test
  void everyPublishedYamlRecipeIsACompleteAcceptedBlueprint() throws Exception {
    String document = Files.readString(PROJECT.resolve("DOCS/Blueprint_Recipes.md"));
    Matcher blocks = YAML_BLOCK.matcher(document);
    Set<String> parsedIds = new LinkedHashSet<>();
    int index = 0;

    while (blocks.find()) {
      Path recipe = temporaryDirectory.resolve("recipe-" + index++ + ".yml");
      Files.writeString(recipe, blocks.group(1) + System.lineSeparator());
      Blueprint blueprint = new BlueprintParser().parse(recipe);
      assertTrue(parsedIds.add(blueprint.id()), () -> "Duplicate recipe ID: " + blueprint.id());
    }

    assertEquals(EXPECTED_RECIPES, parsedIds);
  }

  @Test
  void recipeBookStaysPortableAndExplainsTheCriticalPrecedenceRules() throws Exception {
    String document = Files.readString(PROJECT.resolve("DOCS/Blueprint_Recipes.md"));

    assertFalse(document.contains("/home/container"));
    assertFalse(document.contains("C:\\"));
    assertFalse(document.contains("version: \"26.2\""));
    for (String required :
        Set.of(
            "Same-target COW is first-wins",
            "Ordered `state.copy` is later-wins",
            "Volumes cannot map a file",
            "Reset destructively replaces",
            "Do not map that directory as a volume targeting `/`",
            "The trailing `/` is optional",
            "source` is the supply location below `plugins/sls-lite/`",
            "target` is where that supply appears inside the assembled server")) {
      assertTrue(document.contains(required), () -> "Recipe book is missing: " + required);
    }
  }
}
