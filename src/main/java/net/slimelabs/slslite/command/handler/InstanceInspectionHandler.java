package net.slimelabs.slslite.command.handler;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.OptionalLong;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.slimelabs.slslite.command.CommandAuthorizer;
import net.slimelabs.slslite.command.CommandInstanceAccess;
import net.slimelabs.slslite.command.CommandMessages;
import net.slimelabs.slslite.instance.ManagedInstance;
import net.slimelabs.slslite.instance.ServerController;
import net.slimelabs.slslite.instance.diagnostics.InstanceLogPage;
import net.slimelabs.slslite.velocity.LocalJoinService;

final class InstanceInspectionHandler {

  private static final int DEFAULT_LOG_PAGE = 1;
  private static final int DEFAULT_LOG_LINES = 50;
  private static final int MAX_LOG_LINES = 100;

  private final ServerController instances;
  private final LocalJoinService joinService;
  private final CommandAuthorizer authorizer;
  private final CommandInstanceAccess instanceAccess;

  InstanceInspectionHandler(
      ServerController instances,
      LocalJoinService joinService,
      CommandAuthorizer authorizer,
      CommandInstanceAccess instanceAccess) {
    this.instances = instances;
    this.joinService = joinService;
    this.authorizer = authorizer;
    this.instanceAccess = instanceAccess;
  }

  void info(CommandSource source, String[] arguments) {
    sendInstanceStatus(source, arguments, "info");
  }

  void list(CommandSource source) {
    if (instances.getAll().isEmpty()) {
      source.sendMessage(CommandMessages.message("No servers found.", NamedTextColor.RED));
      return;
    }
    source.sendMessage(CommandMessages.listHeader());
    instances.getAll().stream()
        .sorted(Comparator.comparing(ManagedInstance::id))
        .forEach(
            instance ->
                source.sendMessage(
                    CommandMessages.listEntry(instance, instanceAccess.playersOn(instance))));
    source.sendMessage(CommandMessages.listFooter());
  }

  void status(CommandSource source, String[] arguments) {
    sendInstanceStatus(source, arguments, "status");
  }

  void logs(CommandSource source, String[] arguments) {
    if (!requireAdmin(source, "logs", "view managed server logs")) {
      return;
    }
    if (arguments.length < 2 || arguments.length > 4) {
      source.sendMessage(CommandMessages.incorrectUsage());
      source.sendMessage(CommandMessages.usage("/sls logs", "server"));
      return;
    }

    Integer page =
        arguments.length >= 3 ? parsePositiveInt(arguments[2]) : Integer.valueOf(DEFAULT_LOG_PAGE);
    Integer lines =
        arguments.length == 4 ? parseLogLines(arguments[3]) : Integer.valueOf(DEFAULT_LOG_LINES);
    if (page == null) {
      invalidNumber(source, arguments[2]);
      return;
    }
    if (lines == null) {
      invalidNumber(source, arguments[3]);
      return;
    }
    lines = Math.min(lines, MAX_LOG_LINES);

    ManagedInstance instance = instanceAccess.resolve(source, arguments[1]);
    if (instance == null) {
      return;
    }
    InstanceLogPage result = instance.logs(page, lines);
    int totalPages = Math.max(1, (result.totalRetainedLines() + lines - 1) / lines);
    if (page > totalPages) {
      source.sendMessage(
          CommandMessages.prefix()
              .append(Component.text("Page " + page + " does not exist ", NamedTextColor.RED))
              .append(Component.text("(valid range: 1-" + totalPages + ")", NamedTextColor.GRAY)));
      return;
    }

    source.sendMessage(
        Component.text("----------------", NamedTextColor.DARK_GRAY)
            .decorate(TextDecoration.STRIKETHROUGH, TextDecoration.BOLD)
            .append(
                Component.text(" Logs for " + instance.id() + " ", NamedTextColor.GOLD)
                    .decoration(TextDecoration.STRIKETHROUGH, false))
            .append(
                Component.text("----------------", NamedTextColor.DARK_GRAY)
                    .decorate(TextDecoration.STRIKETHROUGH, TextDecoration.BOLD)));
    if (result.lines().isEmpty()) {
      source.sendMessage(
          Component.text("No output has been retained for this server.", NamedTextColor.GRAY));
    } else {
      TextComponent.Builder output = Component.text();
      result
          .lines()
          .forEach(
              line -> output.append(Component.text(line, NamedTextColor.GRAY)).appendNewline());
      source.sendMessage(output.build());
    }
    source.sendMessage(
        logPaginationFooter(
            instance.id(),
            page,
            lines,
            totalPages,
            result.totalRetainedLines(),
            result.retentionCapacity()));
  }

