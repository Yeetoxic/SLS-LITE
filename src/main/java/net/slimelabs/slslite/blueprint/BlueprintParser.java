package net.slimelabs.slslite.blueprint;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;
import net.slimelabs.slslite.io.BoundedFileReader;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

final class BlueprintParser {

  private static final Pattern VALID_ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
  private static final Pattern VALID_PROPERTY_KEY = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
  static final int MAX_BLUEPRINT_BYTES = 1024 * 1024;

  enum DocumentKind {
    BLUEPRINT,
    MIXIN,
    AMBIGUOUS,
    UNKNOWN
  }

  record ParsedFile(DocumentKind kind, BlueprintDocument blueprint, Mixin mixin) {}

  Blueprint parse(Path path) throws BlueprintException {
    ParsedFile parsed = parseFile(path);
    if (parsed.kind() != DocumentKind.BLUEPRINT || parsed.blueprint() == null) {
      throw error(path, "document is not a blueprint");
    }
    if (!parsed.blueprint().includes().isEmpty()) {
      throw error(path, "includes require mixin resolution; load the blueprints directory");
    }
    return BlueprintComposer.compose(parsed.blueprint(), parsed.blueprint().overlay());
  }

  ParsedFile parseFile(Path path) throws BlueprintException {
    try (InputStream input = BoundedFileReader.openNoFollow(path, MAX_BLUEPRINT_BYTES)) {
      Object document = yaml().load(input);
      Map<String, Object> root = asMap(document, "root", path);
      DocumentKind kind = classify(root);
      return switch (kind) {
        case BLUEPRINT -> new ParsedFile(kind, parseBlueprintDocument(root, path), null);
        case MIXIN -> new ParsedFile(kind, null, parseMixinDocument(root, path));
        case AMBIGUOUS -> throw error(path, "document has both mixin: and blueprint: sections");
        case UNKNOWN -> throw error(path, "document has neither mixin: nor blueprint: section");
      };
    } catch (IOException exception) {
      throw new BlueprintException("Unable to read blueprint " + path, exception);
    } catch (RuntimeException exception) {
      throw new BlueprintException(
          "Invalid YAML in " + path + ": " + exception.getMessage(), exception);
    }
  }

  private static DocumentKind classify(Map<String, Object> root) {
    boolean hasBlueprint = root.containsKey("blueprint");
    boolean hasMixin = root.containsKey("mixin");
    if (hasBlueprint && hasMixin) {
      return DocumentKind.AMBIGUOUS;
    }
    if (hasMixin) {
      return DocumentKind.MIXIN;
    }
    if (hasBlueprint) {
      return DocumentKind.BLUEPRINT;
    }
    return DocumentKind.UNKNOWN;
  }

  private static Yaml yaml() {
    LoaderOptions options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    options.setCodePointLimit(MAX_BLUEPRINT_BYTES);
    options.setMaxAliasesForCollections(50);
    options.setNestingDepthLimit(50);
    return new Yaml(new SafeConstructor(options));
  }

  private BlueprintDocument parseBlueprintDocument(Map<String, Object> root, Path path)
      throws BlueprintException {
    requireOnlyKeys(
        root, "", path, "blueprint", "includes", "server", "state", "save", "annotations");
    Map<String, Object> metadata = requiredMap(root, "blueprint", path);
    requireOnlyKeys(metadata, "blueprint", path, "id", "name", "type");
    String id = requiredString(metadata, "id", path);
    if (!VALID_ID.matcher(id).matches()) {
      throw error(path, "blueprint.id must match " + VALID_ID.pattern());
    }
    List<String> includes = parseIdList(root, "includes", path);
    Overlay overlay = parseOverlay(root, path);
    boolean save = optionalBoolean(root, path);
    return new BlueprintDocument(
        path,
        id,
        requiredString(metadata, "name", path),
        requiredString(metadata, "type", path),
        includes,
        overlay,
        save);
  }

