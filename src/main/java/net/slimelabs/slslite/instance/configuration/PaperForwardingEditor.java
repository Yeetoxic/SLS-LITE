package net.slimelabs.slslite.instance.configuration;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import net.slimelabs.slslite.config.ForwardingConfig;
import net.slimelabs.slslite.config.ForwardingMode;
import net.slimelabs.slslite.instance.InstancePreparationException;
import net.slimelabs.slslite.io.BoundedFileReader;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

public final class PaperForwardingEditor {

  private static final int MAXIMUM_SECRET_LENGTH = 4096;
  private static final int MAXIMUM_SECRET_BYTES = 16 * 1024;

  private PaperForwardingEditor() {}

  public static void apply(Path instanceDirectory, ForwardingConfig config)
      throws InstancePreparationException {
    Path root = instanceDirectory.toAbsolutePath().normalize();
    boolean modern = config.mode() == ForwardingMode.MODERN;
    String secret = modern ? readSecret(config.secretFile()) : "";

    try {
      Path spigotPath = ConfinedConfigFile.resolve(root, "spigot.yml", "Paper config");
      Map<String, Object> spigot = readYaml(spigotPath);
      nestedMap(spigot, "settings", spigotPath).put("bungeecord", false);
      writeYaml(spigotPath, spigot);

      Path paperPath = ConfinedConfigFile.resolve(root, "config/paper-global.yml", "Paper config");
      Map<String, Object> paper = readYaml(paperPath);
      Map<String, Object> proxies = nestedMap(paper, "proxies", paperPath);
      Map<String, Object> velocity = nestedMap(proxies, "velocity", paperPath);
      velocity.put("enabled", modern);
      velocity.put("online-mode", config.onlineMode());
      velocity.put("secret", secret);
      writeYaml(paperPath, paper);
    } catch (IOException exception) {
      throw new InstancePreparationException(
          "Unable to apply Paper forwarding configuration", exception);
    }
  }

  private static String readSecret(Path secretFile) throws InstancePreparationException {
    try {
      if (Files.isSymbolicLink(secretFile)
          || !Files.isRegularFile(secretFile, LinkOption.NOFOLLOW_LINKS)) {
        throw new InstancePreparationException(
            "Velocity forwarding secret file must be a regular non-symbolic file: " + secretFile);
      }
      String secret =
          BoundedFileReader.readStringNoFollow(
                  secretFile, StandardCharsets.UTF_8, MAXIMUM_SECRET_BYTES)
              .trim();
      if (secret.isEmpty()) {
        throw new InstancePreparationException(
            "Velocity forwarding secret file is empty: " + secretFile);
      }
      if (secret.length() > MAXIMUM_SECRET_LENGTH) {
        throw new InstancePreparationException(
            "Velocity forwarding secret exceeds " + MAXIMUM_SECRET_LENGTH + " characters");
      }
      return secret;
    } catch (IOException exception) {
      throw new InstancePreparationException(
          "Unable to read Velocity forwarding secret file: " + secretFile, exception);
    }
  }

  private static Map<String, Object> readYaml(Path path)
      throws IOException, InstancePreparationException {
    if (!ConfinedConfigFile.existsRegular(path, "Paper config")) {
      return new LinkedHashMap<>();
    }
    LoaderOptions options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    options.setCodePointLimit(ConfinedConfigFile.MAX_CONFIG_BYTES);
    options.setMaxAliasesForCollections(50);
    options.setNestingDepthLimit(50);
    Yaml yaml = new Yaml(new SafeConstructor(options));
    try (InputStream input = ConfinedConfigFile.openBounded(path)) {
      Object loaded = yaml.load(input);
      if (loaded == null) {
        return new LinkedHashMap<>();
      }
      if (!(loaded instanceof Map<?, ?> map)) {
        throw new InstancePreparationException("Expected a YAML object in " + path);
      }
      return stringMap(map, path);
    } catch (RuntimeException exception) {
      throw new InstancePreparationException(
          "Invalid YAML in " + path + ": " + exception.getMessage(), exception);
    }
  }

  private static Map<String, Object> nestedMap(Map<String, Object> parent, String key, Path path)
      throws InstancePreparationException {
    Object value = parent.get(key);
    if (value == null) {
      Map<String, Object> created = new LinkedHashMap<>();
      parent.put(key, created);
      return created;
    }
    if (!(value instanceof Map<?, ?> map)) {
      throw new InstancePreparationException("'" + key + "' must be a YAML object in " + path);
    }
    Map<String, Object> converted = stringMap(map, path);
    parent.put(key, converted);
    return converted;
  }

  private static Map<String, Object> stringMap(Map<?, ?> source, Path path)
      throws InstancePreparationException {
    Map<String, Object> result = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : source.entrySet()) {
      if (!(entry.getKey() instanceof String key)) {
        throw new InstancePreparationException("YAML object contains a non-string key in " + path);
      }
      result.put(key, entry.getValue());
    }
    return result;
  }

  private static void writeYaml(Path path, Map<String, Object> values) throws IOException {
    Path temporary = ConfinedConfigFile.createTemporary(path);
    DumperOptions options = new DumperOptions();
    options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
    options.setIndent(2);
    options.setPrettyFlow(true);
    Yaml yaml = new Yaml(options);
    try {
      try (Writer output = ConfinedConfigFile.openTemporaryWriter(temporary)) {
        yaml.dump(values, output);
      }
      ConfinedConfigFile.requireBoundedOutput(temporary, path);
      ConfinedConfigFile.replace(temporary, path);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }
}