  void stats(CommandSource source, String[] arguments) {
    if (!requireAdmin(source, "stats", "view managed server statistics")) {
      return;
    }
    if (arguments.length > 2) {
      source.sendMessage(CommandMessages.usage("/sls stats", "server"));
      return;
    }
    ManagedInstance instance =
        instanceAccess.resolve(source, arguments.length == 2 ? arguments[1] : "this");
    if (instance == null) {
      return;
    }

    long uptime =
        Math.max(
            0L,
            java.time.Duration.between(
                    instance.processStartedAt().orElse(instance.createdAt()),
                    java.time.Instant.now())
                .toSeconds());
    String cpuTime =
        instance
            .processCpuTime()
            .map(duration -> formatDuration(duration.toSeconds()))
            .orElse("not measurable");
    var resources = instance.processResources();
    String residentMemory =
        resources
            .flatMap(
                snapshot ->
                    snapshot.residentBytes().isPresent()
                        ? Optional.of(formatBytes(snapshot.residentBytes().getAsLong()))
                        : Optional.empty())
            .orElse("not measurable");
    String processIo =
        resources
            .map(
                snapshot ->
                    "logical "
                        + optionalBytes(snapshot.charactersRead())
                        + " read / "
                        + optionalBytes(snapshot.charactersWritten())
                        + " written"
                        + "; storage "
                        + optionalBytes(snapshot.storageBytesRead())
                        + " read / "
                        + optionalBytes(snapshot.storageBytesWritten())
                        + " written")
            .orElse("not measurable");
    source.sendMessage(
        Component.text("Stats", NamedTextColor.DARK_AQUA)
            .append(Component.text(" (" + instance.id() + "):", NamedTextColor.DARK_GRAY))
            .appendNewline()
            .append(infoLine("Status:", CommandMessages.statusName(instance.state())))
            .appendNewline()
            .append(infoLine("CPU time:", cpuTime))
            .appendNewline()
            .append(
                infoLine(
                    "Mem:",
                    residentMemory
                        + " current / "
                        + instance.blueprint().memoryLimitMiB()
                        + " MiB configured"))
            .appendNewline()
            .append(infoLine("Process I/O:", processIo))
            .appendNewline()
            .append(infoLine("Uptime:", formatDuration(uptime)))
            .appendNewline()
            .append(
                infoLine(
                    "Logs:",
                    instance.retainedLogLines() + "/" + instance.logRetentionCapacity() + " lines"))
            .appendNewline()
            .append(
                Component.text(
                    "Per-process network is unavailable because managed "
                        + "children share the host/container network "
                        + "namespace. Current recursive disk use is "
                        + "intentionally excluded from this synchronous "
                        + "command; use the storage benchmark.",
                    NamedTextColor.DARK_GRAY)));
  }

  List<String> suggestions(CommandSource source, String operation, String[] arguments) {
    if (arguments.length == 2 && List.of("info", "logs", "stats", "status").contains(operation)) {
      if (!authorizer.canAdminister(source, operation)) {
        return List.of();
      }
      java.util.ArrayList<String> targets = new java.util.ArrayList<>();
      targets.add("this");
      targets.addAll(instanceAccess.activeIds());
      return List.copyOf(targets);
    }
    if (arguments.length == 3
        && "logs".equals(operation)
        && authorizer.canAdminister(source, "logs")) {
      return List.of("1");
    }
    if (arguments.length == 4
        && "logs".equals(operation)
        && authorizer.canAdminister(source, "logs")) {
      return List.of("50", "100", "max");
    }
    return List.of();
  }