  private Mixin parseMixinDocument(Map<String, Object> root, Path path) throws BlueprintException {
    requireOnlyKeys(root, "", path, "mixin", "extends", "server", "state", "annotations");
    Map<String, Object> metadata = requiredMap(root, "mixin", path);
    requireOnlyKeys(metadata, "mixin", path, "id", "description");
    String id = requiredString(metadata, "id", path);
    if (!VALID_ID.matcher(id).matches()) {
      throw error(path, "mixin.id must match " + VALID_ID.pattern());
    }
    String description = optionalString(metadata, "description", path);
    List<String> extendsMixins = parseIdList(root, "extends", path);
    Overlay overlay = parseOverlay(root, path);
    return new Mixin(id, description == null ? "" : description, extendsMixins, overlay);
  }

  private Overlay parseOverlay(Map<String, Object> root, Path path) throws BlueprintException {
    Overlay.Server server = null;
    if (root.containsKey("server")) {
      Map<String, Object> values = asMap(root.get("server"), "server", path);
      requireOnlyKeys(
          values, "server", path, "software", "version", "image", "path", "limits", "configs");
      Map<String, Object> limits = optionalMap(values, "limits", "server", path);
      if (limits.containsKey("max_players")) {
        throw error(
            path, "'server.limits.max_players' was removed; use annotations.sls-lite.max-players");
      }
      requireOnlyKeys(
          limits,
          "server.limits",
          path,
          "memory_limit",
          "max_instances",
          "swap",
          "io_weight",
          "cpu_limit",
          "disk_space",
          "threads",
          "oom_disabled");
      validateDistributedLimits(limits, path);
      String software = optionalString(values, "software", path);
      if (software != null) {
        software = software.toLowerCase(Locale.ROOT);
      }
      String softwarePath = optionalString(values, "path", path);
      validateRelativePath(softwarePath, "server.path", path);
      server =
          new Overlay.Server(
              software,
              optionalString(values, "version", path),
              optionalString(values, "image", path),
              softwarePath,
              optionalPositiveInteger(limits, "memory_limit", path),
              optionalPositiveInteger(limits, "max_instances", path),
              parseConfigs(values, path));
    }

    Overlay.State state = null;
    if (root.containsKey("state")) {
      Map<String, Object> values = asMap(root.get("state"), "state", path);
      if (values.containsKey("mounts")) {
        throw error(
            path,
            "'state.mounts' is not available in local mode; use a "
                + "contained state.volume with mode cow or ro");
      }
      requireOnlyKeys(values, "state", path, "volumes", "copy", "persistent_files", "env");
      state =
          new Overlay.State(
              parseVolumes(values, path),
              parseCopies(values, path),
              parsePersistentFiles(values, path),
              parseEnvironment(values, path));
    }

    Map<String, Object> annotations = optionalMap(root, "annotations", path);
    if (!annotations.isEmpty()) {
      BlueprintComposer.validateAnnotations(annotations, path);
    }
    return new Overlay(server, state, annotations);
  }

  private static List<String> parseIdList(Map<String, Object> root, String key, Path path)
      throws BlueprintException {
    if (!root.containsKey(key)) {
      return List.of();
    }
    Object configured = root.get(key);
    if (!(configured instanceof List<?> rawIds)) {
      throw error(path, "'" + key + "' must be a list");
    }
    ArrayList<String> ids = new ArrayList<>();
    HashSet<String> seen = new HashSet<>();
    for (Object value : rawIds) {
      if (!(value instanceof String stringValue) || stringValue.isBlank()) {
        throw error(path, "'" + key + "' contains an empty mixin id");
      }
      String id = stringValue.trim();
      if (!seen.add(id)) {
        throw error(path, "'" + key + "' contains duplicate mixin id \"" + id + "\"");
      }
      ids.add(id);
    }
    return List.copyOf(ids);
  }

  private static Map<String, Object> requiredMap(Map<String, Object> parent, String key, Path path)
      throws BlueprintException {
    if (!parent.containsKey(key)) {
      throw error(path, "missing required object '" + key + "'");
    }
    return asMap(parent.get(key), key, path);
  }

  private static Map<String, Object> optionalMap(Map<String, Object> parent, String key, Path path)
      throws BlueprintException {
    return optionalMap(parent, key, "", path);
  }

