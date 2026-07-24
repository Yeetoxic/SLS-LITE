package net.slimelabs.slslite.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.slimelabs.slslite.BuildInfo;
import net.slimelabs.slslite.blueprint.Blueprint;
import net.slimelabs.slslite.blueprint.BlueprintRepository;
import net.slimelabs.slslite.instance.InstanceOperationException;
import net.slimelabs.slslite.instance.ManagedInstance;
import net.slimelabs.slslite.instance.ServerController;
import net.slimelabs.slslite.lobby.LobbyProvider;
import net.slimelabs.slslite.resource.ResourceBudget;
import net.slimelabs.slslite.software.SoftwareProfileRepository;
import net.slimelabs.slslite.velocity.LocalJoinService;
import org.slf4j.Logger;

import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;

public final class SLSCommand implements SimpleCommand {

    private static final List<String> PUBLIC_COMMANDS =
            List.of("dequeue", "find", "info", "join", "list", "registries", "version");
    private static final List<String> ADMIN_COMMANDS =
            List.of(
                    "blueprint", "blueprints", "console", "create", "debug", "delete",
                    "install", "kill", "logs", "node", "pause", "reload", "reset",
                    "restart", "resume", "start", "stats", "status", "stop", "system"
            );

    private final ProxyServer proxy;
    private final BlueprintRepository blueprints;
    private final SoftwareProfileRepository softwareProfiles;
    private final ResourceBudget resourceBudget;
    private final ServerController instances;
    private final LocalJoinService joinService;
    private final LobbyProvider lobbyProvider;
    private final Logger logger;

    public SLSCommand(
            ProxyServer proxy,
            BlueprintRepository blueprints,
            SoftwareProfileRepository softwareProfiles,
            ResourceBudget resourceBudget,
            ServerController instances,
            LocalJoinService joinService,
            LobbyProvider lobbyProvider,
            Logger logger
    ) {
        this.proxy = proxy;
        this.blueprints = blueprints;
        this.softwareProfiles = softwareProfiles;
        this.resourceBudget = resourceBudget;
        this.instances = instances;
        this.joinService = joinService;
        this.lobbyProvider = lobbyProvider;
        this.logger = logger;
    }

    @Override
    public void execute(Invocation invocation) {
        String[] arguments = invocation.arguments();
        if (arguments.length == 0) {
            sendRootHelp(invocation.source());
            return;
        }

        switch (arguments[0].toLowerCase(Locale.ROOT)) {
            case "blueprints" -> sendBlueprints(invocation.source(), arguments);
            case "console" -> console(invocation.source(), arguments);
            case "dequeue" -> dequeue(invocation.source(), arguments);
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
            case "blueprint", "create", "debug", "delete", "install", "kill",
                    "logs", "pause", "reset", "restart", "resume", "stats", "system" ->
                    unavailable(invocation.source(), arguments[0], false);
            case "node" -> unavailable(invocation.source(), arguments[0], true);
            default -> sendRootHelp(invocation.source());
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
                case "console" -> CommandPermissions.canAdminister(source, "console")
                        ? completed(withPrefix("this", instanceIds()))
                        : completed(List.of());
                case "find" -> completed(proxy.getAllPlayers().stream()
                        .map(Player::getUsername).sorted().toList());
                case "join" -> completed(withPrefix("player", sorted(blueprints.getTypes())));
                case "dequeue" -> CommandPermissions.canTargetOthers(source, "dequeue")
                        ? completed(joinTargets())
                        : completed(List.of());
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
            if ("player".equalsIgnoreCase(arguments[1])) {
                return completed(proxy.getAllPlayers().stream()
                        .map(Player::getUsername).sorted().toList());
            }
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
            if ("player".equalsIgnoreCase(arguments[1])) {
                return CommandPermissions.canAdminister(source, "join")
                        ? completed(List.of("--force"))
                        : completed(List.of());
            }
            return completed(joinTargets());
        }
        return completed(List.of());
    }

    private void sendRootHelp(CommandSource source) {
        source.sendMessage(CommandMessages.incorrectUsage());
        if (source.hasPermission(CommandPermissions.ADMIN)) {
            source.sendMessage(CommandMessages.usage(
                    "/sls",
                    VSLSCommandContract.ADMIN_ROOT.toArray(String[]::new)
            ));
        } else {
            source.sendMessage(CommandMessages.usage(
                    "/sls", VSLSCommandContract.PUBLIC_ROOT.toArray(String[]::new)
            ));
        }
    }

