package net.slimelabs.slslite.command;

import com.velocitypowered.api.proxy.Player;
import java.util.Collection;
import java.util.Iterator;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.slimelabs.slslite.blueprint.Blueprint;
import net.slimelabs.slslite.blueprint.BlueprintCopy;
import net.slimelabs.slslite.blueprint.BlueprintVolume;
import net.slimelabs.slslite.instance.ManagedInstance;
import net.slimelabs.slslite.instance.model.InstanceState;

public final class CommandMessages {

  private static final TextColor SLS_BLUE = TextColor.color(46, 112, 255);
  private static final TextColor SLS_LIGHT_BLUE = TextColor.color(71, 151, 255);
  private static final TextColor ERROR_RED = TextColor.color(237, 67, 55);

  private CommandMessages() {}

  public static Component prefix() {
    return Component.text("[", NamedTextColor.DARK_GRAY)
        .append(Component.text("S", SLS_BLUE))
        .append(Component.text("L", TextColor.color(58, 132, 255)))
        .append(Component.text("S", SLS_LIGHT_BLUE))
        .append(Component.text("] ", NamedTextColor.DARK_GRAY));
  }

  public static Component message(String text, TextColor color) {
    return prefix().append(Component.text(text, color));
  }

  public static Component incorrectUsage() {
    return prefix().append(Component.text(" Incorrect Command Usage!", ERROR_RED));
  }

  public static Component usage(String command, String... arguments) {
    TextComponent.Builder message =
        Component.text()
            .append(prefix())
            .append(Component.text("Usage: ", NamedTextColor.DARK_AQUA))
            .append(Component.text(command, NamedTextColor.GRAY));
    if (arguments.length == 0) {
      return message.build();
    }
    message.append(Component.text(" <", NamedTextColor.DARK_GRAY));
    for (int index = 0; index < arguments.length; index++) {
      if (index > 0) {
        message.append(Component.text(" | ", NamedTextColor.DARK_GRAY));
      }
      message.append(Component.text(arguments[index], NamedTextColor.GRAY));
    }
    return message.append(Component.text(">", NamedTextColor.DARK_GRAY)).build();
  }

  public static Component player(Player player) {
    String currentServer =
        player
            .getCurrentServer()
            .map(connection -> connection.getServerInfo().getName())
            .orElse("none");
    Component tooltip =
        labelValue("UUID:", player.getUniqueId().toString())
            .appendNewline()
            .append(labelValue("Current server:", currentServer));
    return Component.text(player.getUsername(), NamedTextColor.DARK_AQUA)
        .hoverEvent(HoverEvent.showText(tooltip));
  }

  public static Component server(ManagedInstance instance, int playerCount) {
    String id = instance.id();
    int separator = id.lastIndexOf('.');
    String prefix = separator < 0 ? id : id.substring(0, separator);
    String suffix = separator < 0 ? "" : id.substring(separator);
    return Component.text(prefix, NamedTextColor.GOLD)
        .append(Component.text(suffix, NamedTextColor.YELLOW))
        .hoverEvent(HoverEvent.showText(instanceDetails(instance, playerCount)));
  }

  public static Component fullServerJoinConfirmation(
      Player joiningPlayer,
      Player targetPlayer,
      ManagedInstance instance,
      int currentPlayers,
      int maxPlayers) {
    String forceCommand = "/sls join player " + targetPlayer.getUsername() + " --force";
    Component confirmation =
        Component.text("[Join Anyway]", NamedTextColor.GREEN)
            .clickEvent(ClickEvent.runCommand(forceCommand))
            .hoverEvent(
                HoverEvent.showText(
                    Component.text("Run ", NamedTextColor.GRAY)
                        .append(Component.text(forceCommand, NamedTextColor.GREEN))));
    return prefix()
        .append(Component.text("Blueprint capacity reached: ", NamedTextColor.YELLOW))
        .append(server(instance, currentPlayers))
        .append(
            Component.text(
                " is full (" + currentPlayers + "/" + maxPlayers + ").\n", NamedTextColor.GRAY))
        .append(Component.text("Joining ", NamedTextColor.DARK_GRAY))
        .append(player(joiningPlayer))
        .append(Component.text(" with ", NamedTextColor.DARK_GRAY))
        .append(player(targetPlayer))
        .append(
            Component.text(
                " will exceed this blueprint's matchmaking limit. ", NamedTextColor.DARK_GRAY))
        .append(confirmation);
  }