  private static Map<String, Object> optionalMap(
      Map<String, Object> parent, String key, String section, Path path) throws BlueprintException {
    if (!parent.containsKey(key)) {
      return Map.of();
    }
    String qualified = section.isBlank() ? key : section + "." + key;
    return asMap(parent.get(key), qualified, path);
  }

  private static Map<String, Object> asMap(Object value, String key, Path path)
      throws BlueprintException {
    if (!(value instanceof Map<?, ?> rawMap)) {
      throw error(path, "'" + key + "' must be an object");
    }

    Map<String, Object> result = new LinkedHashMap<>();
    for (Map.Entry<?, ?> entry : rawMap.entrySet()) {
      if (!(entry.getKey() instanceof String stringKey)) {
        throw error(path, "'" + key + "' contains a non-string key");
      }
      result.put(stringKey, entry.getValue());
    }
    return result;
  }

  private static String requiredString(Map<String, Object> values, String key, Path path)
      throws BlueprintException {
    Object value = values.get(key);
    if (value == null || value.toString().isBlank()) {
      throw error(path, "missing required value '" + key + "'");
    }
    return value.toString().trim();
  }

  private static String optionalString(Map<String, Object> values, String key, Path path)
      throws BlueprintException {
    Object value = values.get(key);
    if (value == null) {
      return null;
    }
    if (!(value instanceof String stringValue) || stringValue.isBlank()) {
      throw error(path, "'" + key + "' must be a non-blank string");
    }
    return stringValue.trim();
  }

  private static void validateRelativePath(String value, String key, Path path)
      throws BlueprintException {
    if (value == null) {
      return;
    }
    try {
      Path configured = Path.of(value);
      if (configured.isAbsolute() || configured.normalize().startsWith("..")) {
        throw error(path, "'" + key + "' must be a contained relative path");
      }
    } catch (java.nio.file.InvalidPathException exception) {
      throw error(path, "'" + key + "' is not a valid path");
    }
  }

  private static Integer optionalPositiveInteger(Map<String, Object> values, String key, Path path)
      throws BlueprintException {
    Object value = values.get(key);
    if (value == null) {
      return null;
    }
    if (!(value instanceof Number number)
        || number.intValue() <= 0
        || number.doubleValue() != number.intValue()) {
      throw error(path, "'" + key + "' must be a positive integer");
    }
    return number.intValue();
  }

  private static void validateDistributedLimits(Map<String, Object> limits, Path path)
      throws BlueprintException {
    for (String key : List.of("swap", "io_weight", "cpu_limit", "disk_space")) {
      Object value = limits.get(key);
      if (value != null
          && (!(value instanceof Number number)
              || number.intValue() < 0
              || number.doubleValue() != number.intValue())) {
        throw error(path, "'" + key + "' must be a non-negative integer");
      }
    }
    Object threads = limits.get("threads");
    if (threads != null && !(threads instanceof String)) {
      throw error(path, "'threads' must be a string");
    }
    Object oomDisabled = limits.get("oom_disabled");
    if (oomDisabled != null && !(oomDisabled instanceof Boolean)) {
      throw error(path, "'oom_disabled' must be true or false");
    }
  }

  private static boolean optionalBoolean(Map<String, Object> values, Path path)
      throws BlueprintException {
    Object value = values.get("save");
    if (value == null) {
      return false;
    }
    if (!(value instanceof Boolean booleanValue)) {
      throw error(path, "'" + "save" + "' must be true or false");
    }
    return booleanValue;
  }

  private static List<BlueprintVolume> parseVolumes(Map<String, Object> state, Path path)
      throws BlueprintException {
    Object configured = state.get("volumes");
    if (configured == null) {
      return List.of();
    }
    if (!(configured instanceof List<?> rawVolumes)) {
      throw error(path, "'state.volumes' must be a list");
    }

    ArrayList<BlueprintVolume> volumes = new ArrayList<>();
    for (int index = 0; index < rawVolumes.size(); index++) {
      String section = "state.volumes[" + index + "]";
      BlueprintVolume parsed =
          rawVolumes.get(index) instanceof String shorthand
              ? parseVolumeShorthand(shorthand, section, path)
              : parseVolumeMap(rawVolumes.get(index), section, path);
      volumes.add(parsed);
    }
    return List.copyOf(volumes);
  }

