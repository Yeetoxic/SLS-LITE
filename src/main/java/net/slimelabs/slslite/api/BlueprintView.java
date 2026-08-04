package net.slimelabs.slslite.api;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable, implementation-independent blueprint view.
 *
 * @param id stable blueprint identifier
 * @param name operator-facing blueprint name
 * @param type registry/type used for matchmaking
 * @param software configured server software identifier
 * @param version configured Minecraft/software version
 * @param memoryLimitMiB memory admission limit in mebibytes
 * @param maxPlayers maximum players per instance
 * @param maxInstances maximum concurrent instances
 * @param persistent whether instances survive normal proxy restarts
 * @param volumes immutable configured volume mappings
 * @param hasCopies whether the blueprint declares copy mappings
 * @param environmentVariables names of configured environment variables; values are never exposed
 * @param annotations deeply immutable extension-owned annotation data
 */
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
    annotations = NamespacedAnnotations.immutableValues(annotations);
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Blueprint " + field + " must not be blank");
    }
    return value;
  }
}