  public static Component listEntry(ManagedInstance instance, Collection<Player> players) {
    Component playerNames =
        players.isEmpty()
            ? Component.text("No players", NamedTextColor.DARK_PURPLE)
            : joinedPlayerNames(players);
    int count = players.size();
    Component displayName =
        Component.text(instance.blueprint().name(), statusColor(instance.state()))
            .hoverEvent(HoverEvent.showText(instanceDetails(instance, count)));
    Component playerCount =
        Component.text(count + (count == 1 ? " player" : " players"), NamedTextColor.DARK_AQUA)
            .hoverEvent(HoverEvent.showText(playerNames));
    return Component.text(" - ", NamedTextColor.GOLD)
        .append(displayName)
        .append(Component.text(": ", NamedTextColor.WHITE))
        .append(playerCount);
  }

  public static Component blueprint(
      Blueprint blueprint, Collection<ManagedInstance> activeInstances) {
    Component details =
        blueprintDetails(blueprint, activeInstances)
            .appendNewline()
            .append(
                Component.text("Click to prepare or join this blueprint", NamedTextColor.YELLOW));
    Component interactiveName =
        Component.text(blueprint.type() + " " + blueprint.id(), NamedTextColor.GOLD)
            .hoverEvent(HoverEvent.showText(details))
            .clickEvent(
                ClickEvent.suggestCommand("/sls join " + blueprint.type() + " " + blueprint.id()));
    Component summary =
        Component.text(
            " ("
                + blueprint.software()
                + " "
                + blueprint.version()
                + ", "
                + blueprint.memoryLimitMiB()
                + " MiB)",
            NamedTextColor.GRAY);
    return Component.text().append(interactiveName).append(summary).build();
  }

  public static Component blueprintDetails(
      Blueprint blueprint, Collection<ManagedInstance> activeInstances) {
    List<ManagedInstance> active =
        activeInstances.stream()
            .filter(instance -> instance.blueprint().id().equals(blueprint.id()))
            .toList();
    TextComponent.Builder tooltip =
        Component.text()
            .append(labelValue("Name:", blueprint.name()))
            .appendNewline()
            .append(labelValue("Blueprint:", blueprint.type() + "/" + blueprint.id()))
            .appendNewline()
            .append(labelValue("Software:", blueprint.software() + " " + blueprint.version()))
            .appendNewline()
            .append(labelValue("Memory:", blueprint.memoryLimitMiB() + " MiB"))
            .appendNewline()
            .append(labelValue("Capacity:", blueprint.maxPlayers() + " players per instance"))
            .appendNewline()
            .append(labelValue("Instance limit:", Integer.toString(blueprint.maxInstances())))
            .appendNewline()
            .append(labelValue("Persistence:", blueprint.save() ? "persistent" : "ephemeral"))
            .appendNewline()
            .append(
                labelValue(
                    "Active:",
                    active.isEmpty()
                        ? "none"
                        : active.stream()
                            .map(
                                instance ->
                                    instance.id() + " [" + statusName(instance.state()) + "]")
                            .reduce((left, right) -> left + ", " + right)
                            .orElse("none")));
    if (blueprint.volumes().isEmpty()) {
      tooltip.appendNewline().append(labelValue("Volumes:", "none"));
    } else {
      tooltip
          .appendNewline()
          .append(labelValue("Volumes:", Integer.toString(blueprint.volumes().size())));
      for (BlueprintVolume volume : blueprint.volumes()) {
        tooltip
            .appendNewline()
            .append(
                Component.text(
                    "  "
                        + volume.name()
                        + ": "
                        + volume.source()
                        + " -> "
                        + volume.target()
                        + " ["
                        + volume.mode().name().toLowerCase()
                        + "]",
                    NamedTextColor.DARK_PURPLE));
      }
    }
    tooltip
        .appendNewline()
        .append(
            labelValue("Persistent files:", Integer.toString(blueprint.persistentFiles().size())));
    if (blueprint.copies().isEmpty()) {
      tooltip.appendNewline().append(labelValue("Copies:", "none"));
    } else {
      tooltip
          .appendNewline()
          .append(labelValue("Copies:", Integer.toString(blueprint.copies().size())));
      for (BlueprintCopy copy : blueprint.copies().stream().limit(8).toList()) {
        tooltip
            .appendNewline()
            .append(
                Component.text(
                    "  " + copy.source() + " -> " + copy.target(), NamedTextColor.DARK_PURPLE));
      }
      if (blueprint.copies().size() > 8) {
        tooltip
            .appendNewline()
            .append(
                Component.text(
                    "  +" + (blueprint.copies().size() - 8) + " more", NamedTextColor.DARK_PURPLE));
      }
    }
    if (blueprint.environment().isEmpty()) {
      tooltip.appendNewline().append(labelValue("Environment:", "none"));
    } else {
      List<String> names = blueprint.environment().keySet().stream().sorted().limit(8).toList();
      String summary = String.join(", ", names);
      if (blueprint.environment().size() > names.size()) {
        summary += " +" + (blueprint.environment().size() - names.size());
      }
      tooltip.appendNewline().append(labelValue("Environment:", summary));
    }
    return tooltip.build();
  }

