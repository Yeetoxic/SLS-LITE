package net.slimelabs.slslite.instance.configuration;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.file.Path;
import java.util.Map;
import java.util.Properties;

public final class ServerPropertiesEditor {

  private ServerPropertiesEditor() {}

  public static void applyManagedNetworkSettings(
      Path instanceDirectory, int port, int maxPlayers, Map<String, String> configuredProperties)
      throws IOException {
    applyManagedNetworkSettings(
        instanceDirectory, port, maxPlayers, maxPlayers, configuredProperties);
  }

  public static void applyManagedNetworkSettings(
      Path instanceDirectory,
      int port,
      int publicMaxPlayers,
      int backendMaxPlayers,
      Map<String, String> configuredProperties)
      throws IOException {
    if (port < 1024 || port > 65535) {
      throw new IllegalArgumentException("port must be between 1024 and 65535");
    }
    if (publicMaxPlayers <= 0 || backendMaxPlayers < publicMaxPlayers) {
      throw new IllegalArgumentException(
          "backendMaxPlayers must be at least the positive publicMaxPlayers limit");
    }

    Path root = instanceDirectory.toAbsolutePath().normalize();
    Path propertiesPath =
        ConfinedConfigFile.resolve(root, "server.properties", "Properties config");
    Properties properties = new Properties();
    if (ConfinedConfigFile.existsRegular(propertiesPath, "Properties config")) {
      try (Reader input =
          new java.io.InputStreamReader(
              ConfinedConfigFile.openBounded(propertiesPath),
              java.nio.charset.StandardCharsets.UTF_8)) {
        properties.load(input);
      }
    }

    configuredProperties.forEach(
        (key, value) ->
            properties.setProperty(
                key,
                value
                    .replace("{port}", Integer.toString(port))
                    .replace("{max_players}", Integer.toString(publicMaxPlayers))));

    // Proxy-owned values must win over blueprint configuration.
    properties.setProperty("server-ip", "127.0.0.1");
    properties.setProperty("server-port", Integer.toString(port));
    properties.setProperty("online-mode", "false");
    properties.setProperty("max-players", Integer.toString(backendMaxPlayers));

    Path temporaryPath = ConfinedConfigFile.createTemporary(propertiesPath);
    try {
      try (Writer output = ConfinedConfigFile.openTemporaryWriter(temporaryPath)) {
        properties.store(output, "Managed by SLS-LITE");
      }
      ConfinedConfigFile.requireBoundedOutput(temporaryPath, propertiesPath);
      ConfinedConfigFile.replace(temporaryPath, propertiesPath);
    } finally {
      java.nio.file.Files.deleteIfExists(temporaryPath);
    }
  }
}
