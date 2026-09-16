package net.slimelabs.slslite.blueprint;

import java.nio.file.Path;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Builds a complete {@link Blueprint} from a composed overlay.
 */
final class BlueprintComposer {

  private static final int DEFAULT_MEMORY_MIB = 1024;
  private static final int DEFAULT_MAX_PLAYERS = 10_000;
  private static final int DEFAULT_MAX_INSTANCES = Integer.MAX_VALUE;
  private static final int MAX_COPY_ENTRIES = 128;
  private static final int MAX_PERSISTENT_FILES = 32;

  private BlueprintComposer() {}

  static Blueprint compose(BlueprintDocument document, Overlay overlay) throws BlueprintException {
    Path path = document.source();
    Overlay.Server server = overlay.server();
    if (server == null) {
      throw error(path, "missing required object 'server'");
    }
    if (server.software() == null) {
      throw error(path, "missing required value 'software'");
    }
    if (server.version() == null) {
      throw error(path, "missing required value 'version'");
    }

    Overlay.State state =
        overlay.state() == null
            ? new Overlay.State(List.of(), List.of(), List.of(), Map.of())
            : overlay.state();
    if (state.copies().size() > MAX_COPY_ENTRIES) {
      throw error(path, "'state.copy' must not contain more than 128 entries");
    }
    if (state.persistentFiles().size() > MAX_PERSISTENT_FILES) {
      throw error(path, "'state.persistent_files' must not contain more than 32 entries");
    }
    validatePersistentFileUniqueness(state.persistentFiles(), path);
    validateAnnotations(overlay.annotations(), path);

    FlattenedConfigs configs = flattenConfigs(server.configs(), path);
    Map<String, String> environment;
    try {
      environment = Blueprint.validateEnvironment(state.environment());
    } catch (IllegalArgumentException exception) {
      throw error(path, exception.getMessage());
    }

    int memory = server.memoryLimitMiB() == null ? DEFAULT_MEMORY_MIB : server.memoryLimitMiB();
    int maxPlayers =
        SLSLiteBlueprintAnnotations.maxPlayers(overlay.annotations())
            .orElse(
                VSLSBlueprintAnnotations.maxPlayers(overlay.annotations())
                    .orElse(DEFAULT_MAX_PLAYERS));
    int maxInstances =
        server.maxInstances() == null
            ? VSLSBlueprintAnnotations.maxInstances(overlay.annotations())
                .orElse(DEFAULT_MAX_INSTANCES)
            : server.maxInstances();

    return new Blueprint(
        document.id(),
        document.name(),
        document.type(),
        server.software(),
        server.version(),
        server.image(),
        server.path(),
        memory,
        maxPlayers,
        maxInstances,
        document.save(),
        configs.serverProperties(),
        configs.yamlConfigs(),
        configs.textFileConfigs(),
        overlay.annotations(),
        state.volumes(),
        state.copies(),
        state.persistentFiles(),
        environment,
        document.includes(),
        server.inheritsSoftwareMemory(),
        server.inheritsSoftwareImage());
  }

  static void validateAnnotations(Map<String, Object> annotations, Path path)
      throws BlueprintException {
    try {
      VSLSBlueprintAnnotations.validate(annotations);
      SLSLiteBlueprintAnnotations.maxPlayers(annotations);
      BlueprintProcessTimeouts.fromAnnotations(annotations);
    } catch (IllegalArgumentException exception) {
      throw error(path, exception.getMessage());
    }
  }

  static void validatePersistentFileUniqueness(List<BlueprintPersistentFile> files, Path path)
      throws BlueprintException {
    HashSet<String> names = new HashSet<>();
    HashSet<String> sources = new HashSet<>();
    HashSet<String> targets = new HashSet<>();
    for (BlueprintPersistentFile file : files) {
      if (!names.add(file.name().toLowerCase(Locale.ROOT))) {
        throw error(path, "duplicate persistent file name: " + file.name());
      }
      if (!sources.add(file.source().toLowerCase(Locale.ROOT))) {
        throw error(path, "duplicate persistent file source: " + file.source());
      }
      if (!targets.add(file.target().toLowerCase(Locale.ROOT))) {
        throw error(path, "duplicate persistent file target: " + file.target());
      }
    }
  }

  private static FlattenedConfigs flattenConfigs(Map<String, Overlay.Config> configs, Path path)
      throws BlueprintException {
    if (configs == null || configs.isEmpty()) {
      return new FlattenedConfigs(Map.of(), Map.of(), Map.of());
    }
    LinkedHashMap<String, String> properties = new LinkedHashMap<>();
    LinkedHashMap<String, Map<String, Object>> yamlConfigs = new LinkedHashMap<>();
    LinkedHashMap<String, Map<String, String>> textFileConfigs = new LinkedHashMap<>();
    for (Map.Entry<String, Overlay.Config> entry : configs.entrySet()) {
      Overlay.Config config = entry.getValue();
      if ("properties".equals(config.parser())) {
        config
            .find()
            .forEach((key, value) -> properties.put(key, value == null ? "" : value.toString()));
      } else if ("yaml".equals(config.parser())) {
        yamlConfigs.put(entry.getKey(), Overlay.copyAnnotations(config.find()));
      } else if ("file".equals(config.parser())) {
        LinkedHashMap<String, String> replacements = new LinkedHashMap<>();
        config
            .find()
            .forEach((key, value) -> replacements.put(key, value == null ? "" : value.toString()));
        textFileConfigs.put(entry.getKey(), Map.copyOf(replacements));
      } else {
        throw error(
            path,
            "unsupported parser '" + config.parser() + "' for server.configs." + entry.getKey());
      }
    }
    return new FlattenedConfigs(
        Map.copyOf(properties), Map.copyOf(yamlConfigs), Map.copyOf(textFileConfigs));
  }

  private static BlueprintException error(Path path, String message) {
    return new BlueprintException(path + ": " + message);
  }

  private record FlattenedConfigs(
      Map<String, String> serverProperties,
      Map<String, Map<String, Object>> yamlConfigs,
      Map<String, Map<String, String>> textFileConfigs) {}
}
