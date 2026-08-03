package net.slimelabs.slslite.api;

import java.util.List;
import java.util.Map;
import java.util.Set;

/** Immutable, implementation-independent blueprint view. */
public record BlueprintView(
    String id,
    String name,
    String type,
    String software,
    String version,
    int memoryLimitMiB,
    int maxPlayers,
    int maxInstances,
    boolean persistent,
    List<VolumeView> volumes,
    boolean hasCopies,
    Set<String> environmentVariables,
    Map<String, Object> annotations) {

  public BlueprintView {
    id = requireText(id, "id");
    name = requireText(name, "name");
    type = requireText(type, "type");
    software = requireText(software, "software");
    version = requireText(version, "version");
    if (memoryLimitMiB <= 0 || maxPlayers <= 0 || maxInstances <= 0) {
      throw new IllegalArgumentException("Blueprint limits must be positive");
    }
    volumes = List.copyOf(volumes);
    environmentVariables = Set.copyOf(environmentVariables);
    annotations = immutableMap(annotations);
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Blueprint " + field + " must not be blank");
    }
    return value;
  }

  private static Map<String, Object> immutableMap(Map<String, Object> source) {
    java.util.LinkedHashMap<String, Object> copy = new java.util.LinkedHashMap<>();
    source.forEach((key, value) -> copy.put(key, immutableValue(value)));
    return java.util.Collections.unmodifiableMap(copy);
  }

  private static Object immutableValue(Object value) {
    if (value instanceof Map<?, ?> map) {
      java.util.LinkedHashMap<Object, Object> copy = new java.util.LinkedHashMap<>();
      map.forEach((key, nested) -> copy.put(key, immutableValue(nested)));
      return java.util.Collections.unmodifiableMap(copy);
    }
    if (value instanceof List<?> list) {
      return list.stream().map(BlueprintView::immutableValue).toList();
    }
    return value;
  }
}
