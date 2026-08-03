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
import net.slimelabs.slslite.config.ForwardingConfig;
import net.slimelabs.slslite.config.ForwardingMode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

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
    String settings = Files.readString(installation.workingDirectory().resolve("settings.yml"));
    assertTrue(settings.contains("ip: \"127.0.0.1\""));
    assertTrue(settings.contains("port: 25580"));
    assertTrue(settings.contains("protocol: -1"));
    assertTrue(settings.contains("maxPlayers: 250"));
    assertTrue(settings.contains("type: NONE"));
    assertTrue(settings.contains("secret: \"<UNUSED>\""));
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
    String settings = Files.readString(installation.workingDirectory().resolve("settings.yml"));
    assertTrue(settings.contains("type: MODERN"));
    assertTrue(settings.contains("protocol: 770"));
    assertTrue(settings.contains("secret: \"@forwarding.secret\""));
    assertFalse(settings.contains("test-secret"));
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
}