    private void sendSummary(CommandSource source) {
        TextComponent.Builder message = Component.text()
                .append(Component.text("Info", NamedTextColor.DARK_AQUA))
                .append(Component.text(" (SLS-LITE):", NamedTextColor.DARK_GRAY))
                .appendNewline()
                .append(infoLine("Registries:", Integer.toString(blueprints.getTypes().size())))
                .appendNewline()
                .append(infoLine("Blueprints:", Integer.toString(blueprints.getAll().size())))
                .appendNewline()
                .append(infoLine("Software profiles:", Integer.toString(
                        softwareProfiles.getAll().size()
                )))
                .appendNewline()
                .append(infoLine("Active servers:", Integer.toString(instances.getAll().size())))
                .appendNewline()
                .append(infoLine("Lobby status:", lobbyProvider.status().name()))
                .appendNewline()
                .append(infoLine("Queued players:", Integer.toString(
                        joinService.queuedPlayers().size()
                )))
                .appendNewline()
                .append(infoLine(
                        "Managed memory:",
                        resourceBudget.reservedMemoryMiB() + "/"
                                + resourceBudget.totalMemoryMiB() + " MiB"
                ));
        source.sendMessage(message.build());
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
            source.sendMessage(CommandMessages.message(
                    "No registries are loaded.", NamedTextColor.YELLOW
            ));
            return;
        }
        source.sendMessage(CommandMessages.message("Registries", NamedTextColor.GREEN));
        sorted(blueprints.getTypes()).forEach(type ->
                source.sendMessage(CommandMessages.prefix()
                        .append(Component.text("- " + type, NamedTextColor.GOLD))
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
            source.sendMessage(CommandMessages.usage("/sls blueprints", "registry"));
            return;
        }
        if (selected.isEmpty()) {
            source.sendMessage(CommandMessages.message(
                    "No matching blueprints are loaded.", NamedTextColor.YELLOW
            ));
            return;
        }
        source.sendMessage(CommandMessages.message("Blueprints", NamedTextColor.GREEN));
        selected.forEach(blueprint ->
                source.sendMessage(CommandMessages.prefix()
                        .append(Component.text(
                                "- " + blueprint.type() + " " + blueprint.id(),
                                NamedTextColor.GOLD
                        )).append(Component.text(
                        " (" + blueprint.software() + " " + blueprint.version()
                                + ", " + blueprint.memoryLimitMiB() + " MiB)"
                ).color(NamedTextColor.GRAY))));
    }

    private void sendInstances(CommandSource source) {
        if (instances.getAll().isEmpty()) {
            source.sendMessage(CommandMessages.message(
                    "No servers found.", NamedTextColor.RED
            ));
            return;
        }
        source.sendMessage(CommandMessages.listHeader());
        instances.getAll().stream()
                .sorted(Comparator.comparing(ManagedInstance::id))
                .forEach(instance -> source.sendMessage(CommandMessages.listEntry(
                        instance, playersOn(instance)
                )));
        source.sendMessage(CommandMessages.listFooter());
    }

    private void console(CommandSource source, String[] arguments) {
        if (!requireAdmin(source, "console", "send managed server console commands")) {
            return;
        }
        if (arguments.length == 1) {
            source.sendMessage(CommandMessages.incorrectUsage());
            source.sendMessage(CommandMessages.usage("/sls console", "server"));
            return;
        }
        if (arguments.length == 2) {
            source.sendMessage(CommandMessages.incorrectUsage());
            source.sendMessage(CommandMessages.usage(
                    "/sls console " + arguments[1], "command"
            ));
            return;
        }

        ManagedInstance instance = resolveConsoleInstance(source, arguments[1]);
        if (instance == null) {
            return;
        }
        String command = String.join(
                " ",
                java.util.Arrays.copyOfRange(arguments, 2, arguments.length)
        );
        try {
            instances.sendCommand(instance.id(), command);
            source.sendMessage(CommandMessages.message(
                    "Command executed successfully", NamedTextColor.GRAY
            ));
        } catch (InstanceOperationException exception) {
            source.sendMessage(CommandMessages.message(
                    "Failed to send command to server " + instance.id() + ": "
                            + exception.getMessage(),
                    NamedTextColor.RED
            ));
        }
    }

