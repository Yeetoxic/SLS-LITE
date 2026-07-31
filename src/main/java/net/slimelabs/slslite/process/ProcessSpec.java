package net.slimelabs.slslite.process;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public record ProcessSpec(
    List<String> command,
    Path workingDirectory,
    Pattern readinessPattern,
    Duration startupTimeout,
    String stopCommand,
    Duration stopTimeout,
    Map<String, String> environment) {

  public ProcessSpec {
    command = List.copyOf(command);
    if (command.isEmpty() || command.stream().anyMatch(value -> value == null || value.isBlank())) {
      throw new IllegalArgumentException("command must contain non-blank arguments");
    }
    workingDirectory = workingDirectory.toAbsolutePath().normalize();
    if (readinessPattern == null) {
      throw new IllegalArgumentException("readinessPattern must not be null");
    }
    if (startupTimeout == null || startupTimeout.isZero() || startupTimeout.isNegative()) {
      throw new IllegalArgumentException("startupTimeout must be positive");
    }
    if (stopCommand == null
        || stopCommand.isBlank()
        || stopCommand.contains("\n")
        || stopCommand.contains("\r")) {
      throw new IllegalArgumentException("stopCommand must be one non-blank line");
    }
    if (stopTimeout == null || stopTimeout.isZero() || stopTimeout.isNegative()) {
      throw new IllegalArgumentException("stopTimeout must be positive");
    }
    environment = Map.copyOf(environment);
  }

  public ProcessSpec(
      List<String> command,
      Path workingDirectory,
      Pattern readinessPattern,
      Duration startupTimeout,
      String stopCommand,
      Duration stopTimeout) {
    this(
        command,
        workingDirectory,
        readinessPattern,
        startupTimeout,
        stopCommand,
        stopTimeout,
        Map.of());
  }
}