  private static List<BlueprintCopy> parseCopies(Map<String, Object> state, Path path)
      throws BlueprintException {
    Object configured = state.get("copy");
    if (configured == null) {
      return List.of();
    }
    if (!(configured instanceof List<?> rawCopies)) {
      throw error(path, "'state.copy' must be a list");
    }

    ArrayList<BlueprintCopy> copies = new ArrayList<>();
    for (int index = 0; index < rawCopies.size(); index++) {
      String section = "state.copy[" + index + "]";
      Object rawCopy = rawCopies.get(index);
      if (rawCopy instanceof String shorthand) {
        String[] parts = shorthand.split(":", 2);
        if (parts.length != 2 || parts[0].isBlank() || parts[1].isBlank()) {
          throw error(path, "'" + section + "' must be source:target");
        }
        copies.add(copy(parts[0], parts[1], section, path));
      } else {
        Map<String, Object> values = asMap(rawCopy, section, path);
        requireOnlyKeys(values, section, path, "source", "target");
        copies.add(
            copy(
                requiredString(values, "source", path),
                requiredString(values, "target", path),
                section,
                path));
      }
    }
    return List.copyOf(copies);
  }

  private static BlueprintCopy copy(String source, String target, String section, Path path)
      throws BlueprintException {
    String normalizedSource = source.trim();
    String normalizedTarget = target.trim();
    validateRelativePath(normalizedSource, section + ".source", path);
    validateRelativePath(normalizedTarget, section + ".target", path);
    if (normalizedSource.indexOf('\\') >= 0 || normalizedTarget.indexOf('\\') >= 0) {
      throw error(path, "'" + section + "' must use portable '/' separators");
    }
    return new BlueprintCopy(normalizedSource, normalizedTarget);
  }

  private static List<BlueprintPersistentFile> parsePersistentFiles(
      Map<String, Object> state, Path path) throws BlueprintException {
    Object configured = state.get("persistent_files");
    if (configured == null) {
      return List.of();
    }
    if (!(configured instanceof List<?> rawFiles)) {
      throw error(path, "'state.persistent_files' must be a list");
    }

    ArrayList<BlueprintPersistentFile> files = new ArrayList<>();
    for (int index = 0; index < rawFiles.size(); index++) {
      String section = "state.persistent_files[" + index + "]";
      Map<String, Object> values = asMap(rawFiles.get(index), section, path);
      requireOnlyKeys(values, section, path, "name", "source", "target");
      String name = requiredString(values, "name", path).trim();
      String source = requiredString(values, "source", path).trim();
      String target = requiredString(values, "target", path).trim();
      validateRelativePath(source, section + ".source", path);
      validateRelativePath(target, section + ".target", path);
      if (!VALID_ID.matcher(name.toLowerCase(Locale.ROOT)).matches()) {
        throw error(path, "'" + section + ".name' must match " + VALID_ID.pattern());
      }
      if (source.indexOf('\\') >= 0 || target.indexOf('\\') >= 0) {
        throw error(path, "'" + section + "' must use portable '/' separators");
      }
      if (!source.toLowerCase(Locale.ROOT).startsWith("volumes/")) {
        throw error(path, "'" + section + ".source' must stay below volumes/");
      }
      if (java.util.Arrays.stream(target.split("/"))
          .anyMatch(segment -> segment.toLowerCase(Locale.ROOT).startsWith(".sls-lite-"))) {
        throw error(path, "'" + section + ".target' uses a reserved SLS-LITE path");
      }
      files.add(new BlueprintPersistentFile(name, source, target));
    }
    BlueprintComposer.validatePersistentFileUniqueness(files, path);
    return List.copyOf(files);
  }

