package net.slimelabs.slslite.command;

import java.util.HashMap;
import java.util.Map;
import net.slimelabs.slslite.instance.model.InstanceLaunchOverrides;

public final class CreateOverrideParser {

  public static final java.util.List<String> FLAGS =
      java.util.List.of(
          "--save=", "--memory=", "--seed=", "--view-distance=", "--enable-command-block=");

  private CreateOverrideParser() {}

  public static InstanceLaunchOverrides parse(String[] arguments) {
    Map<String, String> values = new HashMap<>();
    for (int index = 3; index < arguments.length; index++) {
      String argument = arguments[index];
      if (!argument.startsWith("--") || !argument.contains("=")) {
        throw new IllegalArgumentException("Create overrides must use --name=value: " + argument);
      }
      int separator = argument.indexOf('=');
      String name = argument.substring(2, separator);
      String value = argument.substring(separator + 1);
      if (value.isEmpty()) {
        throw new IllegalArgumentException("Create override --" + name + " requires a value");
      }
      if (!isSupported(name)) {
        throw new IllegalArgumentException(
            "Create override --" + name + " is unavailable in local mode");
      }
      if (values.putIfAbsent(name, value) != null) {
        throw new IllegalArgumentException("Duplicate create override: --" + name);
      }
    }

    return new InstanceLaunchOverrides(
        integer(values, "memory"),
        bool(values, "save"),
        values.get("seed"),
        integer(values, "view-distance"),
        bool(values, "enable-command-block"));
  }

  private static boolean isSupported(String name) {
    return FLAGS.stream().anyMatch(flag -> flag.equals("--" + name + "="));
  }

  private static Integer integer(Map<String, String> values, String name) {
    String value = values.get(name);
    if (value == null) {
      return null;
    }
    try {
      return Integer.valueOf(value);
    } catch (NumberFormatException exception) {
      throw new IllegalArgumentException("--" + name + " must be an integer", exception);
    }
  }

  private static Boolean bool(Map<String, String> values, String name) {
    String value = values.get(name);
    if (value == null) {
      return null;
    }
    if (!"true".equalsIgnoreCase(value) && !"false".equalsIgnoreCase(value)) {
      throw new IllegalArgumentException("--" + name + " must be true or false");
    }
    return Boolean.valueOf(value);
  }
}
