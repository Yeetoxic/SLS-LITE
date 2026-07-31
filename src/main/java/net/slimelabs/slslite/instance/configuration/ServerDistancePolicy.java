package net.slimelabs.slslite.instance.configuration;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import net.slimelabs.slslite.instance.InstancePreparationException;

final class ServerDistancePolicy {

  private static final int MINIMUM_DISTANCE = 2;
  private static final int MAXIMUM_DISTANCE = 32;
  private static final Pattern MINECRAFT_VERSION =
      Pattern.compile("^1\\.(\\d+)(?:\\.\\d+)?(?:[-+].*)?$");

  private ServerDistancePolicy() {}

  static void validate(String version, Map<String, String> properties)
      throws InstancePreparationException {
    Integer viewDistance = distance(properties, "view-distance");
    Integer simulationDistance = distance(properties, "simulation-distance");
    if (simulationDistance == null) {
      return;
    }

    Matcher minecraftVersion = MINECRAFT_VERSION.matcher(version);
    if (minecraftVersion.matches() && Integer.parseInt(minecraftVersion.group(1)) < 18) {
      throw new InstancePreparationException(
          "simulation-distance requires Minecraft 1.18 or newer; blueprint version is " + version);
    }
    if (viewDistance != null && simulationDistance > viewDistance) {
      throw new InstancePreparationException("simulation-distance must not exceed view-distance");
    }
  }

  private static Integer distance(Map<String, String> properties, String key)
      throws InstancePreparationException {
    String configured = properties.get(key);
    if (configured == null) {
      return null;
    }
    int parsed;
    try {
      parsed = Integer.parseInt(configured);
    } catch (NumberFormatException exception) {
      throw new InstancePreparationException(
          key + " must be an integer between " + MINIMUM_DISTANCE + " and " + MAXIMUM_DISTANCE);
    }
    if (parsed < MINIMUM_DISTANCE || parsed > MAXIMUM_DISTANCE) {
      throw new InstancePreparationException(
          key + " must be between " + MINIMUM_DISTANCE + " and " + MAXIMUM_DISTANCE);
    }
    return parsed;
  }
}
