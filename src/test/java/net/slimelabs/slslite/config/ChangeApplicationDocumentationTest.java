package net.slimelabs.slslite.config;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;

class ChangeApplicationDocumentationTest {

  @Test
  void canonicalGuidePinsEveryChangeBoundary() throws Exception {
    String guide = Files.readString(Path.of("DOCS", "Change_Application.md")).replace("\r\n", "\n");

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
            Path.of("DOCS", "Configuration.md"),
            Path.of("DOCS", "Blueprints.md"),
            Path.of("DOCS", "Java_API.md"))) {
      assertTrue(
          Files.readString(page).contains("Change_Application.md"),
          () -> page + " does not link the canonical change-application guide");
    }
  }
}
