package net.slimelabs.slslite.command;

import com.velocitypowered.api.proxy.Player;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.slimelabs.slslite.BuildInfo;
import net.slimelabs.slslite.instance.InstanceState;
import net.slimelabs.slslite.instance.ManagedInstance;

import java.util.Collection;
import java.util.Iterator;

final class CommandMessages {

    private static final TextColor SLS_BLUE = TextColor.color(46, 112, 255);
    private static final TextColor SLS_LIGHT_BLUE = TextColor.color(71, 151, 255);
    private static final TextColor ERROR_RED = TextColor.color(237, 67, 55);

    private CommandMessages() {
    }

    static Component prefix() {
        Component tooltip = Component.text("Server Launch System", NamedTextColor.RED)
                .appendNewline()
                .append(Component.text("By " + BuildInfo.AUTHORS, SLS_LIGHT_BLUE));
        return Component.text("[", NamedTextColor.DARK_GRAY)
                .append(Component.text("S", SLS_BLUE))
                .append(Component.text("L", TextColor.color(58, 132, 255)))
                .append(Component.text("S", SLS_LIGHT_BLUE))
                .append(Component.text("] ", NamedTextColor.DARK_GRAY))
                .hoverEvent(HoverEvent.showText(tooltip));
    }

    static Component message(String text, TextColor color) {
        return prefix().append(Component.text(text, color));
    }

    static Component incorrectUsage() {
        return prefix().append(Component.text(" Incorrect Command Usage!", ERROR_RED));
    }

    static Component usage(String command, String... arguments) {
        TextComponent.Builder message = Component.text()
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

    static Component player(Player player) {
        String currentServer = player.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName())
                .orElse("none");
        Component tooltip = labelValue("UUID:", player.getUniqueId().toString())
                .appendNewline()
                .append(labelValue("Current server:", currentServer));
        return Component.text(player.getUsername(), NamedTextColor.DARK_AQUA)
                .hoverEvent(HoverEvent.showText(tooltip));
    }

    static Component server(ManagedInstance instance, int playerCount) {
        String id = instance.id();
        int separator = id.lastIndexOf('.');
        String prefix = separator < 0 ? id : id.substring(0, separator);
        String suffix = separator < 0 ? "" : id.substring(separator);
        Component tooltip = labelValue("Name:", instance.blueprint().name())
                .appendNewline()
                .append(labelValue("Blueprint:", instance.blueprint().id()))
                .appendNewline()
                .append(labelValue("Status:", statusName(instance.state())))
                .appendNewline()
                .append(labelValue("Players:", Integer.toString(playerCount)));
        return Component.text(prefix, NamedTextColor.GOLD)
                .append(Component.text(suffix, NamedTextColor.YELLOW))
                .hoverEvent(HoverEvent.showText(tooltip));
    }

    static Component listEntry(
            ManagedInstance instance,
            Collection<Player> players
    ) {
        Component playerNames = players.isEmpty()
                ? Component.text("No players", NamedTextColor.DARK_PURPLE)
                : joinedPlayerNames(players);
        int count = players.size();
        Component displayName = Component.text(
                instance.blueprint().name(),
                statusColor(instance.state())
        ).hoverEvent(HoverEvent.showText(
                Component.text(instance.id(), statusColor(instance.state()))
        ));
        Component playerCount = Component.text(
                count + (count == 1 ? " player" : " players"),
                NamedTextColor.DARK_AQUA
        ).hoverEvent(HoverEvent.showText(playerNames));
        return Component.text(" - ", NamedTextColor.GOLD)
                .append(displayName)
                .append(Component.text(": ", NamedTextColor.WHITE))
                .append(playerCount);
    }

    static Component listHeader() {
        return Component.text("----", NamedTextColor.DARK_GRAY)
                .decorate(TextDecoration.STRIKETHROUGH)
                .append(Component.text(" SERVER LIST ", NamedTextColor.DARK_GRAY)
                        .decoration(TextDecoration.STRIKETHROUGH, false))
                .append(Component.text("----", NamedTextColor.DARK_GRAY)
                        .decorate(TextDecoration.STRIKETHROUGH));
    }

    static Component listFooter() {
        return Component.text("----------------", NamedTextColor.DARK_GRAY)
                .decorate(TextDecoration.STRIKETHROUGH)
                .decorate(TextDecoration.BOLD);
    }

    static TextColor statusColor(InstanceState state) {
        return switch (state) {
            case READY -> NamedTextColor.GREEN;
            case STOPPING, STOPPED, FAILED -> NamedTextColor.RED;
            default -> NamedTextColor.YELLOW;
        };
    }

    static String statusName(InstanceState state) {
        return switch (state) {
            case READY -> "Running";
            case STOPPED -> "Offline";
            default -> titleCase(state.name());
        };
    }

    static Component labelValue(String label, String value) {
        return Component.text(label, NamedTextColor.DARK_GRAY)
                .append(Component.text(" " + value, NamedTextColor.GRAY));
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
