package net.slimelabs.slslite.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ChangeApplicationDocumentationTest {

  @Test
  void canonicalGuidePinsEveryChangeBoundary() throws Exception {
    String guide =
        Files.readString(Path.of("DOCS", "operations", "Applying_Changes.md"))
            .replace("\r\n", "\n");

    assertTrue(guide.contains("`config.yml` | Restart Velocity"));
    assertTrue(guide.contains("SLS-LITE or extension plugin JAR | Restart Velocity"));
    assertTrue(guide.contains("never\nrewrites, restarts, or disconnects"));
    assertTrue(guide.contains("Restart Versus Reset"));
    assertTrue(guide.contains("`state.copy` mapping or source file"));
    assertTrue(guide.contains("valid siblings remain available"));
  }

  @Test
  void primaryOperatorPagesLinkTheCanonicalGuide() throws Exception {
    for (Path page :
        java.util.List.of(
            Path.of("README.md"),
            Path.of("DOCS", "README.md"),
            Path.of("DOCS", "setup", "Configuration.md"),
            Path.of("DOCS", "blueprints", "Schema.md"),
            Path.of("DOCS", "extensions", "README.md"))) {
      assertTrue(
          Files.readString(page).contains("Applying_Changes.md"),
          () -> page + " does not link the canonical change-application guide");
    }
  }
}
