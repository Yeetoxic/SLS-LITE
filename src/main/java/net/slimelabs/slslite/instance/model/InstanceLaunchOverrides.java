package net.slimelabs.slslite.instance.model;

import java.util.LinkedHashMap;
import java.util.Map;
import net.slimelabs.slslite.blueprint.Blueprint;

/**
 * Validated per-instance differences from a loaded blueprint.
 *
 * <p>The deliberately small field set is safe to persist and reconstruct. Host placement,
 * container, software, and filesystem overrides remain outside local-mode scope.
 */
public record InstanceLaunchOverrides(
    Integer memoryLimitMiB,
    Boolean save,
    String seed,
    Integer viewDistance,
    Integer simulationDistance,
    Boolean enableCommandBlock) {

  public static final InstanceLaunchOverrides NONE =
      new InstanceLaunchOverrides(null, null, null, null, null, null);

  public InstanceLaunchOverrides {
    if (memoryLimitMiB != null && memoryLimitMiB <= 0) {
      throw new IllegalArgumentException("memory override must be a positive MiB value");
    }
    if (seed != null) {
      seed = seed.strip();
      if (seed.isEmpty()) {
        throw new IllegalArgumentException("seed override must not be blank");
      }
      if (seed.length() > 256 || seed.indexOf('\0') >= 0) {
        throw new IllegalArgumentException(
            "seed override must be at most 256 characters without NUL");
      }
    }
    if (viewDistance != null && (viewDistance < 2 || viewDistance > 32)) {
      throw new IllegalArgumentException("view-distance override must be between 2 and 32");
    }
    if (simulationDistance != null && (simulationDistance < 2 || simulationDistance > 32)) {
      throw new IllegalArgumentException("simulation-distance override must be between 2 and 32");
    }
    if (viewDistance != null && simulationDistance != null && simulationDistance > viewDistance) {
      throw new IllegalArgumentException(
          "simulation-distance override must not exceed view-distance");
    }
  }

  public boolean isEmpty() {
    return memoryLimitMiB == null
        && save == null
        && seed == null
        && viewDistance == null
        && simulationDistance == null
        && enableCommandBlock == null;
  }

  public Blueprint applyTo(Blueprint blueprint) {
    if (isEmpty()) {
      return blueprint;
    }
    Map<String, String> properties = new LinkedHashMap<>(blueprint.serverProperties());
    put(properties, "level-seed", seed);
    put(properties, "view-distance", viewDistance);
    put(properties, "simulation-distance", simulationDistance);
    put(properties, "enable-command-block", enableCommandBlock);
    return new Blueprint(
        blueprint.id(),
        blueprint.name(),
        blueprint.type(),
        blueprint.software(),
        blueprint.version(),
        blueprint.image(),
        blueprint.softwarePath(),
        memoryLimitMiB == null ? blueprint.memoryLimitMiB() : memoryLimitMiB,
        blueprint.maxPlayers(),
        blueprint.maxInstances(),
        save == null ? blueprint.save() : save,
        properties,
        blueprint.yamlConfigs(),
        blueprint.textFileConfigs(),
        blueprint.annotations(),
        blueprint.volumes(),
        blueprint.copies(),
        blueprint.persistentFiles(),
        blueprint.environment(),
        memoryLimitMiB == null && blueprint.inheritsSoftwareMemory(),
        blueprint.inheritsSoftwareImage());
  }

  private static void put(Map<String, String> values, String key, Object value) {
    if (value != null) {
      values.put(key, String.valueOf(value));
    }
  }
}
