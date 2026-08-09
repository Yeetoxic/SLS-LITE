package net.slimelabs.slslite.instance.configuration;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import net.slimelabs.slslite.config.ForwardingConfig;
import net.slimelabs.slslite.config.ForwardingMode;
import net.slimelabs.slslite.config.ForwardingSecretFile;
import net.slimelabs.slslite.instance.InstancePreparationException;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

public final class PaperForwardingEditor {

  private PaperForwardingEditor() {}

  public static void apply(Path instanceDirectory, ForwardingConfig config, String minecraftVersion)
      throws InstancePreparationException {
    Path root = instanceDirectory.toAbsolutePath().normalize();
    boolean modern = config.mode() == ForwardingMode.MODERN;
    String secret = modern ? readSecret(config.secretFile()) : "";

    try {
      Path spigotPath = ConfinedConfigFile.resolve(root, "spigot.yml", "Paper config");
      Map<String, Object> spigot = readYaml(spigotPath);
      nestedMap(spigot, "settings", spigotPath).put("bungeecord", false);
      writeYaml(spigotPath, spigot);

      boolean legacy = usesLegacyPaperConfig(minecraftVersion);
      Path paperPath =
          ConfinedConfigFile.resolve(
              root, legacy ? "paper.yml" : "config/paper-global.yml", "Paper config");
      Map<String, Object> paper = readYaml(paperPath);
      Map<String, Object> velocity;
      if (legacy) {
        Map<String, Object> settings = nestedMap(paper, "settings", paperPath);
        velocity = nestedMap(settings, "velocity-support", paperPath);
      } else {
        Map<String, Object> proxies = nestedMap(paper, "proxies", paperPath);
        velocity = nestedMap(proxies, "velocity", paperPath);
      }
      velocity.put("enabled", modern);
      velocity.put("online-mode", config.onlineMode());
      velocity.put("secret", secret);
      writeYaml(paperPath, paper);
    } catch (IOException exception) {
      throw new InstancePreparationException(
          "Unable to apply Paper forwarding configuration", exception);
    }
  }

  static boolean usesLegacyPaperConfig(String minecraftVersion) {
    if (minecraftVersion == null) {
      return false;
    }
    String[] parts = minecraftVersion.strip().split("\\.", 4);
    if (parts.length < 2) {
      return false;
    }
    try {
      int major = Integer.parseInt(parts[0]);
      int minor = Integer.parseInt(parts[1]);
      int patch = parts.length >= 3 ? numericPrefix(parts[2]) : 0;
      return major == 1 && (minor < 18 || (minor == 18 && patch <= 2));
    } catch (NumberFormatException ignored) {
      return false;
    }
  }

  private static int numericPrefix(String value) {
    int end = 0;
    while (end < value.length() && Character.isDigit(value.charAt(end))) {
      end++;
    }
    if (end == 0) {
      throw new NumberFormatException("missing numeric patch");
    }
    return Integer.parseInt(value.substring(0, end));
  }

  private static String readSecret(Path secretFile) throws InstancePreparationException {
    try {
      return ForwardingSecretFile.read(secretFile);
    } catch (IOException exception) {
      throw new InstancePreparationException(
          "Velocity forwarding secret file " + exception.getMessage() + ": " + secretFile,
          exception);
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
