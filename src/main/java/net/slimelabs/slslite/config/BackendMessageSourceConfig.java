package net.slimelabs.slslite.config;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.regex.Pattern;

public record BackendMessageSourceConfig(
    String id,
    String server,
    String blueprint,
    Set<BackendMessageAction> actions,
    List<String> commandRoots) {

  private static final Pattern SOURCE_ID = Pattern.compile("[a-z0-9][a-z0-9._-]{0,63}");
  private static final Pattern SERVER_NAME = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,127}");
  private static final Pattern BLUEPRINT_ID =
      Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}/[A-Za-z0-9][A-Za-z0-9._-]{0,63}");

  public BackendMessageSourceConfig {
    id = id == null ? "" : id.trim().toLowerCase(Locale.ROOT);
    server = server == null ? "" : server.trim();
    blueprint = blueprint == null ? "" : blueprint.trim();
    if (!SOURCE_ID.matcher(id).matches()) {
      throw new IllegalArgumentException(
          "backend messaging source id must match " + SOURCE_ID.pattern());
    }
    if (server.isBlank() == blueprint.isBlank()) {
      throw new IllegalArgumentException(
          "backend messaging source '" + id + "' must set exactly one of server or blueprint");
    }
    if (!server.isBlank() && !SERVER_NAME.matcher(server).matches()) {
      throw new IllegalArgumentException(
          "backend messaging source '" + id + "' has an invalid server name");
    }
    if (!blueprint.isBlank() && !BLUEPRINT_ID.matcher(blueprint).matches()) {
      throw new IllegalArgumentException(
          "backend messaging source '" + id + "' blueprint must use registry/id");
    }
    actions = Set.copyOf(actions == null ? Set.of() : actions);
    commandRoots = List.copyOf(commandRoots == null ? List.of() : commandRoots);
    if (actions.isEmpty()) {
      throw new IllegalArgumentException(
          "backend messaging source '" + id + "' must permit at least one action");
    }
    if (actions.contains(BackendMessageAction.COMMAND) && commandRoots.isEmpty()) {
      throw new IllegalArgumentException(
          "backend messaging source '" + id + "' permits command but has no command_roots");
    }
    if (!actions.contains(BackendMessageAction.COMMAND) && !commandRoots.isEmpty()) {
      throw new IllegalArgumentException(
          "backend messaging source '" + id + "' has command_roots without command action");
    }
    if (commandRoots.size() > 32) {
      throw new IllegalArgumentException(
          "backend messaging source '" + id + "' may define at most 32 command roots");
    }
    LinkedHashSet<String> normalizedRoots = new LinkedHashSet<>();
    for (String root : commandRoots) {
      String normalized = normalizeCommand(root);
      if (!normalized.equals("sls") && !normalized.startsWith("sls ")) {
        throw new IllegalArgumentException(
            "backend messaging source '" + id + "' command roots must begin with sls");
      }
      if (!normalizedRoots.add(normalized)) {
        throw new IllegalArgumentException(
            "backend messaging source '" + id + "' contains duplicate command root '" + root + "'");
      }
    }
    commandRoots = List.copyOf(normalizedRoots);
  }

  public boolean exactServer() {
    return !server.isBlank();
  }

  public boolean permitsCommand(String command) {
    String normalized = normalizeCommand(command);
    return commandRoots.stream()
        .anyMatch(root -> normalized.equals(root) || normalized.startsWith(root + " "));
  }

  static String normalizeCommand(String command) {
    String candidate = command == null ? "" : command;
    for (int index = 0; index < candidate.length(); index++) {
      if (Character.isISOControl(candidate.charAt(index))) {
        throw new IllegalArgumentException("backend command must not contain control characters");
      }
    }
    String normalized = candidate.strip();
    if (normalized.startsWith("/")) {
      normalized = normalized.substring(1).stripLeading();
    }
    normalized = normalized.replaceAll(" +", " ").toLowerCase(Locale.ROOT);
    if (normalized.isBlank() || normalized.length() > 512) {
      throw new IllegalArgumentException("backend command must contain 1-512 characters");
    }
    return normalized;
  }
}
