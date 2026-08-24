package net.slimelabs.slslite.docs;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Stream;
import org.junit.jupiter.api.Test;

final class DocumentationNavigationTest {

  private static final Path PROJECT = Path.of("").toAbsolutePath().normalize();
  private static final Pattern MARKDOWN_LINK = Pattern.compile("\\[[^]]+]\\(([^)]+)\\)");
  private static final Pattern CANONICAL_REPOSITORY_LINK =
      Pattern.compile("https://github\\.com/Yeetoxic/SLS-LITE/(?:blob|tree)/main/([^#)]+)");
  private static final Set<String> WIKI_PAGES =
      Set.of(
          "Home.md",
          "Release-Notes.md",
          "_Sidebar.md",
          "_Footer.md",
          "Installation-and-First-Run.md",
          "Configuration.md",
          "Commands-and-Permissions.md",
          "Storage-and-COW.md",
          "Lobby-and-Matchmaking.md",
          "Operations.md",
          "Troubleshooting.md",
          "Security-and-Privacy.md",
          "Compatibility.md",
          "Java-Extension-Development.md",
          "Backend-Integrations.md",
          "Contributing.md");

  @Test
  void localMarkdownLinksResolve() throws IOException {
    for (Path document : projectDocuments()) {
      Matcher links = MARKDOWN_LINK.matcher(Files.readString(document));
      while (links.find()) {
        String target = links.group(1).split("#", 2)[0].replace("<", "").replace(">", "");
        if (target.isBlank()
            || target.startsWith("http://")
            || target.startsWith("https://")
            || target.startsWith("mailto:")) {
          continue;
        }
        Path resolved = document.getParent().resolve(target).normalize();
        Path wikiPage = resolved.resolveSibling(resolved.getFileName() + ".md");
        assertTrue(
            Files.exists(resolved) || Files.isRegularFile(wikiPage),
            () -> relative(document) + " has a missing link target: " + links.group(1));
      }
    }
  }

  @Test
  void everyCurrentGuideReturnsToTheCanonicalIndex() throws IOException {
    Path docs = PROJECT.resolve("DOCS");
    Path historical = docs.resolve("HISTORICAL");
    try (Stream<Path> documents = Files.walk(docs)) {
      for (Path document :
          documents
              .filter(Files::isRegularFile)
              .filter(path -> path.toString().endsWith(".md"))
              .filter(path -> !path.startsWith(historical))
              .toList()) {
        if (document.equals(docs.resolve("README.md"))) {
          continue;
        }
        assertTrue(
            linksTo(Files.readString(document), document.getParent(), docs.resolve("README.md")),
            () -> relative(document) + " does not link to DOCS/README.md");
      }
    }
  }

  @Test
  void wikiSourceIsCompleteAndPointsToCanonicalRepositoryContent() throws IOException {
    Path wiki = PROJECT.resolve("WIKI");
    String publishing = Files.readString(wiki.resolve("README.md"));
    String sidebar = Files.readString(wiki.resolve("_Sidebar.md"));

    for (String page : WIKI_PAGES) {
      assertTrue(Files.isRegularFile(wiki.resolve(page)), () -> "Missing wiki page: " + page);
      assertTrue(publishing.contains("`" + page + "`"), () -> "Publishing list omits: " + page);
      if (!page.startsWith("_") && !page.equals("Home.md")) {
        assertTrue(
            sidebar.contains("(" + page.substring(0, page.length() - 3) + ")"),
            () -> "Wiki sidebar omits: " + page);
      }
    }

    for (Path page : WIKI_PAGES.stream().map(wiki::resolve).toList()) {
      Matcher links = CANONICAL_REPOSITORY_LINK.matcher(Files.readString(page));
      while (links.find()) {
        assertTrue(
            Files.exists(PROJECT.resolve(links.group(1))),
            () -> relative(page) + " points to missing canonical content: " + links.group());
      }
    }
  }

  @Test
  void developmentHandoffLanguageDoesNotReturnToPublicGuides() throws IOException {
    String publicGuides = publicGuideText();
    for (String staleClaim :
        List.of(
            "Stage 3.10 must pass",
            "remaining Java API release gate",
            "Stage 3.4 is moving",
            "Native copy-on-write optimizations may be added later",
            "**Development status:**",
            "pre-release implementation",
            "The project is currently a snapshot",
            "not yet a production release",
            "start-on-proxy-start` is roadmap work",
            "Java_API_Roadmap.md")) {
      assertFalse(
          publicGuides.contains(staleClaim),
          () -> "Public documentation contains stale status text: " + staleClaim);
    }
  }

