package net.slimelabs.slslite.lobby;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.Map;
import net.slimelabs.slslite.config.ForwardingConfig;
import net.slimelabs.slslite.config.ForwardingMode;
import net.slimelabs.slslite.config.SLSLimboPresentationConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

class SLSLimboInstallerTest {

  @TempDir Path temporaryDirectory;

  @Test
  void extractsPinnedRuntimeAndWritesLoopbackConfiguration() throws Exception {
    SLSLimboInstaller installer = new SLSLimboInstaller(temporaryDirectory);

    SLSLimboInstaller.SLSLimboInstallation installation =
        installer.install(
            25580,
            new ForwardingConfig(
                ForwardingMode.NONE, true, temporaryDirectory.resolve("unused.secret")),
            -1,
            250);

    assertTrue(Files.isRegularFile(installation.runtimeJar()));
    assertEquals(SLSLimboInstaller.RUNTIME_SHA256, sha256(installation.runtimeJar()));
    Map<String, Object> settings = settings(installation.workingDirectory());
    assertEquals("127.0.0.1", map(settings.get("bind")).get("ip"));
    assertEquals(25580, map(settings.get("bind")).get("port"));
    assertEquals(-1, map(settings.get("ping")).get("protocol"));
    assertEquals(250, settings.get("maxPlayers"));
    assertEquals("NONE", map(settings.get("infoForwarding")).get("type"));
    assertEquals("<UNUSED>", map(settings.get("infoForwarding")).get("secret"));
    assertFalse(Files.exists(installation.workingDirectory().resolve("forwarding.secret")));
  }

  @Test
  void copiesModernForwardingSecretWithoutEmbeddingItInSettings() throws Exception {
    Path secret = temporaryDirectory.resolve("velocity.secret");
    Files.writeString(secret, "test-secret");
    SLSLimboInstaller installer = new SLSLimboInstaller(temporaryDirectory);

    SLSLimboInstaller.SLSLimboInstallation installation =
        installer.install(
            25581, new ForwardingConfig(ForwardingMode.MODERN, true, secret), 770, 500);

    Path copiedSecret = installation.workingDirectory().resolve("forwarding.secret");
    assertEquals("test-secret", Files.readString(copiedSecret));
    Map<String, Object> settings = settings(installation.workingDirectory());
    assertEquals("MODERN", map(settings.get("infoForwarding")).get("type"));
    assertEquals(770, map(settings.get("ping")).get("protocol"));
    assertEquals("@forwarding.secret", map(settings.get("infoForwarding")).get("secret"));
    assertFalse(
        Files.readString(installation.workingDirectory().resolve("settings.yml"))
            .contains("test-secret"));
  }

  @Test
  void safelySerializesCustomizedPresentation() throws Exception {
    SLSLimboPresentationConfig defaults = SLSLimboPresentationConfig.defaults();
    SLSLimboPresentationConfig presentation =
        new SLSLimboPresentationConfig(
            SLSLimboPresentationConfig.Dimension.OVERWORLD,
            new SLSLimboPresentationConfig.Ping(false, "quoted: \"value\"\nnext", "<aqua>Custom"),
            new SLSLimboPresentationConfig.PlayerList(true, "Holder"),
            new SLSLimboPresentationConfig.TextElement(false, "brand: hidden"),
            new SLSLimboPresentationConfig.TextElement(true, "line one\nline two: yes"),
            new SLSLimboPresentationConfig.BossBar(
                false, defaults.bossBar().text(), 0.5, "BLUE", "NOTCHED_10"),
            new SLSLimboPresentationConfig.Title(
                true, "<gold>Title: safe", "subtitle\nnext", 0, 40, 5),
            new SLSLimboPresentationConfig.HeaderFooter(true, "header: value", "footer\nvalue"));
    SLSLimboInstaller installer = new SLSLimboInstaller(temporaryDirectory);

    SLSLimboInstaller.SLSLimboInstallation installation =
        installer.install(
            25586,
            new ForwardingConfig(
                ForwardingMode.NONE, true, temporaryDirectory.resolve("unused.secret")),
            -1,
            100,
            presentation);

    Map<String, Object> settings = settings(installation.workingDirectory());
    assertEquals("OVERWORLD", settings.get("dimension"));
    assertEquals("", map(settings.get("ping")).get("description"));
    assertEquals("line one\nline two: yes", map(settings.get("joinMessage")).get("text"));
    assertEquals("header: value", map(settings.get("headerAndFooter")).get("header"));
    assertEquals(false, map(settings.get("brandName")).get("enable"));
  }

  @Test
  void rejectsFixedProtocolMissingFromPinnedRuntime() {
    SLSLimboInstaller installer = new SLSLimboInstaller(temporaryDirectory);

    IOException failure =
        assertThrows(
            IOException.class,
            () ->
                installer.install(
                    25583,
                    new ForwardingConfig(
                        ForwardingMode.NONE, true, temporaryDirectory.resolve("unused.secret")),
                    Integer.MAX_VALUE,
                    100));

    assertTrue(failure.getMessage().contains("is not supported"));
  }

  @Test
  void rejectsFixedProtocolWithUnsafeHeightmapTranslation() {
    SLSLimboInstaller installer = new SLSLimboInstaller(temporaryDirectory);

    IOException failure =
        assertThrows(
            IOException.class,
            () ->
                installer.install(
                    25584,
                    new ForwardingConfig(
                        ForwardingMode.NONE, true, temporaryDirectory.resolve("unused.secret")),
                    769,
                    100));

    assertTrue(failure.getMessage().contains("heightmaps"));
    assertTrue(failure.getMessage().contains("1.21.5"));
  }

  @Test
  void rejectsNonPositiveProxyCapacity() {
    SLSLimboInstaller installer = new SLSLimboInstaller(temporaryDirectory);

    assertThrows(
        IllegalArgumentException.class,
        () ->
            installer.install(
                25585,
                new ForwardingConfig(
                    ForwardingMode.NONE, true, temporaryDirectory.resolve("unused.secret")),
                -1,
                0));
  }

  private static String sha256(Path path) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    digest.update(Files.readAllBytes(path));
    return HexFormat.of().formatHex(digest.digest());
  }

  private static Map<String, Object> settings(Path directory) throws Exception {
    return map(new Yaml().load(Files.readString(directory.resolve("settings.yml"))));
  }

  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }
}
