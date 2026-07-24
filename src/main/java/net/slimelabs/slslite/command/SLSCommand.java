package net.slimelabs.slslite.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.slimelabs.slslite.BuildInfo;
import net.slimelabs.slslite.blueprint.Blueprint;
import net.slimelabs.slslite.blueprint.BlueprintRepository;
import net.slimelabs.slslite.instance.InstanceOperationException;
import net.slimelabs.slslite.instance.ManagedInstance;
import net.slimelabs.slslite.instance.ServerController;
import net.slimelabs.slslite.resource.ResourceBudget;
import net.slimelabs.slslite.software.SoftwareProfileRepository;
import net.slimelabs.slslite.velocity.LocalJoinService;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class SLSCommand implements SimpleCommand {

    private static final List<String> PUBLIC_COMMANDS =
            List.of("find", "info", "join", "list", "registries", "version");
    private static final List<String> ADMIN_COMMANDS =
            List.of("blueprints", "reload", "start", "status", "stop");

    private final ProxyServer proxy;
    private final BlueprintRepository blueprints;
    private final SoftwareProfileRepository softwareProfiles;
    private final ResourceBudget resourceBudget;
    private final ServerController instances;
    private final LocalJoinService joinService;
    private final Logger logger;

    public SLSCommand(
            ProxyServer proxy,
            BlueprintRepository blueprints,
            SoftwareProfileRepository softwareProfiles,
            ResourceBudget resourceBudget,
            ServerController instances,
            LocalJoinService joinService,
            Logger logger
    ) {
        this.proxy = proxy;
        this.blueprints = blueprints;
        this.softwareProfiles = softwareProfiles;
        this.resourceBudget = resourceBudget;
        this.instances = instances;
        this.joinService = joinService;
        this.logger = logger;
    }

    @Override
    public void execute(Invocation invocation) {
        String[] arguments = invocation.arguments();
        if (arguments.length == 0) {
            sendSummary(invocation.source());
            return;
        }

        switch (arguments[0].toLowerCase(Locale.ROOT)) {
            case "blueprints" -> sendBlueprints(invocation.source(), arguments);
            case "find" -> find(invocation.source(), arguments);
            case "info" -> info(invocation.source(), arguments);
            case "join" -> join(invocation.source(), arguments);
            case "list" -> sendInstances(invocation.source());
            case "registries" -> sendRegistries(invocation.source());
            case "reload" -> reload(invocation.source(), arguments);
            case "start" -> start(invocation.source(), arguments);
            case "status" -> status(invocation.source(), arguments);
            case "stop" -> stop(invocation.source(), arguments);
            case "version" -> sendVersion(invocation.source());
            default -> invocation.source().sendMessage(
                    Component.text("Unknown SLS-LITE subcommand.")
                            .color(NamedTextColor.RED)
            );
        }
    }

    @Override
    public CompletableFuture<List<String>> suggestAsync(Invocation invocation) {
        String[] arguments = invocation.arguments();
        CommandSource source = invocation.source();
        if (arguments.length <= 1) {
            List<String> suggestions = new java.util.ArrayList<>(PUBLIC_COMMANDS);
            ADMIN_COMMANDS.stream()
                    .filter(command -> CommandPermissions.canAdminister(source, command))
                    .forEach(suggestions::add);
            return completed(suggestions);
        }

        String operation = arguments[0].toLowerCase(Locale.ROOT);
        if (arguments.length == 2) {
            return switch (operation) {
                case "blueprints" -> CommandPermissions.canAdminister(source, "blueprints")
                        ? completed(sorted(blueprints.getTypes()))
                        : completed(List.of());
                case "find" -> completed(proxy.getAllPlayers().stream()
                        .map(Player::getUsername).sorted().toList());
                case "join" -> completed(sorted(blueprints.getTypes()));
                case "reload" -> CommandPermissions.canAdminister(source, "reload")
                        ? completed(List.of("all", "blueprints", "software"))
                        : completed(List.of());
                case "start" -> CommandPermissions.canAdminister(source, "start")
                        ? completed(sorted(blueprints.getTypes()))
                        : completed(List.of());
                case "status", "stop", "info" ->
                        CommandPermissions.canAdminister(source, operation)
                                ? completed(instanceIds())
                                : completed(List.of());
                default -> completed(List.of());
            };
        }
        if (arguments.length == 3 && "join".equals(operation)) {
            return completed(blueprints.getByType(arguments[1]).stream()
                    .map(Blueprint::id).toList());
        }
        if (arguments.length == 3 && "start".equals(operation)
                && CommandPermissions.canAdminister(source, "start")) {
            return completed(blueprints.getByType(arguments[1]).stream()
                    .map(Blueprint::id).toList());
        }
        if (arguments.length == 4 && "join".equals(operation)
                && CommandPermissions.canTargetOthers(source, "join")) {
            return completed(proxy.getAllPlayers().stream()
                    .map(Player::getUsername).sorted().toList());
        }
        return completed(List.of());
    }

    private void sendSummary(CommandSource source) {
        source.sendMessage(Component.text("SLS-LITE")
                .color(NamedTextColor.GREEN)
                .append(Component.text(" - standalone local server management for Velocity")
                        .color(NamedTextColor.GRAY)));
        source.sendMessage(Component.text(
                "Registries: " + blueprints.getTypes().size()
                        + " | Blueprints: " + blueprints.getAll().size()
                        + " | Software profiles: " + softwareProfiles.getAll().size()
        ).color(NamedTextColor.GRAY));
        source.sendMessage(Component.text(
                "Active instances: " + instances.getAll().size()
                        + " | Managed memory: " + resourceBudget.reservedMemoryMiB()
                        + "/" + resourceBudget.totalMemoryMiB() + " MiB"
        ).color(NamedTextColor.GRAY));
    }

    private void info(CommandSource source, String[] arguments) {
        if (arguments.length == 1) {
            sendSummary(source);
            return;
        }
        if (!requireAdmin(source, "info", "inspect managed instances")) {
            return;
        }
        sendInstanceStatus(source, arguments, "info");
    }

    private void sendRegistries(CommandSource source) {
        if (blueprints.getTypes().isEmpty()) {
            source.sendMessage(Component.text("No registries are loaded.")
                    .color(NamedTextColor.YELLOW));
            return;
        }
        source.sendMessage(Component.text("Registries").color(NamedTextColor.GREEN));
        sorted(blueprints.getTypes()).forEach(type ->
                source.sendMessage(Component.text("- " + type).color(NamedTextColor.GOLD)
                        .append(Component.text(
                                " (" + blueprints.getByType(type).size() + " server(s))"
                        ).color(NamedTextColor.GRAY))));
    }

    private void sendBlueprints(CommandSource source, String[] arguments) {
        if (!requireAdmin(source, "blueprints", "inspect blueprints")) {
            return;
        }
        Collection<Blueprint> selected;
        if (arguments.length == 1) {
            selected = blueprints.getAll();
        } else if (arguments.length == 2) {
            selected = blueprints.getByType(arguments[1]);
        } else {
            source.sendMessage(Component.text("Usage: /sls blueprints [registry]")
                    .color(NamedTextColor.RED));
            return;
        }
        if (selected.isEmpty()) {
            source.sendMessage(Component.text("No matching blueprints are loaded.")
                    .color(NamedTextColor.YELLOW));
            return;
        }
        source.sendMessage(Component.text("Blueprints").color(NamedTextColor.GREEN));
        selected.forEach(blueprint ->
                source.sendMessage(Component.text(
                        "- " + blueprint.type() + " " + blueprint.id()
                ).color(NamedTextColor.GOLD).append(Component.text(
                        " (" + blueprint.software() + " " + blueprint.version()
                                + ", " + blueprint.memoryLimitMiB() + " MiB)"
                ).color(NamedTextColor.GRAY))));
    }

    private void sendInstances(CommandSource source) {
        if (instances.getAll().isEmpty()) {
            source.sendMessage(Component.text("No managed instances are active.")
                    .color(NamedTextColor.YELLOW));
            return;
        }
        source.sendMessage(Component.text("Managed instances").color(NamedTextColor.GREEN));
        instances.getAll().forEach(instance ->
                source.sendMessage(Component.text("- " + instance.id()).color(NamedTextColor.GOLD)
                        .append(Component.text(
                                " (" + instance.blueprint().type() + ", " + instance.state()
                                        + ", port " + instance.port() + ")"
                        ).color(NamedTextColor.GRAY))));
    }

    private void start(CommandSource source, String[] arguments) {
        if (!requireAdmin(source, "start", "start managed servers")) {
            return;
        }
        Optional<Blueprint> blueprint = resolveBlueprint(arguments);
        if (blueprint.isEmpty()) {
            source.sendMessage(Component.text("Usage: /sls start <registry> <server>")
                    .color(NamedTextColor.RED));
            return;
        }

        try {
            ManagedInstance instance = instances.start(blueprint.get().id());
            source.sendMessage(Component.text(
                    "Preparing " + instance.id() + " from "
                            + blueprint.get().type() + "/" + blueprint.get().id() + "..."
            ).color(NamedTextColor.YELLOW));
            instance.readyFuture().whenComplete((ready, failure) -> {
                if (failure == null) {
                    source.sendMessage(Component.text(
                            "Instance " + ready.id() + " is ready on port " + ready.port() + "."
                    ).color(NamedTextColor.GREEN));
                } else {
                    source.sendMessage(Component.text(
                            "Instance " + instance.id() + " failed: " + rootMessage(failure)
                    ).color(NamedTextColor.RED));
                }
            });
        } catch (InstanceOperationException exception) {
            source.sendMessage(Component.text(exception.getMessage()).color(NamedTextColor.RED));
        }
    }

    private void join(CommandSource source, String[] arguments) {
        if (arguments.length < 3 || arguments.length > 4) {
            source.sendMessage(Component.text("Usage: /sls join <registry> <server> [player]")
                    .color(NamedTextColor.RED));
            return;
        }

        Player target;
        if (arguments.length == 4) {
            if (!CommandPermissions.canTargetOthers(source, "join")) {
                permissionDenied(source, "join other players");
                return;
            }
            target = proxy.getPlayer(arguments[3]).orElse(null);
            if (target == null) {
                source.sendMessage(Component.text("Player not found: " + arguments[3])
                        .color(NamedTextColor.RED));
                return;
            }
        } else if (source instanceof Player player) {
            target = player;
        } else {
            source.sendMessage(Component.text(
                    "Console must specify a player: /sls join <registry> <server> <player>"
            ).color(NamedTextColor.RED));
            return;
        }

        try {
            LocalJoinService.JoinAttempt attempt =
                    joinService.join(target, arguments[1], arguments[2]);
            ManagedInstance instance = attempt.instance();
            String action = attempt.created() ? "Preparing" : "Joining";
            source.sendMessage(Component.text(
                    action + " " + instance.id() + " for " + target.getUsername() + "..."
            ).color(attempt.created() ? NamedTextColor.YELLOW : NamedTextColor.GREEN));
            if (source != target) {
                target.sendMessage(Component.text(
                        "SLS-LITE is connecting you to " + arguments[1] + "/" + arguments[2] + "."
                ).color(NamedTextColor.YELLOW));
            }
            attempt.connection().whenComplete((result, failure) ->
                    reportConnection(source, target, instance, result, failure));
        } catch (InstanceOperationException exception) {
            source.sendMessage(Component.text(exception.getMessage()).color(NamedTextColor.RED));
        }
    }

    private void reportConnection(
            CommandSource source,
            Player target,
            ManagedInstance instance,
            ConnectionRequestBuilder.Result result,
            Throwable failure
    ) {
        if (failure != null) {
            source.sendMessage(Component.text(
                    "Unable to connect " + target.getUsername() + ": " + rootMessage(failure)
            ).color(NamedTextColor.RED));
            return;
        }
        if (result.isSuccessful()
                || result.getStatus() == ConnectionRequestBuilder.Status.ALREADY_CONNECTED) {
            source.sendMessage(Component.text(
                    "Connected " + target.getUsername() + " to " + instance.id() + "."
            ).color(NamedTextColor.GREEN));
            return;
        }
        source.sendMessage(Component.text(
                "Connection to " + instance.id() + " failed: " + result.getStatus()
        ).color(NamedTextColor.RED));
    }

    private void find(CommandSource source, String[] arguments) {
        if (arguments.length != 2) {
            source.sendMessage(Component.text("Usage: /sls find <player>")
                    .color(NamedTextColor.RED));
            return;
        }
        Player player = proxy.getPlayer(arguments[1]).orElse(null);
        if (player == null) {
            source.sendMessage(Component.text("Player not found: " + arguments[1])
                    .color(NamedTextColor.RED));
            return;
        }
        String current = player.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName())
                .orElse(null);
        boolean managed = current != null && instances.getAll().stream()
                .anyMatch(instance -> instance.id().equals(current));
        if (!managed) {
            source.sendMessage(Component.text(
                    player.getUsername() + " is not connected to a managed SLS-LITE instance."
            ).color(NamedTextColor.YELLOW));
            return;
        }
        source.sendMessage(Component.text(
                player.getUsername() + " is on " + current + "."
        ).color(NamedTextColor.GREEN));
    }

    private void stop(CommandSource source, String[] arguments) {
        if (!requireAdmin(source, "stop", "stop managed servers")) {
            return;
        }
        if (arguments.length != 2) {
            source.sendMessage(Component.text("Usage: /sls stop <instance>")
                    .color(NamedTextColor.RED));
            return;
        }
        try {
            source.sendMessage(Component.text("Stopping " + arguments[1] + "...")
                    .color(NamedTextColor.YELLOW));
            instances.stop(arguments[1]).whenComplete((exitCode, failure) -> {
                if (failure == null) {
                    source.sendMessage(Component.text(
                            "Stopped " + arguments[1] + " with exit code " + exitCode + "."
                    ).color(NamedTextColor.GREEN));
                } else {
                    source.sendMessage(Component.text(
                            "Stop failed: " + rootMessage(failure)
                    ).color(NamedTextColor.RED));
                }
            });
        } catch (InstanceOperationException exception) {
            source.sendMessage(Component.text(exception.getMessage()).color(NamedTextColor.RED));
        }
    }

    private void status(CommandSource source, String[] arguments) {
        sendInstanceStatus(source, arguments, "status");
    }

    private void sendInstanceStatus(
            CommandSource source,
            String[] arguments,
            String permission
    ) {
        if (!requireAdmin(source, permission, "inspect managed instances")) {
            return;
        }
        if (arguments.length != 2) {
            source.sendMessage(Component.text("Usage: /sls " + permission + " <instance>")
                    .color(NamedTextColor.RED));
            return;
        }
        try {
            ManagedInstance instance = instances.get(arguments[1]);
            source.sendMessage(Component.text(instance.id()).color(NamedTextColor.GREEN));
            source.sendMessage(Component.text(
                    "Registry: " + instance.blueprint().type()
                            + " | Blueprint: " + instance.blueprint().id()
                            + " | State: " + instance.state()
            ).color(NamedTextColor.GRAY));
            source.sendMessage(Component.text(
                    "Port: " + instance.port() + " | Memory: "
                            + instance.blueprint().memoryLimitMiB() + " MiB"
            ).color(NamedTextColor.GRAY));
            source.sendMessage(Component.text("Directory: " + instance.directory())
                    .color(NamedTextColor.DARK_GRAY));
        } catch (InstanceOperationException exception) {
            source.sendMessage(Component.text(exception.getMessage()).color(NamedTextColor.RED));
        }
    }

    private void reload(CommandSource source, String[] arguments) {
        if (!requireAdmin(source, "reload", "reload SLS-LITE")) {
            return;
        }
        String mode = arguments.length == 1 ? "all" : arguments[1].toLowerCase(Locale.ROOT);
        if (arguments.length > 2 || !List.of("all", "blueprints", "software").contains(mode)) {
            source.sendMessage(Component.text("Usage: /sls reload [all|blueprints|software]")
                    .color(NamedTextColor.RED));
            return;
        }
        try {
            if ("all".equals(mode) || "software".equals(mode)) {
                softwareProfiles.reload();
            }
            if ("all".equals(mode) || "blueprints".equals(mode)) {
                blueprints.reload();
            }
            source.sendMessage(Component.text(
                    "Reloaded " + mode + ": " + blueprints.getTypes().size()
                            + " registries, " + blueprints.getAll().size()
                            + " blueprints, " + softwareProfiles.getAll().size()
                            + " software profiles."
            ).color(NamedTextColor.GREEN));
        } catch (Exception exception) {
            logger.error("Unable to reload SLS-LITE " + mode, exception);
            source.sendMessage(Component.text(
                    "Reload failed: " + rootMessage(exception)
            ).color(NamedTextColor.RED));
        }
    }

    private void sendVersion(CommandSource source) {
        source.sendMessage(Component.text("SLS-LITE " + BuildInfo.VERSION)
                .color(NamedTextColor.GREEN));
    }

    private Optional<Blueprint> resolveBlueprint(String[] arguments) {
        if (arguments.length == 3) {
            return blueprints.get(arguments[1], arguments[2]);
        }
        if (arguments.length == 2) {
            return blueprints.get(arguments[1]);
        }
        return Optional.empty();
    }

    private boolean requireAdmin(CommandSource source, String permission, String operation) {
        if (CommandPermissions.canAdminister(source, permission)) {
            return true;
        }
        permissionDenied(source, operation);
        return false;
    }

    private void permissionDenied(CommandSource source, String operation) {
        source.sendMessage(Component.text(
                "You do not have permission to " + operation + "."
        ).color(NamedTextColor.RED));
    }

    private List<String> instanceIds() {
        return instances.getAll().stream().map(ManagedInstance::id).sorted().toList();
    }

    private static List<String> sorted(Collection<String> values) {
        return values.stream().sorted(Comparator.naturalOrder()).toList();
    }

    private static CompletableFuture<List<String>> completed(List<String> values) {
        return CompletableFuture.completedFuture(values);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null
                ? current.getClass().getSimpleName()
                : current.getMessage();
    }
}