    private ManagedInstance resolveConsoleInstance(
            CommandSource source,
            String requestedId
    ) {
        String instanceId = requestedId;
        if ("this".equalsIgnoreCase(requestedId)) {
            if (!(source instanceof Player player)) {
                source.sendMessage(CommandMessages.message(
                        "Console must specify a server id.", NamedTextColor.RED
                ));
                return null;
            }
            instanceId = player.getCurrentServer()
                    .map(connection -> connection.getServerInfo().getName())
                    .orElse(null);
            if (instanceId == null || findInstanceOrNull(instanceId) == null) {
                source.sendMessage(CommandMessages.message(
                        "Server " + (instanceId == null ? "none" : instanceId)
                                + " is not an SLS server",
                        NamedTextColor.RED
                ));
                return null;
            }
        }
        ManagedInstance instance = findInstanceOrNull(instanceId);
        if (instance == null) {
            source.sendMessage(CommandMessages.message(
                    "No such server " + requestedId, NamedTextColor.RED
            ));
        }
        return instance;
    }

    private void start(CommandSource source, String[] arguments) {
        if (!requireAdmin(source, "start", "start managed servers")) {
            return;
        }
        Optional<Blueprint> blueprint = resolveBlueprint(arguments);
        if (blueprint.isEmpty()) {
            source.sendMessage(CommandMessages.usage(
                    "/sls start", "type", "blueprint"
            ));
            return;
        }

        try {
            ManagedInstance instance = instances.start(blueprint.get().id());
            source.sendMessage(CommandMessages.message(
                    "Preparing " + instance.id() + " from "
                            + blueprint.get().type() + "/" + blueprint.get().id() + "...",
                    NamedTextColor.YELLOW
            ));
            instance.readyFuture().whenComplete((ready, failure) -> {
                if (failure == null) {
                    source.sendMessage(CommandMessages.message(
                            "Server " + ready.id() + " is running.",
                            NamedTextColor.GREEN
                    ));
                } else if (rootCause(failure) instanceof CancellationException) {
                    source.sendMessage(CommandMessages.message(
                            "Server " + instance.id() + " startup cancelled.",
                            NamedTextColor.YELLOW
                    ));
                } else {
                    source.sendMessage(CommandMessages.message(
                            "Server " + instance.id() + " failed: " + rootMessage(failure),
                            NamedTextColor.RED
                    ));
                }
            });
        } catch (InstanceOperationException exception) {
            source.sendMessage(CommandMessages.message(
                    exception.getMessage(), NamedTextColor.RED
            ));
        }
    }

    private void join(CommandSource source, String[] arguments) {
        if (arguments.length >= 2 && "player".equalsIgnoreCase(arguments[1])) {
            joinPlayer(source, arguments);
            return;
        }
        if (arguments.length < 3 || arguments.length > 4) {
            source.sendMessage(CommandMessages.incorrectUsage());
            source.sendMessage(CommandMessages.usage(
                    "/sls join", "type", "player"
            ));
            return;
        }
        List<Player> targets;
        if (arguments.length == 4) {
            if (!CommandPermissions.canTargetOthers(source, "join")) {
                permissionDenied(source, "join other players");
                return;
            }
            targets = resolveTargets(source, arguments[3]);
        } else if (source instanceof Player player) {
            targets = List.of(player);
        } else {
            source.sendMessage(CommandMessages.message(
                    "Console must specify a player: "
                            + "/sls join <registry> <server> <player>",
                    NamedTextColor.RED
            ));
            return;
        }
        if (targets.isEmpty()) {
            return;
        }

        for (Player target : targets) {
            try {
                LocalJoinService.JoinAttempt attempt =
                        joinService.join(target, arguments[1], arguments[2]);
                ManagedInstance instance = attempt.instance();
                String action = attempt.created() ? "Preparing" : "Queued for";
                source.sendMessage(CommandMessages.prefix()
                        .append(Component.text(
                                action + " ", attempt.created()
                                        ? NamedTextColor.YELLOW
                                        : NamedTextColor.GREEN
                        ))
                        .append(CommandMessages.player(target))
                        .append(Component.text(" for ", NamedTextColor.GRAY))
                        .append(CommandMessages.server(
                                instance, playersOn(instance).size()
                        ))
                        .append(Component.text(".", NamedTextColor.GRAY)));
                if (source != target) {
                    target.sendMessage(CommandMessages.message(
                            "Queued for " + arguments[1] + "/" + arguments[2] + ".",
                            NamedTextColor.YELLOW
                    ));
                }
                attempt.connection().whenComplete((result, failure) ->
                        reportConnection(source, target, instance, result, failure));
            } catch (InstanceOperationException exception) {
                source.sendMessage(CommandMessages.message(
                        target.getUsername() + ": " + exception.getMessage(),
                        NamedTextColor.RED
                ));
            }
        }
    }