  private void sendInstanceStatus(CommandSource source, String[] arguments, String permission) {
    if (!requireAdmin(source, permission, "inspect managed instances")) {
      return;
    }
    if (arguments.length != 2) {
      source.sendMessage(CommandMessages.usage("/sls " + permission, "server"));
      return;
    }
    ManagedInstance instance = instanceAccess.resolve(source, arguments[1]);
    if (instance == null) {
      return;
    }
    if ("status".equals(permission)) {
      source.sendMessage(
          CommandMessages.prefix()
              .append(Component.text("Status: ", NamedTextColor.DARK_AQUA))
              .append(
                  Component.text(
                      CommandMessages.statusName(instance.state()), NamedTextColor.GRAY)));
      return;
    }
    sendInstanceInfo(source, instance);
  }

  private void sendInstanceInfo(CommandSource source, ManagedInstance instance) {
    List<Player> players = instanceAccess.playersOn(instance);
    long uptimeSeconds =
        Math.max(
            0L,
            java.time.Duration.between(instance.createdAt(), java.time.Instant.now()).toSeconds());
    TextComponent.Builder message =
        Component.text()
            .append(Component.text("Info", NamedTextColor.DARK_AQUA))
            .append(Component.text(" (" + instance.id() + "):", NamedTextColor.DARK_GRAY))
            .appendNewline()
            .append(
                infoLine("Players:", Integer.toString(players.size()))
                    .hoverEvent(
                        Component.text(
                            players.isEmpty()
                                ? "No players"
                                : players.stream()
                                    .map(Player::getUsername)
                                    .sorted()
                                    .reduce((left, right) -> left + ", " + right)
                                    .orElse(""),
                            NamedTextColor.DARK_PURPLE)))
            .appendNewline()
            .append(Component.text(" - ", NamedTextColor.GOLD))
            .append(Component.text("Status:", NamedTextColor.DARK_GRAY))
            .append(
                Component.text(
                    " " + CommandMessages.statusName(instance.state()),
                    CommandMessages.statusColor(instance.state())))
            .appendNewline()
            .append(
                infoLine("Blueprint:", instance.blueprint().name())
                    .hoverEvent(
                        Component.text(instance.blueprint().id(), NamedTextColor.DARK_PURPLE)))
            .appendNewline()
            .append(infoLine("Type:", instance.blueprint().type()))
            .appendNewline()
            .append(
                infoLine(
                    "Server:",
                    instance.blueprint().software() + " " + instance.blueprint().version()))
            .appendNewline()
            .append(infoLine("Port:", Integer.toString(instance.port())))
            .appendNewline()
            .append(
                infoLine(
                    "Process:",
                    instance.processId().isPresent()
                        ? Long.toString(instance.processId().getAsLong())
                        : "not started"))
            .appendNewline()
            .append(infoLine("Mem:", instance.blueprint().memoryLimitMiB() + " MiB limit"))
            .appendNewline()
            .append(infoLine("Uptime:", formatDuration(uptimeSeconds)))
            .appendNewline()
            .append(infoLine("Queued:", joinService.hasPendingJoin(instance.id()) ? "yes" : "no"))
            .appendNewline()
            .append(
                infoLine(
                    "Logs:",
                    instance.retainedLogLines() + "/" + instance.logRetentionCapacity() + " lines"))
            .appendNewline()
            .append(
                infoLine(
                    "Log file:",
                    instance
                        .temporaryLogPath()
                        .map(java.nio.file.Path::toString)
                        .orElse("disabled")))
            .appendNewline()
            .append(infoLine("Directory:", instance.directory().toString()))
            .appendNewline()
            .append(
                Component.text("------------------------------------", NamedTextColor.DARK_GRAY)
                    .decorate(TextDecoration.STRIKETHROUGH)
                    .decorate(TextDecoration.BOLD));
    source.sendMessage(message.build());
  }