  @Test
  void publicGuidesDescribeCurrentBehaviorInsteadOfRoadmapWork() throws IOException {
    String publicGuides = publicGuideText();
    assertFalse(
        Pattern.compile("\\bdeferred\\b", Pattern.CASE_INSENSITIVE).matcher(publicGuides).find(),
        "Public documentation uses roadmap classification; proposed work belongs in todo.md");
  }

  @Test
  void candidateVersionAndReleaseWorkflowStayConsistent() throws IOException {
    Matcher versionMatcher =
        Pattern.compile(
                "<artifactId>sls-lite</artifactId>\\s*<version>([^<]+)</version>",
                Pattern.MULTILINE)
            .matcher(Files.readString(PROJECT.resolve("pom.xml")));
    assertTrue(versionMatcher.find(), "Project version is missing from pom.xml");
    String version = versionMatcher.group(1);
    assertEquals("0.1.0-rc.2.3", version, "Unexpected release-candidate version");

    assertContains(
        "src/main/java/net/slimelabs/slslite/BuildInfo.java",
        "public static final String VERSION = \"" + version + "\";");
    assertContains("src/main/resources/velocity-plugin.json", "\"version\": \"" + version + "\"");
    assertContains("DOCS/networking/Protocol_Compatibility.md", "| SLS-LITE | `" + version + "` |");
    assertContains("RELEASE_NOTES.md", "# SLS-LITE " + version);
    assertContains("WIKI/Home.md", "SLS-LITE " + version);

    Path workflow = PROJECT.resolve(".github/workflows/build-release.yml");
    assertTrue(Files.isRegularFile(workflow), "Build Release workflow is missing");
    String releaseWorkflow = Files.readString(workflow);
    for (String mode : List.of("distribution-smoke", "release-candidate", "release")) {
      assertTrue(releaseWorkflow.contains("- " + mode), () -> "Missing release mode: " + mode);
    }
    assertTrue(releaseWorkflow.contains("environment: ${{ inputs.mode }}"));
    assertTrue(releaseWorkflow.contains("Refusing to replace or promote existing release"));
    assertFalse(
        releaseWorkflow.contains("SLS-LITE candidate:"),
        "Release validation must not restore a version pin on the branch-based compatibility page");
    assertFalse(
        Files.exists(PROJECT.resolve(".github/workflows/api-distribution-smoke.yml")),
        "Obsolete standalone distribution workflow still exists");
  }

  private static String publicGuideText() throws IOException {
    StringBuilder publicGuides = new StringBuilder(Files.readString(PROJECT.resolve("README.md")));
    for (String root : List.of("DOCS", "WIKI")) {
      Path directory = PROJECT.resolve(root);
      try (Stream<Path> documents = Files.walk(directory)) {
        for (Path document :
            documents
                .filter(Files::isRegularFile)
                .filter(path -> path.toString().endsWith(".md"))
                .filter(path -> !path.startsWith(PROJECT.resolve("DOCS/HISTORICAL")))
                .toList()) {
          publicGuides.append('\n').append(Files.readString(document));
        }
      }
    }
    return publicGuides.toString();
  }

  private static List<Path> projectDocuments() throws IOException {
    List<Path> documents = new ArrayList<>();
    documents.add(PROJECT.resolve("README.md"));
    documents.add(PROJECT.resolve("RELEASE_NOTES.md"));
    documents.add(PROJECT.resolve("examples/velocity-extension/README.md"));
    documents.add(PROJECT.resolve("examples/paper-backend-sender/README.md"));
    documents.add(PROJECT.resolve("infra/pterodactyl/README.md"));
    for (String root : List.of("DOCS", "WIKI", "RELEASE_EVIDENCE", "THIRD_PARTY")) {
      Path directory = PROJECT.resolve(root);
      if (!Files.isDirectory(directory)) {
        continue;
      }
      try (Stream<Path> paths = Files.walk(directory)) {
        paths
            .filter(Files::isRegularFile)
            .filter(path -> path.toString().endsWith(".md"))
            .forEach(documents::add);
      }
    }
    return documents;
  }

  private static String relative(Path path) {
    return PROJECT.relativize(path).toString().replace('\\', '/');
  }

  private static boolean linksTo(String content, Path parent, Path expected) {
    Matcher links = MARKDOWN_LINK.matcher(content);
    while (links.find()) {
      String target = links.group(1).split("#", 2)[0].replace("<", "").replace(">", "");
      if (!target.isBlank()
          && !target.startsWith("http://")
          && !target.startsWith("https://")
          && parent.resolve(target).normalize().equals(expected)) {
        return true;
      }
    }
    return false;
  }

  private static void assertContains(String relativePath, String expected) throws IOException {
    assertTrue(
        Files.readString(PROJECT.resolve(relativePath)).contains(expected),
        () -> relativePath + " does not contain: " + expected);
  }
}
