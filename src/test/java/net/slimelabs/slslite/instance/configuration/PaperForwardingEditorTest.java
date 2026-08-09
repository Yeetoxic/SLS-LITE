package net.slimelabs.slslite.instance.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import net.slimelabs.slslite.config.ForwardingConfig;
import net.slimelabs.slslite.config.ForwardingMode;
import net.slimelabs.slslite.instance.InstancePreparationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

class PaperForwardingEditorTest {

  @TempDir Path temporaryDirectory;

  @Test
  void configuresModernForwardingAndPreservesExistingValues() throws Exception {
    Path secret = temporaryDirectory.resolve("forwarding.secret");
    Files.writeString(secret, "test-secret\n");
    Files.writeString(
        temporaryDirectory.resolve("spigot.yml"),
        """
                settings:
                  restart-on-crash: true
                  bungeecord: true
                """);
    Path paper = temporaryDirectory.resolve("config/paper-global.yml");
    Files.createDirectories(paper.getParent());
    Files.writeString(
        paper,
        """
                proxies:
                  proxy-protocol: false
                """);

    PaperForwardingEditor.apply(
        temporaryDirectory, new ForwardingConfig(ForwardingMode.MODERN, true, secret), "1.21.11");

    Map<String, Object> spigot = yaml(temporaryDirectory.resolve("spigot.yml"));
    Map<String, Object> settings = map(spigot.get("settings"));
    assertEquals(false, settings.get("bungeecord"));
    assertEquals(true, settings.get("restart-on-crash"));

    Map<String, Object> paperConfig = yaml(paper);
    Map<String, Object> proxies = map(paperConfig.get("proxies"));
    Map<String, Object> velocity = map(proxies.get("velocity"));
    assertEquals(false, proxies.get("proxy-protocol"));
    assertEquals(true, velocity.get("enabled"));
    assertEquals(true, velocity.get("online-mode"));
    assertEquals("test-secret", velocity.get("secret"));
  }

  @Test
  void configuresLegacyPaperThroughOnePointEighteenTwo() throws Exception {
    Path secret = temporaryDirectory.resolve("forwarding.secret");
    Files.writeString(secret, "legacy-test-secret\n");
    Path paper = temporaryDirectory.resolve("paper.yml");
    Files.writeString(
        paper,
        """
                settings:
                  unsupported-settings:
                    allow-headless-pistons: true
                """);

    PaperForwardingEditor.apply(
        temporaryDirectory, new ForwardingConfig(ForwardingMode.MODERN, true, secret), "1.18.2");

    Map<String, Object> settings = map(yaml(paper).get("settings"));
    Map<String, Object> velocity = map(settings.get("velocity-support"));
    assertEquals(true, velocity.get("enabled"));
    assertEquals(true, velocity.get("online-mode"));
    assertEquals("legacy-test-secret", velocity.get("secret"));
    assertTrue(settings.containsKey("unsupported-settings"));
    assertFalse(Files.exists(temporaryDirectory.resolve("config/paper-global.yml")));
  }

  @Test
  void selectsThePaperConfigurationBoundaryByMinecraftVersion() {
    assertTrue(PaperForwardingEditor.usesLegacyPaperConfig("1.8.8"));
    assertTrue(PaperForwardingEditor.usesLegacyPaperConfig("1.18"));
    assertTrue(PaperForwardingEditor.usesLegacyPaperConfig("1.18.2"));
    assertFalse(PaperForwardingEditor.usesLegacyPaperConfig("1.19"));
    assertFalse(PaperForwardingEditor.usesLegacyPaperConfig("1.19.2"));
    assertFalse(PaperForwardingEditor.usesLegacyPaperConfig("1.21.11"));
    assertFalse(PaperForwardingEditor.usesLegacyPaperConfig("26.2"));
  }

  @Test
  void disablesForwardingWithoutReadingASecret() throws Exception {
    Path missingSecret = temporaryDirectory.resolve("missing.secret");

    PaperForwardingEditor.apply(
        temporaryDirectory,
        new ForwardingConfig(ForwardingMode.NONE, false, missingSecret),
        "1.21.11");

    Map<String, Object> paper = yaml(temporaryDirectory.resolve("config/paper-global.yml"));
    Map<String, Object> velocity = map(map(paper.get("proxies")).get("velocity"));
    assertEquals(false, velocity.get("enabled"));
    assertEquals("", velocity.get("secret"));
    assertFalse(Files.exists(missingSecret));
  }

  @Test
  void rejectsModernForwardingWhenSecretIsMissing() {
    InstancePreparationException exception =
        assertThrows(
            InstancePreparationException.class,
            () ->
                PaperForwardingEditor.apply(
                    temporaryDirectory,
                    new ForwardingConfig(
                        ForwardingMode.MODERN, true, temporaryDirectory.resolve("missing.secret")),
                    "1.21.11"));

    assertTrue(exception.getMessage().contains("regular non-symbolic file"));
    assertFalse(Files.exists(temporaryDirectory.resolve("spigot.yml")));
  }

  @Test
  void refusesSymbolicPaperConfigDirectory() throws Exception {
    Path outside = Files.createDirectories(temporaryDirectory.resolveSibling("outside-config"));
    try {
      Files.createSymbolicLink(temporaryDirectory.resolve("config"), outside);
    } catch (UnsupportedOperationException | java.io.IOException exception) {
      org.junit.jupiter.api.Assumptions.abort("Symbolic links unavailable: " + exception);
    }

    assertThrows(
        InstancePreparationException.class,
        () ->
            PaperForwardingEditor.apply(
                temporaryDirectory,
                new ForwardingConfig(
                    ForwardingMode.NONE, false, temporaryDirectory.resolve("unused.secret")),
                "1.21.11"));
    assertFalse(Files.exists(outside.resolve("paper-global.yml")));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> yaml(Path path) throws Exception {
    LoaderOptions options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    try (InputStream input = Files.newInputStream(path)) {
      return (Map<String, Object>) new Yaml(new SafeConstructor(options)).load(input);
    }
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }
}