    private void joinPlayer(CommandSource source, String[] arguments) {
        if (arguments.length < 3 || arguments.length > 4) {
            source.sendMessage(CommandMessages.usage(
                    "/sls join player", "player"
            ));
            return;
        }
        if (!(source instanceof Player player)) {
            source.sendMessage(CommandMessages.message(
                    "Console cannot join another player's server.", NamedTextColor.RED
            ));
            return;
        }
        if (arguments.length == 4) {
            if (!"--force".equalsIgnoreCase(arguments[3])) {
                source.sendMessage(CommandMessages.usage(
                        "/sls join player", "player"
                ));
                return;
            }
            if (!CommandPermissions.canAdminister(source, "join")) {
                permissionDenied(source, "force a player join");
                return;
            }
        }

        Player target = proxy.getPlayer(arguments[2]).orElse(null);
        if (target == null) {
            sendPlayerNotFound(source, arguments[2]);
            return;
        }
        try {
            LocalJoinService.DirectJoin directJoin =
                    joinService.joinPlayer(player, target);
            source.sendMessage(CommandMessages.prefix()
                    .append(Component.text("Joining ", NamedTextColor.GREEN))
                    .append(CommandMessages.player(target))
                    .append(Component.text(" on ", NamedTextColor.GRAY))
                    .append(CommandMessages.server(
                            directJoin.instance(),
                            playersOn(directJoin.instance()).size()
                    ))
                    .append(Component.text(".", NamedTextColor.GRAY)));
            directJoin.connection().whenComplete((result, failure) ->
                    reportConnection(
                            source,
                            player,
                            directJoin.instance(),
                            result,
                            failure
                    ));
        } catch (InstanceOperationException exception) {
            source.sendMessage(CommandMessages.message(
                    exception.getMessage(), NamedTextColor.RED
            ));
        }
    }

    private void dequeue(CommandSource source, String[] arguments) {
        if (arguments.length > 2) {
            source.sendMessage(CommandMessages.usage(
                    "/sls dequeue", "all", "local", "player"
            ));
            return;
        }

        List<LocalJoinService.QueueTicket> removed;
        if (arguments.length == 1) {
            if (!(source instanceof Player player)) {
                source.sendMessage(CommandMessages.message(
                        "Console must specify all or a player.", NamedTextColor.RED
                ));
                return;
            }
            removed = joinService.dequeue(player.getUniqueId()).stream().toList();
        } else {
            if (!CommandPermissions.canTargetOthers(source, "dequeue")) {
                permissionDenied(source, "dequeue other players");
                return;
            }
            String target = arguments[1];
            if ("all".equalsIgnoreCase(target)) {
                removed = joinService.dequeueAll();
            } else if ("local".equalsIgnoreCase(target)) {
                List<Player> local = resolveTargets(source, "local");
                removed = joinService.dequeue(local.stream()
                        .map(Player::getUniqueId).toList());
            } else {
                Player player = proxy.getPlayer(target).orElse(null);
                if (player == null) {
                    sendPlayerNotFound(source, target);
                    return;
                }
                removed = joinService.dequeue(player.getUniqueId()).stream().toList();
            }
        }

        if (removed.isEmpty()) {
            source.sendMessage(CommandMessages.message(
                    "No matching players were queued.", NamedTextColor.YELLOW
            ));
            return;
        }
        source.sendMessage(CommandMessages.message(
                "Removed " + removed.size() + " player(s) from matchmaking.",
                NamedTextColor.GREEN
        ));
    }