  public static Component listHeader() {
    return Component.text("----", NamedTextColor.DARK_GRAY)
        .decorate(TextDecoration.STRIKETHROUGH)
        .append(
            Component.text(" SERVER LIST ", NamedTextColor.DARK_GRAY)
                .decoration(TextDecoration.STRIKETHROUGH, false))
        .append(
            Component.text("----", NamedTextColor.DARK_GRAY)
                .decorate(TextDecoration.STRIKETHROUGH));
  }

  public static Component listFooter() {
    return Component.text("----------------", NamedTextColor.DARK_GRAY)
        .decorate(TextDecoration.STRIKETHROUGH)
        .decorate(TextDecoration.BOLD);
  }

  public static TextColor statusColor(InstanceState state) {
    return switch (state) {
      case READY -> NamedTextColor.GREEN;
      case STOPPING, STOPPED, FAILED -> NamedTextColor.RED;
      default -> NamedTextColor.YELLOW;
    };
  }

  public static String statusName(InstanceState state) {
    return switch (state) {
      case READY -> "Running";
      case STOPPED -> "Offline";
      default -> titleCase(state.name());
    };
  }

  public static Component labelValue(String label, String value) {
    return Component.text(label, NamedTextColor.DARK_GRAY)
        .append(Component.text(" " + value, NamedTextColor.GRAY));
  }

  private static Component instanceDetails(ManagedInstance instance, int playerCount) {
    Blueprint blueprint = instance.blueprint();
    return labelValue("Instance:", instance.id())
        .appendNewline()
        .append(labelValue("Name:", blueprint.name()))
        .appendNewline()
        .append(labelValue("Blueprint:", blueprint.type() + "/" + blueprint.id()))
        .appendNewline()
        .append(labelValue("Status:", statusName(instance.state())))
        .appendNewline()
        .append(labelValue("Players:", playerCount + "/" + blueprint.maxPlayers()))
        .appendNewline()
        .append(labelValue("Software:", blueprint.software() + " " + blueprint.version()))
        .appendNewline()
        .append(labelValue("Memory:", blueprint.memoryLimitMiB() + " MiB"))
        .appendNewline()
        .append(labelValue("Port:", Integer.toString(instance.port())))
        .appendNewline()
        .append(labelValue("Persistence:", blueprint.save() ? "persistent" : "ephemeral"));
  }

  private static Component joinedPlayerNames(Collection<Player> players) {
    TextComponent.Builder names = Component.text();
    Iterator<Player> iterator = players.iterator();
    while (iterator.hasNext()) {
      names.append(Component.text(iterator.next().getUsername(), NamedTextColor.DARK_PURPLE));
      if (iterator.hasNext()) {
        names.append(Component.text(", ", NamedTextColor.DARK_GRAY));
      }
    }
    return names.build();
  }

  private static String titleCase(String value) {
    String lower = value.toLowerCase(java.util.Locale.ROOT);
    return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
  }
}
