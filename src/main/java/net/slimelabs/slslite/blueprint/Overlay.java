package net.slimelabs.slslite.blueprint;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Partial server/state/annotation overlay shared by mixins and unresolved blueprints.
 *
 * <p>Absent sections are {@code null}. Completeness is validated only after includes are applied.
 */
public record Overlay(Server server, State state, Map<String, Object> annotations) {

  public Overlay {
    annotations = copyAnnotations(annotations);
  }

  public static Overlay empty() {
    return new Overlay(null, null, Map.of());
  }

  public boolean isEmpty() {
    return server == null && state == null && annotations.isEmpty();
  }

  static Map<String, Object> copyAnnotations(Map<String, Object> configured) {
    if (configured == null || configured.isEmpty()) {
      return Map.of();
    }
    LinkedHashMap<String, Object> copied = new LinkedHashMap<>();
    configured.forEach((key, value) -> copied.put(key, copyAnnotationValue(value)));
    return java.util.Collections.unmodifiableMap(copied);
  }

  static Object copyAnnotationValue(Object value) {
    if (value instanceof Map<?, ?> map) {
      LinkedHashMap<Object, Object> copied = new LinkedHashMap<>();
      map.forEach((key, nested) -> copied.put(key, copyAnnotationValue(nested)));
      return java.util.Collections.unmodifiableMap(copied);
    }
    if (value instanceof List<?> list) {
      ArrayList<Object> copied = new ArrayList<>(list.size());
      list.forEach(item -> copied.add(copyAnnotationValue(item)));
      return java.util.Collections.unmodifiableList(copied);
    }
    return value;
  }

  public record Server(
      String software,
      String version,
      String image,
      String path,
      Integer memoryLimitMiB,
      Integer maxInstances,
      Map<String, Config> configs) {

    public Server {
      software = blankToNull(software);
      version = blankToNull(version);
      image = blankToNull(image);
      path = blankToNull(path);
      configs = copyConfigs(configs);
    }

    public boolean inheritsSoftwareMemory() {
      return memoryLimitMiB == null;
    }

    public boolean inheritsSoftwareImage() {
      return image == null;
    }
  }

  public record State(
      List<BlueprintVolume> volumes,
      List<BlueprintCopy> copies,
      List<BlueprintPersistentFile> persistentFiles,
      Map<String, String> environment) {

    public State {
      volumes = volumes == null ? List.of() : List.copyOf(volumes);
      copies = copies == null ? List.of() : List.copyOf(copies);
      persistentFiles = persistentFiles == null ? List.of() : List.copyOf(persistentFiles);
      environment =
          environment == null || environment.isEmpty() ? Map.of() : Map.copyOf(environment);
    }
  }

  public record Config(String parser, Map<String, Object> find) {

    public Config {
      parser = parser == null ? "" : parser;
      find = find == null || find.isEmpty() ? Map.of() : copyFind(find);
    }
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value.trim();
  }

  private static Map<String, Config> copyConfigs(Map<String, Config> configured) {
    if (configured == null || configured.isEmpty()) {
      return Map.of();
    }
    return Map.copyOf(configured);
  }

  private static Map<String, Object> copyFind(Map<String, Object> find) {
    LinkedHashMap<String, Object> copied = new LinkedHashMap<>();
    find.forEach((key, value) -> copied.put(key, copyAnnotationValue(value)));
    return Map.copyOf(copied);
  }
}