    private List<Player> resolveTargets(CommandSource source, String target) {
        if ("all".equalsIgnoreCase(target)) {
            return List.copyOf(proxy.getAllPlayers());
        }
        if ("local".equalsIgnoreCase(target)) {
            if (!(source instanceof Player player)) {
                source.sendMessage(CommandMessages.message(
                        "Console cannot use the local player selector.", NamedTextColor.RED
                ));
                return List.of();
            }
            return player.getCurrentServer()
                    .map(connection -> List.copyOf(connection.getServer().getPlayersConnected()))
                    .orElseGet(() -> {
                        source.sendMessage(CommandMessages.message(
                                "You are not connected to a backend server.",
                                NamedTextColor.RED
                        ));
                        return List.of();
                    });
        }
        Player player = proxy.getPlayer(target).orElse(null);
        if (player == null) {
            sendPlayerNotFound(source, target);
            return List.of();
        }
        return List.of(player);
    }

    private void reportConnection(
            CommandSource source,
            Player target,
            ManagedInstance instance,
            ConnectionRequestBuilder.Result result,
            Throwable failure
    ) {
        if (failure != null) {
            if (rootCause(failure) instanceof LocalJoinService.QueueCancelledException) {
                return;
            }
            source.sendMessage(CommandMessages.message(
                    "Unable to connect " + target.getUsername() + ": "
                            + rootMessage(failure),
                    NamedTextColor.RED
            ));
            return;
        }
        if (result.isSuccessful()
                || result.getStatus() == ConnectionRequestBuilder.Status.ALREADY_CONNECTED) {
            source.sendMessage(CommandMessages.message(
                    "Connected " + target.getUsername() + " to " + instance.id() + ".",
                    NamedTextColor.GREEN
            ));
            return;
        }
        source.sendMessage(CommandMessages.message(
                "Connection to " + instance.id() + " failed: " + result.getStatus(),
                NamedTextColor.RED
        ));
    }

    private void find(CommandSource source, String[] arguments) {
        if (arguments.length != 2) {
            source.sendMessage(CommandMessages.usage("/sls find", "player"));
            return;
        }
        Player player = proxy.getPlayer(arguments[1]).orElse(null);
        if (player == null) {
            sendPlayerNotFound(source, arguments[1]);
            return;
        }
        String current = player.getCurrentServer()
                .map(connection -> connection.getServerInfo().getName())
                .orElse(null);
        boolean managed = current != null && instances.getAll().stream()
                .anyMatch(instance -> instance.id().equals(current));
        if (!managed) {
            source.sendMessage(CommandMessages.prefix()
                    .append(CommandMessages.player(player))
                    .append(Component.text(
                            " is not on an SLS-LITE server. ", NamedTextColor.RED
                    ))
                    .append(CommandMessages.labelValue("Current server:",
                            current == null ? "none" : current)));
            sendActionBar(source, Component.text(
                    player.getUsername() + " is not on an SLS-LITE server",
                    NamedTextColor.RED
            ));
            return;
        }
        ManagedInstance instance = findInstance(current);
        source.sendMessage(CommandMessages.prefix()
                .append(CommandMessages.player(player))
                .append(Component.text(" is currently on ", NamedTextColor.GRAY))
                .append(CommandMessages.server(instance, playersOn(instance).size())));
        sendActionBar(source, Component.text(
                player.getUsername() + " is on " + instance.id(),
                NamedTextColor.GREEN
        ));
    }

    private void stop(CommandSource source, String[] arguments) {
        if (!requireAdmin(source, "stop", "stop managed servers")) {
            return;
        }
        if (arguments.length != 2) {
            source.sendMessage(CommandMessages.usage("/sls stop", "server"));
            return;
        }
        if (lobbyProvider.isLobby(arguments[1])) {
            source.sendMessage(CommandMessages.message(
                    "The active lobby cannot be stopped with /sls stop.",
                    NamedTextColor.RED
            ));
            return;
        }
        source.sendMessage(CommandMessages.message(
                "Moving players to the lobby before stopping " + arguments[1] + "...",
                NamedTextColor.YELLOW
        ));
        lobbyProvider.evacuate(arguments[1]).whenComplete((ignored, evacuationFailure) -> {
            if (evacuationFailure != null) {
                source.sendMessage(CommandMessages.message(
                        "Stop cancelled: " + rootMessage(evacuationFailure),
                        NamedTextColor.RED
                ));
                return;
            }
            stopInstance(source, arguments[1]);
        });
    }