  private boolean requireAdmin(CommandSource source, String permission, String operation) {
    if (authorizer.canAdminister(source, permission)) {
      return true;
    }
    source.sendMessage(
        CommandMessages.message(
            "You do not have permission to " + operation + ".", NamedTextColor.RED));
    return false;
  }

  private static Component infoLine(String label, String value) {
    return Component.text(" - ", NamedTextColor.GOLD)
        .append(Component.text(label, NamedTextColor.DARK_GRAY))
        .append(Component.text(" " + value, NamedTextColor.BLUE));
  }

  private static String formatDuration(long seconds) {
    long hours = seconds / 3600;
    long minutes = (seconds % 3600) / 60;
    long remainingSeconds = seconds % 60;
    return hours + "h " + minutes + "m " + remainingSeconds + "s";
  }

  private static String optionalBytes(OptionalLong value) {
    return value.isPresent() ? formatBytes(value.getAsLong()) : "unavailable";
  }

  private static String formatBytes(long bytes) {
    long normalized = Math.max(0L, bytes);
    String[] units = {"B", "KiB", "MiB", "GiB", "TiB"};
    double value = normalized;
    int unit = 0;
    while (value >= 1024.0d && unit < units.length - 1) {
      value /= 1024.0d;
      unit++;
    }
    return unit == 0
        ? normalized + " " + units[unit]
        : String.format(java.util.Locale.ROOT, "%.1f %s", value, units[unit]);
  }

  private static Integer parsePositiveInt(String value) {
    try {
      int parsed = Integer.parseInt(value);
      return parsed > 0 ? parsed : null;
    } catch (NumberFormatException exception) {
      return null;
    }
  }

  private static Integer parseLogLines(String value) {
    return "max".equalsIgnoreCase(value) ? MAX_LOG_LINES : parsePositiveInt(value);
  }

  private static void invalidNumber(CommandSource source, String value) {
    source.sendMessage(
        CommandMessages.prefix()
            .append(Component.text("Invalid number " + value, NamedTextColor.RED)));
  }

  private static Component logPaginationFooter(
      String serverId,
      int page,
      int linesPerPage,
      int totalPages,
      int totalLines,
      int retentionCapacity) {
    Component previous =
        page > 1
            ? logPageArrow("<<", serverId, page - 1, linesPerPage, "View newer logs")
            : Component.text("<<", NamedTextColor.DARK_GRAY);
    Component next =
        page < totalPages
            ? logPageArrow(">>", serverId, page + 1, linesPerPage, "View older logs")
            : Component.text(">>", NamedTextColor.DARK_GRAY);
    Component pageLabel =
        Component.text("PAGE " + page + "/" + totalPages, NamedTextColor.GOLD)
            .hoverEvent(
                Component.text(
                    "Retained lines: "
                        + totalLines
                        + "/"
                        + retentionCapacity
                        + "\nLines per page: "
                        + linesPerPage,
                    NamedTextColor.GRAY));
    return previous
        .append(
            Component.text(" -------------- ", NamedTextColor.DARK_GRAY)
                .decorate(TextDecoration.STRIKETHROUGH, TextDecoration.BOLD))
        .append(pageLabel)
        .append(
            Component.text(" -------------- ", NamedTextColor.DARK_GRAY)
                .decorate(TextDecoration.STRIKETHROUGH, TextDecoration.BOLD))
        .append(next);
  }

  private static Component logPageArrow(
      String arrow, String serverId, int page, int linesPerPage, String hover) {
    return Component.text(arrow, NamedTextColor.AQUA)
        .clickEvent(
            ClickEvent.runCommand("/sls logs " + serverId + " " + page + " " + linesPerPage))
        .hoverEvent(Component.text(hover, NamedTextColor.GRAY));
  }
}
