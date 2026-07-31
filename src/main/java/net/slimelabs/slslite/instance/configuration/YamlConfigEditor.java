package net.slimelabs.slslite.instance.configuration;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

public final class YamlConfigEditor {

  private YamlConfigEditor() {}

  public static void apply(Path instanceDirectory, Map<String, Map<String, Object>> patches)
      throws IOException {
    Path root = instanceDirectory.toAbsolutePath().normalize();
    for (Map.Entry<String, Map<String, Object>> patch : patches.entrySet()) {
      apply(root, patch.getKey(), patch.getValue());
    }
  }

  private static void apply(Path root, String configuredTarget, Map<String, Object> patch)
      throws IOException {
    Path target = ConfinedConfigFile.resolve(root, configuredTarget, "YAML config");

    Map<String, Object> values = new LinkedHashMap<>();
    if (ConfinedConfigFile.existsRegular(target, "YAML config")) {
      LoaderOptions options = new LoaderOptions();
      options.setAllowDuplicateKeys(false);
      options.setCodePointLimit(ConfinedConfigFile.MAX_CONFIG_BYTES);
      options.setMaxAliasesForCollections(50);
      options.setNestingDepthLimit(50);
      Yaml yaml = new Yaml(new SafeConstructor(options));
      try (InputStream input = ConfinedConfigFile.openBounded(target)) {
        Object loaded = yaml.load(input);
        if (loaded != null) {
          values.putAll(stringMap(loaded, target));
        }
      }
    }
    merge(values, patch);

    Path temporary = ConfinedConfigFile.createTemporary(target);
    try {
      DumperOptions options = new DumperOptions();
      options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
      options.setPrettyFlow(true);
      options.setIndent(2);
      try (Writer output = ConfinedConfigFile.openTemporaryWriter(temporary)) {
        new Yaml(options).dump(values, output);
      }
      ConfinedConfigFile.requireBoundedOutput(temporary, target);
      ConfinedConfigFile.replace(temporary, target);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  private static Map<String, Object> stringMap(Object configured, Path path) throws IOException {
    if (!(configured instanceof Map<?, ?> map)) {
      throw new IOException("YAML config root must be an object: " + path);
    }
    Map<String, Object> result = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : map.entrySet()) {
      if (!(entry.getKey() instanceof String key)) {
        throw new IOException("YAML config contains a non-string key: " + path);
      }
      result.put(key, normalize(entry.getValue(), path));
    }
    return result;
  }

  private static Object normalize(Object value, Path path) throws IOException {
    if (value == null
        || value instanceof String
        || value instanceof Number
        || value instanceof Boolean) {
      return value;
    }
    if (value instanceof Map<?, ?>) {
      return stringMap(value, path);
    }
    if (value instanceof java.util.List<?> list) {
      java.util.ArrayList<Object> result = new java.util.ArrayList<>();
      for (Object item : list) {
        result.add(normalize(item, path));
      }
      return result;
    }
    throw new IOException("YAML config contains an unsupported value: " + path);
  }

  @SuppressWarnings("unchecked")
  private static void merge(Map<String, Object> target, Map<String, Object> patch) {
    patch.forEach(
        (key, value) -> {
          Object existing = target.get(key);
          if (existing instanceof Map<?, ?> existingMap && value instanceof Map<?, ?> patchMap) {
            Map<String, Object> nested = new LinkedHashMap<>((Map<String, Object>) existingMap);
            merge(nested, (Map<String, Object>) patchMap);
            target.put(key, nested);
          } else {
            target.put(key, value);
          }
        });
  }
}