    private void stopInstance(CommandSource source, String instanceId) {
        try {
            source.sendMessage(CommandMessages.message(
                    "Stopping " + instanceId + "...", NamedTextColor.YELLOW
            ));
            instances.stop(instanceId).whenComplete((exitCode, failure) -> {
                if (failure == null) {
                    source.sendMessage(CommandMessages.message(
                            "Stopped " + instanceId + " with exit code " + exitCode + ".",
                            NamedTextColor.GREEN
                    ));
                } else {
                    source.sendMessage(CommandMessages.message(
                            "Stop failed: " + rootMessage(failure), NamedTextColor.RED
                    ));
                }
            });
        } catch (InstanceOperationException exception) {
            source.sendMessage(CommandMessages.message(
                    exception.getMessage(), NamedTextColor.RED
            ));
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
            source.sendMessage(CommandMessages.usage(
                    "/sls " + permission, "server"
            ));
            return;
        }
        try {
            ManagedInstance instance = instances.get(arguments[1]);
            if ("status".equals(permission)) {
                source.sendMessage(CommandMessages.prefix()
                        .append(Component.text("Status: ", NamedTextColor.DARK_AQUA))
                        .append(Component.text(
                                CommandMessages.statusName(instance.state()),
                                NamedTextColor.GRAY
                        )));
                return;
            }
            sendInstanceInfo(source, instance);
        } catch (InstanceOperationException exception) {
            source.sendMessage(CommandMessages.message(
                    "No such server " + arguments[1], NamedTextColor.RED
            ));
        }
    }

    private void reload(CommandSource source, String[] arguments) {
        if (!requireAdmin(source, "reload", "reload SLS-LITE")) {
            return;
        }
        String mode = arguments.length == 1 ? "all" : arguments[1].toLowerCase(Locale.ROOT);
        if (arguments.length > 2 || !List.of("all", "blueprints", "software").contains(mode)) {
            source.sendMessage(CommandMessages.usage(
                    "/sls reload", "all", "blueprints", "software"
            ));
            return;
        }
        try {
            if ("all".equals(mode) || "software".equals(mode)) {
                softwareProfiles.reload();
            }
            if ("all".equals(mode) || "blueprints".equals(mode)) {
                blueprints.reload();
            }
            source.sendMessage(CommandMessages.message(
                    "Reloaded " + mode + ": " + blueprints.getTypes().size()
                            + " registries, " + blueprints.getAll().size()
                            + " blueprints, " + softwareProfiles.getAll().size()
                            + " software profiles.",
                    NamedTextColor.GREEN
            ));
        } catch (Exception exception) {
            logger.error("Unable to reload SLS-LITE " + mode, exception);
            source.sendMessage(CommandMessages.message(
                    "Reload failed: " + rootMessage(exception), NamedTextColor.RED
            ));
        }
    }

    private void sendVersion(CommandSource source) {
        source.sendMessage(CommandMessages.prefix()
                .append(Component.text("Version: ", NamedTextColor.DARK_AQUA)
                        .decorate(TextDecoration.BOLD))
                .append(Component.text(BuildInfo.VERSION, NamedTextColor.GOLD))
                .append(Component.text(" By: ", NamedTextColor.DARK_AQUA))
                .append(Component.text(BuildInfo.AUTHORS, NamedTextColor.GOLD)));
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
        source.sendMessage(CommandMessages.message(
                "You do not have permission to " + operation + ".",
                NamedTextColor.RED
        ));
    }

    private void unavailable(
            CommandSource source,
            String command,
            boolean distributedOnly
    ) {
        if (!requireAdmin(source, command, "use /sls " + command)) {
            return;
        }
        String explanation = distributedOnly
                ? " is not available in local mode."
                : " is not available in this SLS-LITE build yet.";
        source.sendMessage(CommandMessages.prefix()
                .append(Component.text("/sls " + command, NamedTextColor.GOLD))
                .append(Component.text(explanation, NamedTextColor.GRAY)));
    }

