package net.slimelabs.slslite.instance.configuration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ServerPropertiesEditorTest {

  @TempDir Path temporaryDirectory;

  @Test
  void preservesExistingValuesAndAppliesManagedNetworkSettings() throws Exception {
    Files.writeString(
        temporaryDirectory.resolve("server.properties"),
        "motd=Test Server\nonline-mode=true\n",
        StandardCharsets.UTF_8);

    ServerPropertiesEditor.applyManagedNetworkSettings(
        temporaryDirectory,
        25571,
        12,
        java.util.Map.of(
            "enable-command-block", "true",
            "motd", "Blueprint Server",
            "server-port", "12345",
            "query.port", "{port}",
            "custom-capacity", "{max_players}"));

    Properties properties = new Properties();
    try (Reader input =
        Files.newBufferedReader(
            temporaryDirectory.resolve("server.properties"), StandardCharsets.UTF_8)) {
      properties.load(input);
    }
    assertEquals("Blueprint Server", properties.getProperty("motd"));
    assertEquals("true", properties.getProperty("enable-command-block"));
    assertEquals("false", properties.getProperty("online-mode"));
    assertEquals("12", properties.getProperty("max-players"));
    assertEquals("127.0.0.1", properties.getProperty("server-ip"));
    assertEquals("25571", properties.getProperty("server-port"));
    assertEquals("25571", properties.getProperty("query.port"));
    assertEquals("12", properties.getProperty("custom-capacity"));
    assertFalse(Files.exists(temporaryDirectory.resolve("server.properties.tmp")));
  }

  @Test
  void managedNetworkSettingsOverrideEarlierGenericTextPatch() throws Exception {
    Files.writeString(
        temporaryDirectory.resolve("server.properties"),
        "server-port=25565\nonline-mode=true\nmotd=Original\n");
    TextFileConfigEditor.apply(
        temporaryDirectory,
        Map.of(
            "server.properties",
            Map.of(
                "server-port=", "server-port=12345",
                "online-mode=", "online-mode=true")));

    ServerPropertiesEditor.applyManagedNetworkSettings(temporaryDirectory, 25571, 12, Map.of());

    Properties properties = new Properties();
    try (Reader input =
        Files.newBufferedReader(
            temporaryDirectory.resolve("server.properties"), StandardCharsets.UTF_8)) {
      properties.load(input);
    }
    assertEquals("25571", properties.getProperty("server-port"));
    assertEquals("false", properties.getProperty("online-mode"));
    assertEquals("127.0.0.1", properties.getProperty("server-ip"));
    assertEquals("Original", properties.getProperty("motd"));
  }
}
