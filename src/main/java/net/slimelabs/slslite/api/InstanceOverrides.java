package net.slimelabs.slslite.api;

/**
 * Safe local overrides accepted by a public API start request.
 *
 * <p>A {@code null} component retains the blueprint value.
 *
 * @param memoryLimitMiB optional positive memory limit in mebibytes
 * @param persistent optional persistence override
 * @param seed optional bounded Minecraft world seed
 * @param viewDistance optional view distance from 2 through 32
 * @param simulationDistance optional simulation distance from 2 through 32
 * @param enableCommandBlock optional command-block setting
 */
public record InstanceOverrides(
    Integer memoryLimitMiB,
    Boolean persistent,
    String seed,
    Integer viewDistance,
    Integer simulationDistance,
    Boolean enableCommandBlock) {

  public static final InstanceOverrides NONE =
      new InstanceOverrides(null, null, null, null, null, null);

  public InstanceOverrides {
    if (memoryLimitMiB != null && memoryLimitMiB <= 0) {
      throw new IllegalArgumentException("memoryLimitMiB must be positive");
    }
    if (seed != null) {
      seed = seed.strip();
      if (seed.isEmpty() || seed.length() > 256 || seed.indexOf('\0') >= 0) {
        throw new IllegalArgumentException("seed must be 1-256 characters without NUL");
      }
    }
    validateDistance(viewDistance, "viewDistance");
    validateDistance(simulationDistance, "simulationDistance");
    if (viewDistance != null && simulationDistance != null && simulationDistance > viewDistance) {
      throw new IllegalArgumentException("simulationDistance must not exceed viewDistance");
    }
  }

  private static void validateDistance(Integer value, String field) {
    if (value != null && (value < 2 || value > 32)) {
      throw new IllegalArgumentException(field + " must be between 2 and 32");
    }
  }
}