  private static Map<String, String> parseEnvironment(Map<String, Object> state, Path path)
      throws BlueprintException {
    Map<String, Object> configured = optionalMap(state, "env", path);
    if (configured.isEmpty()) {
      return Map.of();
    }
    Map<String, String> environment = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : configured.entrySet()) {
      if (!(entry.getValue() instanceof String value)) {
        throw error(path, "'state.env." + entry.getKey() + "' must be a string");
      }
      environment.put(entry.getKey(), value);
    }
    try {
      return Blueprint.validateEnvironment(environment, false);
    } catch (IllegalArgumentException exception) {
      throw error(path, exception.getMessage());
    }
  }

  private static BlueprintVolume parseVolumeMap(Object configured, String section, Path path)
      throws BlueprintException {
    Map<String, Object> volume = asMap(configured, section, path);
    requireOnlyKeys(volume, section, path, "name", "source", "target", "mode");
    String name = requiredString(volume, "name", path);
    String source = requiredString(volume, "source", path);
    String target = requiredString(volume, "target", path);
    String mode = optionalString(volume, "mode", path);
    return volume(name, source, target, mode == null ? "cow" : mode, section, path);
  }

  private static BlueprintVolume parseVolumeShorthand(String configured, String section, Path path)
      throws BlueprintException {
    String[] parts = configured.split(":", -1);
    if (parts.length != 3 && parts.length != 4) {
      throw error(path, "'" + section + "' shorthand must be name:source:target[:mode]");
    }
    for (int index = 0; index < parts.length; index++) {
      parts[index] = parts[index].trim();
      if (parts[index].isEmpty()) {
        throw error(path, "'" + section + "' shorthand contains a blank segment");
      }
    }
    return volume(
        parts[0], parts[1], parts[2], parts.length == 4 ? parts[3] : "cow", section, path);
  }

  private static BlueprintVolume volume(
      String name, String source, String target, String mode, String section, Path path)
      throws BlueprintException {
    BlueprintVolume.Mode parsedMode;
    try {
      parsedMode = BlueprintVolume.Mode.valueOf(mode.trim().toUpperCase(Locale.ROOT));
    } catch (IllegalArgumentException exception) {
      throw error(path, "'" + section + ".mode' must be cow, ro, or rw");
    }
    return new BlueprintVolume(name, source, target, parsedMode);
  }

  private static Map<String, Overlay.Config> parseConfigs(Map<String, Object> server, Path path)
      throws BlueprintException {
    Map<String, Object> configs = optionalMap(server, "configs", path);
    if (configs.isEmpty()) {
      return Map.of();
    }

    LinkedHashMap<String, Overlay.Config> parsed = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : configs.entrySet()) {
      String target = entry.getKey();
      if (target.isBlank()) {
        throw error(path, "'server.configs target' must not be blank");
      }
      validateRelativePath(target, "server.configs target", path);
      Map<String, Object> config = asMap(entry.getValue(), "server.configs." + target, path);
      requireOnlyKeys(config, "server.configs." + target, path, "parser", "find");
      String parser = requiredString(config, "parser", path).toLowerCase(Locale.ROOT);
      Map<String, Object> find = optionalMap(config, "find", path);
      switch (parser) {
        case "properties" -> {
          if (!target.equals("server.properties")) {
            throw error(
                path,
                "properties config target '"
                    + target
                    + "' is not supported; use server.properties");
          }
          parsed.put(target, new Overlay.Config(parser, objectMap(parseProperties(find, path))));
        }
        case "yaml" -> {
          String lowerTarget = target.toLowerCase(Locale.ROOT);
          if (!lowerTarget.endsWith(".yml") && !lowerTarget.endsWith(".yaml")) {
            throw error(path, "YAML config target must end in .yml or .yaml");
          }
          parsed.put(target, new Overlay.Config(parser, validateYamlMap(find, target, path)));
        }
        case "file" ->
            parsed.put(
                target,
                new Overlay.Config(
                    parser, objectMap(parseTextFileReplacements(find, target, path))));
        default ->
            throw error(path, "unsupported parser '" + parser + "' for server.configs." + target);
      }
    }
    return Map.copyOf(parsed);
  }

  private static Map<String, Object> objectMap(Map<String, String> values) {
    LinkedHashMap<String, Object> copied = new LinkedHashMap<>(values);
    return Map.copyOf(copied);
  }

  private static Map<String, String> parseTextFileReplacements(
      Map<String, Object> configured, String target, Path path) throws BlueprintException {
    Map<String, String> replacements = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : configured.entrySet()) {
      String match = entry.getKey();
      if (match.isEmpty() || match.contains("\n") || match.contains("\r")) {
        throw error(
            path,
            "'server.configs." + target + ".find' keys must be non-empty single-line prefixes");
      }
      Object value = entry.getValue();
      if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) {
        throw error(
            path,
            "'server.configs."
                + target
                + ".find."
                + match
                + "' must be a string, number, or boolean");
      }
      replacements.put(match, value.toString());
    }
    List<String> prefixes = List.copyOf(replacements.keySet());
    for (int left = 0; left < prefixes.size(); left++) {
      for (int right = left + 1; right < prefixes.size(); right++) {
        String first = prefixes.get(left);
        String second = prefixes.get(right);
        if (first.startsWith(second) || second.startsWith(first)) {
          throw error(
              path,
              "'server.configs."
                  + target
                  + ".find' prefixes overlap: '"
                  + first
                  + "' and '"
                  + second
                  + "'");
        }
      }
    }
    return java.util.Collections.unmodifiableMap(replacements);
  }

  private static Map<String, String> parseProperties(Map<String, Object> configured, Path path)
      throws BlueprintException {
    Map<String, String> properties = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : configured.entrySet()) {
      String key = entry.getKey();
      if (!VALID_PROPERTY_KEY.matcher(key).matches()) {
        throw error(path, "invalid server.properties key '" + key + "'");
      }
      Object value = entry.getValue();
      if (!(value instanceof String || value instanceof Number || value instanceof Boolean)) {
        throw error(
            path,
            "'server.configs.server.properties.find."
                + key
                + "' must be a string, number, or boolean");
      }
      String rendered = value.toString();
      if (rendered.contains("\n") || rendered.contains("\r")) {
        throw error(
            path,
            "'server.configs.server.properties.find." + key + "' must be a single-line value");
      }
      properties.put(key, rendered);
    }
    return Map.copyOf(properties);
  }

  private static Map<String, Object> validateYamlMap(
      Map<String, Object> configured, String target, Path path) throws BlueprintException {
    Map<String, Object> result = new LinkedHashMap<>();
    for (Map.Entry<String, Object> entry : configured.entrySet()) {
      result.put(
          entry.getKey(),
          validateYamlValue(
              entry.getValue(), "server.configs." + target + ".find." + entry.getKey(), path));
    }
    return Map.copyOf(result);
  }

  private static Object validateYamlValue(Object value, String key, Path path)
      throws BlueprintException {
    if (value instanceof String || value instanceof Number || value instanceof Boolean) {
      return value;
    }
    switch (value) {
      case null -> throw error(path, "'" + key + "' must not be null");
      case Map<?, ?> map -> {
        return validateYamlMap(asMap(value, key, path), key, path);
      }
      case List<?> list -> {
        ArrayList<Object> values = new ArrayList<>();
        for (int index = 0; index < list.size(); index++) {
          values.add(validateYamlValue(list.get(index), key + "[" + index + "]", path));
        }
        return List.copyOf(values);
      }
      default -> {}
    }
    throw error(path, "'" + key + "' contains an unsupported YAML value");
  }

  private static void requireOnlyKeys(
      Map<String, Object> values, String section, Path path, String... allowedKeys)
      throws BlueprintException {
    Set<String> allowed = Set.of(allowedKeys);
    for (String key : values.keySet()) {
      if (!allowed.contains(key)) {
        throw error(
            path, net.slimelabs.slslite.config.YamlValues.unknownKeyMessage(section, key, allowed));
      }
    }
  }

  private static BlueprintException error(Path path, String message) {
    return new BlueprintException(path + ": " + message);
  }
}