    private void sendInstanceInfo(CommandSource source, ManagedInstance instance) {
        List<Player> players = playersOn(instance);
        long uptimeSeconds = Math.max(
                0L,
                java.time.Duration.between(
                        instance.createdAt(), java.time.Instant.now()
                ).toSeconds()
        );
        TextComponent.Builder message = Component.text()
                .append(Component.text("Info", NamedTextColor.DARK_AQUA))
                .append(Component.text(
                        " (" + instance.id() + "):", NamedTextColor.DARK_GRAY
                ))
                .appendNewline()
                .append(infoLine("Players:", Integer.toString(players.size()))
                        .hoverEvent(Component.text(
                                players.isEmpty()
                                        ? "No players"
                                        : players.stream().map(Player::getUsername)
                                                .sorted().reduce((left, right) ->
                                                        left + ", " + right).orElse(""),
                                NamedTextColor.DARK_PURPLE
                        )))
                .appendNewline()
                .append(Component.text(" - ", NamedTextColor.GOLD))
                .append(Component.text("Status:", NamedTextColor.DARK_GRAY))
                .append(Component.text(
                        " " + CommandMessages.statusName(instance.state()),
                        CommandMessages.statusColor(instance.state())
                ))
                .appendNewline()
                .append(infoLine("Blueprint:", instance.blueprint().name())
                        .hoverEvent(Component.text(
                                instance.blueprint().id(), NamedTextColor.DARK_PURPLE
                        )))
                .appendNewline()
                .append(infoLine("Type:", instance.blueprint().type()))
                .appendNewline()
                .append(infoLine(
                        "Server:",
                        instance.blueprint().software() + " "
                                + instance.blueprint().version()
                ))
                .appendNewline()
                .append(infoLine("Port:", Integer.toString(instance.port())))
                .appendNewline()
                .append(infoLine(
                        "Mem:",
                        instance.blueprint().memoryLimitMiB() + " MiB limit"
                ))
                .appendNewline()
                .append(infoLine("Uptime:", formatDuration(uptimeSeconds)))
                .appendNewline()
                .append(Component.text(
                        "------------------------------------",
                        NamedTextColor.DARK_GRAY
                ).decorate(TextDecoration.STRIKETHROUGH).decorate(TextDecoration.BOLD));
        source.sendMessage(message.build());
    }

    private static Component infoLine(String label, String value) {
        return Component.text(" - ", NamedTextColor.GOLD)
                .append(Component.text(label, NamedTextColor.DARK_GRAY))
                .append(Component.text(" " + value, NamedTextColor.BLUE));
    }

    private List<Player> playersOn(ManagedInstance instance) {
        return proxy.getServer(instance.id())
                .map(server -> List.copyOf(server.getPlayersConnected()))
                .orElseGet(List::of);
    }

    private ManagedInstance findInstance(String id) {
        return java.util.Objects.requireNonNull(findInstanceOrNull(id));
    }

    private ManagedInstance findInstanceOrNull(String id) {
        return instances.getAll().stream()
                .filter(instance -> instance.id().equals(id))
                .findFirst()
                .orElse(null);
    }

    private static void sendPlayerNotFound(CommandSource source, String playerName) {
        source.sendMessage(CommandMessages.prefix()
                .append(Component.text("Player ", NamedTextColor.RED))
                .append(Component.text(playerName, NamedTextColor.DARK_AQUA))
                .append(Component.text(" was not found.", NamedTextColor.RED)));
        sendActionBar(source, Component.text(
                "Player not found: " + playerName, NamedTextColor.RED
        ));
    }

    private static void sendActionBar(CommandSource source, Component component) {
        if (source instanceof Player player) {
            player.sendActionBar(component);
        }
    }

    private static String formatDuration(long seconds) {
        long hours = seconds / 3600;
        long minutes = (seconds % 3600) / 60;
        long remainingSeconds = seconds % 60;
        return hours + "h " + minutes + "m " + remainingSeconds + "s";
    }

    private List<String> instanceIds() {
        return instances.getAll().stream().map(ManagedInstance::id).sorted().toList();
    }

    private List<String> joinTargets() {
        Set<String> targets = new LinkedHashSet<>();
        targets.add("all");
        targets.add("local");
        proxy.getAllPlayers().stream()
                .map(Player::getUsername)
                .sorted()
                .forEach(targets::add);
        return List.copyOf(targets);
    }

    private static List<String> withPrefix(String prefix, List<String> values) {
        List<String> result = new java.util.ArrayList<>(values.size() + 1);
        result.add(prefix);
        result.addAll(values);
        return List.copyOf(result);
    }

    private static List<String> sorted(Collection<String> values) {
        return values.stream().sorted(Comparator.naturalOrder()).toList();
    }

    private static CompletableFuture<List<String>> completed(List<String> values) {
        return CompletableFuture.completedFuture(values);
    }

    private static String rootMessage(Throwable throwable) {
        Throwable current = rootCause(throwable);
        return current.getMessage() == null
                ? current.getClass().getSimpleName()
                : current.getMessage();
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
