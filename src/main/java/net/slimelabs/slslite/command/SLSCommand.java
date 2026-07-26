package net.slimelabs.slslite.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.ConsoleCommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.slimelabs.slslite.BuildInfo;
import net.slimelabs.slslite.blueprint.Blueprint;
import net.slimelabs.slslite.blueprint.BlueprintRepository;
import net.slimelabs.slslite.config.ManagedOutputConfig;
import net.slimelabs.slslite.host.HostCapability;
import net.slimelabs.slslite.host.HostCapabilityReport;
import net.slimelabs.slslite.host.HostCapabilityStatus;
import net.slimelabs.slslite.instance.InstanceOperationException;
import net.slimelabs.slslite.instance.InstanceLogPage;
import net.slimelabs.slslite.instance.ManagedInstance;
import net.slimelabs.slslite.instance.ServerController;
import net.slimelabs.slslite.lobby.LobbyProvider;
import net.slimelabs.slslite.resource.ResourceBudget;
import net.slimelabs.slslite.security.AdminClaimService;
import net.slimelabs.slslite.security.Administrator;
import net.slimelabs.slslite.security.AdministratorStore;
import net.slimelabs.slslite.software.SoftwareProfileRepository;
import net.slimelabs.slslite.velocity.LocalJoinService;
import org.slf4j.Logger;

import java.io.IOException;
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

    private static final int DEFAULT_LOG_PAGE = 1;
    private static final int DEFAULT_LOG_LINES = 50;
    private static final int MAX_LOG_LINES = 100;

    private static final List<String> PUBLIC_COMMANDS =
            List.of(
                    "admin", "dequeue", "find", "info", "join", "list",
                    "registries", "version"
            );
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
    private final ManagedOutputConfig outputConfig;
    private final HostCapabilityReport hostCapabilities;
    private final AdministratorStore administrators;
    private final AdminClaimService adminClaims;
    private final CommandAuthorizer authorizer;
    private final Logger logger;

    public SLSCommand(
            ProxyServer proxy,
            BlueprintRepository blueprints,
            SoftwareProfileRepository softwareProfiles,
            ResourceBudget resourceBudget,
            ServerController instances,
            LocalJoinService joinService,
            LobbyProvider lobbyProvider,
            ManagedOutputConfig outputConfig,
            HostCapabilityReport hostCapabilities,
            AdministratorStore administrators,
            AdminClaimService adminClaims,
            Logger logger
    ) {
        this.proxy = proxy;
        this.blueprints = blueprints;
        this.softwareProfiles = softwareProfiles;
        this.resourceBudget = resourceBudget;
        this.instances = instances;
        this.joinService = joinService;
        this.lobbyProvider = lobbyProvider;
        this.outputConfig = outputConfig;
        this.hostCapabilities = hostCapabilities;
        this.administrators = administrators;
        this.adminClaims = adminClaims;
        this.authorizer = new CommandAuthorizer(administrators);
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
            case "admin" -> admin(invocation.source(), arguments);
            case "blueprints" -> sendBlueprints(invocation.source(), arguments);
            case "console" -> console(invocation.source(), arguments);
            case "dequeue" -> dequeue(invocation.source(), arguments);
            case "find" -> find(invocation.source(), arguments);
            case "info" -> info(invocation.source(), arguments);
            case "join" -> join(invocation.source(), arguments);
            case "list" -> sendInstances(invocation.source());
            case "logs" -> logs(invocation.source(), arguments);
            case "registries" -> sendRegistries(invocation.source());
            case "reload" -> reload(invocation.source(), arguments);
            case "reset" -> reset(invocation.source(), arguments);
            case "restart" -> restart(invocation.source(), arguments);
            case "start" -> start(invocation.source(), arguments);
            case "stats" -> stats(invocation.source(), arguments);
            case "status" -> status(invocation.source(), arguments);
            case "stop" -> stop(invocation.source(), arguments);
            case "system" -> system(invocation.source(), arguments);
            case "version" -> sendVersion(invocation.source());
            case "blueprint", "create", "debug", "delete", "install", "kill",
                    "pause", "resume" ->
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
                    .filter(command -> authorizer.canAdminister(source, command))
                    .forEach(suggestions::add);
            return completed(suggestions);
        }

        String operation = arguments[0].toLowerCase(Locale.ROOT);
        if (arguments.length == 2) {
            return switch (operation) {
                case "admin" -> completed(adminActions(source));
                case "blueprints" -> authorizer.canAdminister(source, "blueprints")
                        ? completed(sorted(blueprints.getTypes()))
                        : completed(List.of());
                case "console", "logs" ->
                        authorizer.canAdminister(source, operation)
                        ? completed(withPrefix("this", instanceIds()))
                        : completed(List.of());
                case "find" -> completed(proxy.getAllPlayers().stream()
                        .map(Player::getUsername).sorted().toList());
                case "join" -> completed(withPrefix("player", sorted(blueprints.getTypes())));
                case "dequeue" -> authorizer.canTargetOthers(source, "dequeue")
                        ? completed(joinTargets())
                        : completed(List.of());
                case "reload" -> authorizer.canAdminister(source, "reload")
                        ? completed(List.of("all", "blueprints", "software"))
                        : completed(List.of());
                case "start" -> authorizer.canAdminister(source, "start")
                        ? completed(sorted(blueprints.getTypes()))
                        : completed(List.of());
                case "reset", "restart" ->
                        authorizer.canAdminister(source, operation)
                                ? completed(withPrefix("this", persistentInstanceIds()))
                                : completed(List.of());
                case "status", "stop", "info", "stats" ->
                        authorizer.canAdminister(source, operation)
                                ? completed(withPrefix("this", instanceIds()))
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
                && authorizer.canAdminister(source, "start")) {
            return completed(blueprints.getByType(arguments[1]).stream()
                    .map(Blueprint::id).toList());
        }
        if (arguments.length == 3 && "admin".equals(operation)
                && authorizer.canAdminister(source, "admin")) {
            if ("add".equalsIgnoreCase(arguments[1])) {
                return completed(proxy.getAllPlayers().stream()
                        .map(Player::getUsername).sorted().toList());
            }
            if ("remove".equalsIgnoreCase(arguments[1])) {
                return completed(administrators.list().stream()
                        .map(Administrator::lastKnownName).toList());
            }
        }
        if (arguments.length == 3 && "logs".equals(operation)
                && authorizer.canAdminister(source, "logs")) {
            return completed(List.of("1"));
        }
        if (arguments.length == 4 && "logs".equals(operation)
                && authorizer.canAdminister(source, "logs")) {
            return completed(List.of("50", "100", "max"));
        }
        if (arguments.length == 4 && "join".equals(operation)
                && authorizer.canTargetOthers(source, "join")) {
            if ("player".equalsIgnoreCase(arguments[1])) {
                return authorizer.canAdminister(source, "join")
                        ? completed(List.of("--force"))
                        : completed(List.of());
            }
            return completed(joinTargets());
        }
        return completed(List.of());
    }

    private List<String> adminActions(CommandSource source) {
        List<String> actions = new java.util.ArrayList<>();
        if (source instanceof Player) {
            actions.add("claim");
        }
        if (authorizer.canAdminister(source, "admin")) {
            actions.addAll(List.of("add", "list", "remove"));
        }
        if (source instanceof ConsoleCommandSource) {
            actions.add("code");
        }
        return List.copyOf(actions);
    }

    private void admin(CommandSource source, String[] arguments) {
        if (arguments.length < 2) {
            source.sendMessage(CommandMessages.usage(
                    "/sls admin", "claim <code>", "add <online-player>",
                    "remove <player>", "list", "code"
            ));
            return;
        }
        String action = arguments[1].toLowerCase(Locale.ROOT);
        switch (action) {
            case "claim" -> claimAdministration(source, arguments);
            case "add" -> addAdministrator(source, arguments);
            case "remove" -> removeAdministrator(source, arguments);
            case "list" -> listAdministrators(source, arguments);
            case "code" -> issueAdminCode(source, arguments);
            default -> source.sendMessage(CommandMessages.usage(
                    "/sls admin", "claim <code>", "add <online-player>",
                    "remove <player>", "list", "code"
            ));
        }
    }

    private void claimAdministration(CommandSource source, String[] arguments) {
        if (!(source instanceof Player player)) {
            source.sendMessage(CommandMessages.message(
                    "Only a player can claim in-game administration.",
                    NamedTextColor.RED
            ));
            return;
        }
        if (arguments.length != 3) {
            source.sendMessage(CommandMessages.usage(
                    "/sls admin claim", "<code>"
            ));
            return;
        }
        try {
            AdminClaimService.ClaimResult result = adminClaims.claim(player, arguments[2]);
            switch (result) {
                case CLAIMED -> source.sendMessage(CommandMessages.message(
                        "You are now an SLS-LITE administrator.",
                        NamedTextColor.GREEN
                ));
                case ALREADY_ADMINISTRATOR -> source.sendMessage(CommandMessages.message(
                        "You are already an SLS-LITE administrator.",
                        NamedTextColor.YELLOW
                ));
                case INVALID -> source.sendMessage(CommandMessages.message(
                        "That administrator claim code is invalid.",
                        NamedTextColor.RED
                ));
                case EXPIRED -> source.sendMessage(CommandMessages.message(
                        "That administrator claim code has expired. Generate another "
                                + "from the proxy console.",
                        NamedTextColor.RED
                ));
                case NO_ACTIVE_CODE -> source.sendMessage(CommandMessages.message(
                        "No administrator claim code is active. Generate one from "
                                + "the proxy console.",
                        NamedTextColor.RED
                ));
                case OFFLINE_MODE_BLOCKED -> source.sendMessage(CommandMessages.message(
                        "In-game administrator claims are disabled while Velocity "
                                + "is in offline mode.",
                        NamedTextColor.RED
                ));
            }
        } catch (IOException exception) {
            logger.error("Unable to persist claimed SLS-LITE administrator", exception);
            source.sendMessage(CommandMessages.message(
                    "Unable to save the administrator claim.",
                    NamedTextColor.RED
            ));
        }
    }

    private void addAdministrator(CommandSource source, String[] arguments) {
        if (!requireAdmin(source, "admin", "add an SLS-LITE administrator")) {
            return;
        }
        if (arguments.length != 3) {
            source.sendMessage(CommandMessages.usage(
                    "/sls admin add", "<online-player>"
            ));
            return;
        }
        Player player = proxy.getPlayer(arguments[2]).orElse(null);
        if (player == null) {
            source.sendMessage(CommandMessages.message(
                    "Online player not found: " + arguments[2],
                    NamedTextColor.RED
            ));
            return;
        }
        try {
            adminClaims.requireSecureIdentity();
            administrators.add(player.getUniqueId(), player.getUsername());
            source.sendMessage(CommandMessages.message(
                    "Added SLS-LITE administrator " + player.getUsername() + ".",
                    NamedTextColor.GREEN
            ));
        } catch (AdminClaimService.InsecureOfflineModeException exception) {
            source.sendMessage(CommandMessages.message(
                    exception.getMessage() + ".",
                    NamedTextColor.RED
            ));
        } catch (IOException exception) {
            logger.error("Unable to persist SLS-LITE administrator", exception);
            source.sendMessage(CommandMessages.message(
                    "Unable to save the administrator.",
                    NamedTextColor.RED
            ));
        }
    }

    private void removeAdministrator(CommandSource source, String[] arguments) {
        if (!requireAdmin(source, "admin", "remove an SLS-LITE administrator")) {
            return;
        }
        if (arguments.length != 3) {
            source.sendMessage(CommandMessages.usage(
                    "/sls admin remove", "<player>"
            ));
            return;
        }
        try {
            Optional<Administrator> removed = administrators.remove(arguments[2]);
            if (removed.isEmpty()) {
                source.sendMessage(CommandMessages.message(
                        "SLS-LITE administrator not found: " + arguments[2],
                        NamedTextColor.RED
                ));
                return;
            }
            source.sendMessage(CommandMessages.message(
                    "Removed SLS-LITE administrator "
                            + removed.get().lastKnownName() + ".",
                    NamedTextColor.GREEN
            ));
        } catch (IOException exception) {
            logger.error("Unable to remove SLS-LITE administrator", exception);
            source.sendMessage(CommandMessages.message(
                    "Unable to save the administrator change.",
                    NamedTextColor.RED
            ));
        }
    }

    private void listAdministrators(CommandSource source, String[] arguments) {
        if (!requireAdmin(source, "admin", "list SLS-LITE administrators")) {
            return;
        }
        if (arguments.length != 2) {
            source.sendMessage(CommandMessages.usage("/sls admin", "list"));
            return;
        }
        List<Administrator> current = administrators.list();
        String names = current.isEmpty()
                ? "none"
                : current.stream()
                        .map(Administrator::lastKnownName)
                        .reduce((left, right) -> left + ", " + right)
                        .orElse("none");
        source.sendMessage(CommandMessages.message(
                "SLS-LITE administrators: " + names,
                NamedTextColor.GRAY
        ));
    }

    private void issueAdminCode(CommandSource source, String[] arguments) {
        if (!(source instanceof ConsoleCommandSource)) {
            source.sendMessage(CommandMessages.message(
                    "Administrator claim codes can only be generated from "
                            + "the proxy console.",
                    NamedTextColor.RED
            ));
            return;
        }
        if (arguments.length != 2) {
            source.sendMessage(CommandMessages.usage("/sls admin", "code"));
            return;
        }
        try {
            String code = adminClaims.issueCode();
            source.sendMessage(CommandMessages.message(
                    "Administrator claim code: " + code,
                    NamedTextColor.GOLD
            ));
        } catch (AdminClaimService.InsecureOfflineModeException exception) {
            source.sendMessage(CommandMessages.message(
                    exception.getMessage()
                            + ". Enable online mode or explicitly allow insecure "
                            + "offline administrators in config.yml.",
                    NamedTextColor.RED
            ));
        }
    }

    private void sendRootHelp(CommandSource source) {
        source.sendMessage(CommandMessages.incorrectUsage());
        if (authorizer.canAdminister(source, "admin")) {
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

        ManagedInstance instance = resolveInstance(source, arguments[1]);
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

    private void logs(CommandSource source, String[] arguments) {
        if (!requireAdmin(source, "logs", "view managed server logs")) {
            return;
        }
        if (arguments.length < 2 || arguments.length > 4) {
            source.sendMessage(CommandMessages.incorrectUsage());
            source.sendMessage(CommandMessages.usage("/sls logs", "server"));
            return;
        }

        Integer page = arguments.length >= 3
                ? parsePositiveInt(arguments[2])
                : DEFAULT_LOG_PAGE;
        Integer lines = arguments.length == 4
                ? parseLogLines(arguments[3])
                : DEFAULT_LOG_LINES;
        if (page == null) {
            invalidNumber(source, arguments[2]);
            return;
        }
        if (lines == null) {
            invalidNumber(source, arguments[3]);
            return;
        }
        lines = Math.min(lines, MAX_LOG_LINES);

        ManagedInstance instance = resolveInstance(source, arguments[1]);
        if (instance == null) {
            return;
        }
        InstanceLogPage result = instance.logs(page, lines);
        int totalPages = Math.max(
                1,
                (result.totalRetainedLines() + lines - 1) / lines
        );
        if (page > totalPages) {
            source.sendMessage(CommandMessages.prefix()
                    .append(Component.text(
                            "Page " + page + " does not exist ", NamedTextColor.RED
                    ))
                    .append(Component.text(
                            "(valid range: 1-" + totalPages + ")",
                            NamedTextColor.GRAY
                    )));
            return;
        }

        source.sendMessage(Component.text("----------------", NamedTextColor.DARK_GRAY)
                .decorate(TextDecoration.STRIKETHROUGH, TextDecoration.BOLD)
                .append(Component.text(
                        " Logs for " + instance.id() + " ",
                        NamedTextColor.GOLD
                ).decoration(TextDecoration.STRIKETHROUGH, false))
                .append(Component.text("----------------", NamedTextColor.DARK_GRAY)
                        .decorate(TextDecoration.STRIKETHROUGH, TextDecoration.BOLD)));
        if (result.lines().isEmpty()) {
            source.sendMessage(Component.text(
                    "No output has been retained for this server.", NamedTextColor.GRAY
            ));
        } else {
            TextComponent.Builder output = Component.text();
            result.lines().forEach(line -> output
                    .append(Component.text(line, NamedTextColor.GRAY))
                    .appendNewline());
            source.sendMessage(output.build());
        }
        source.sendMessage(logPaginationFooter(
                instance.id(),
                page,
                lines,
                totalPages,
                result.totalRetainedLines(),
                result.retentionCapacity()
        ));
    }

    private void stats(CommandSource source, String[] arguments) {
        if (!requireAdmin(source, "stats", "view managed server statistics")) {
            return;
        }
        if (arguments.length > 2) {
            source.sendMessage(CommandMessages.usage("/sls stats", "server"));
            return;
        }
        ManagedInstance instance = resolveInstance(
                source,
                arguments.length == 2 ? arguments[1] : "this"
        );
        if (instance == null) {
            return;
        }

        long uptime = Math.max(
                0L,
                java.time.Duration.between(
                        instance.processStartedAt().orElse(instance.createdAt()),
                        java.time.Instant.now()
                ).toSeconds()
        );
        String cpuTime = instance.processCpuTime()
                .map(duration -> formatDuration(duration.toSeconds()))
                .orElse("not measurable");
        source.sendMessage(Component.text("Stats", NamedTextColor.DARK_AQUA)
                .append(Component.text(" (" + instance.id() + "):", NamedTextColor.DARK_GRAY))
                .appendNewline()
                .append(infoLine(
                        "Status:",
                        CommandMessages.statusName(instance.state())
                ))
                .appendNewline()
                .append(infoLine("CPU time:", cpuTime))
                .appendNewline()
                .append(infoLine(
                        "Mem:",
                        instance.blueprint().memoryLimitMiB() + " MiB configured"
                ))
                .appendNewline()
                .append(infoLine("Uptime:", formatDuration(uptime)))
                .appendNewline()
                .append(infoLine(
                        "Logs:",
                        instance.retainedLogLines() + "/"
                                + instance.logRetentionCapacity() + " lines"
                ))
                .appendNewline()
                .append(Component.text(
                        "Current process memory, network, and disk usage are not "
                                + "measurable by the Java supervisor.",
                        NamedTextColor.DARK_GRAY
                )));
    }

    private void system(CommandSource source, String[] arguments) {
        if (!requireAdmin(source, "system", "view local host information")) {
            return;
        }
        if (arguments.length != 1) {
            source.sendMessage(CommandMessages.usage("/sls system"));
            return;
        }

        Runtime runtime = Runtime.getRuntime();
        long usedJvmMiB = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L);
        long maxJvmMiB = runtime.maxMemory() / (1024L * 1024L);
        TextComponent.Builder message = Component.text()
                .append(Component.text("----------------", NamedTextColor.DARK_GRAY)
                        .decorate(TextDecoration.STRIKETHROUGH, TextDecoration.BOLD))
                .append(Component.text(
                        " INFO ", NamedTextColor.DARK_AQUA
                ).decoration(TextDecoration.STRIKETHROUGH, false))
                .append(Component.text("----------------", NamedTextColor.DARK_GRAY)
                        .decorate(TextDecoration.STRIKETHROUGH, TextDecoration.BOLD))
                .appendNewline()
                .append(infoLine("Version:", BuildInfo.VERSION))
                .appendNewline()
                .append(infoLine("Architecture:", System.getProperty("os.arch", "unknown")))
                .appendNewline()
                .append(infoLine(
                        "CPU Threads:",
                        Integer.toString(runtime.availableProcessors())
                ))
                .appendNewline()
                .append(infoLine(
                        "Velocity JVM:",
                        usedJvmMiB + "/" + maxJvmMiB + " MiB"
                ))
                .appendNewline()
                .append(infoLine(
                        "Managed memory:",
                        resourceBudget.reservedMemoryMiB() + "/"
                                + resourceBudget.totalMemoryMiB() + " MiB"
                ))
                .appendNewline()
                .append(infoLine(
                        "Managed servers:",
                        Integer.toString(instances.getAll().size())
                ))
                .appendNewline()
                .append(infoLine("Java:", System.getProperty("java.version", "unknown")))
                .appendNewline()
                .append(infoLine(
                        "OS:",
                        System.getProperty("os.name", "unknown") + " "
                                + System.getProperty("os.version", "unknown")
                ))
                .appendNewline()
                .append(infoLine(
                        "Proxy log mirror:",
                        outputConfig.mirrorToProxyConsole() ? "enabled" : "disabled"
                ))
                .appendNewline()
                .append(infoLine(
                        "Temporary logs:",
                        outputConfig.writeTemporaryFile()
                                ? "enabled (" + outputConfig.temporaryFileMaxKiB()
                                        + " KiB/server)"
                                : "disabled"
                ));
        for (HostCapability capability : hostCapabilities.capabilities()) {
            message.appendNewline()
                    .append(capabilityLine(capability));
        }
        source.sendMessage(message.build());
    }

    private static Component capabilityLine(HostCapability capability) {
        NamedTextColor color = switch (capability.status()) {
            case PASS -> NamedTextColor.GREEN;
            case WARNING -> NamedTextColor.YELLOW;
            case FAILURE -> NamedTextColor.RED;
        };
        return Component.text(" - ", NamedTextColor.GOLD)
                .append(Component.text(
                        capability.name() + ":", NamedTextColor.DARK_GRAY
                ))
                .append(Component.text(
                        " " + capability.status(), color
                ).hoverEvent(Component.text(capability.detail(), NamedTextColor.GRAY)));
    }

    private ManagedInstance resolveInstance(
            CommandSource source,
            String requestedId
    ) {
        Optional<String> currentServer = source instanceof Player player
                ? player.getCurrentServer()
                        .map(connection -> connection.getServerInfo().getName())
                : Optional.empty();
        InstanceTargetResolver.Resolution resolution = InstanceTargetResolver.resolve(
                requestedId,
                source instanceof Player,
                currentServer,
                id -> findInstanceOrNull(id) != null
        );
        if (resolution.error() != null) {
            source.sendMessage(CommandMessages.message(
                    resolution.error(), NamedTextColor.RED
            ));
            return null;
        }
        ManagedInstance instance = findInstanceOrNull(resolution.instanceId());
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
            if (!authorizer.canTargetOthers(source, "join")) {
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
        boolean force = arguments.length == 4;
        if (force) {
            if (!"--force".equalsIgnoreCase(arguments[3])) {
                source.sendMessage(CommandMessages.usage(
                        "/sls join player", "player", "--force"
                ));
                return;
            }
            if (!authorizer.canAdminister(source, "join")) {
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
                    joinService.joinPlayer(player, target, force);
            source.sendMessage(CommandMessages.prefix()
                    .append(Component.text(
                            force ? "Force joining " : "Joining ",
                            force ? NamedTextColor.YELLOW : NamedTextColor.GREEN
                    ))
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
            if (!authorizer.canTargetOthers(source, "dequeue")) {
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
        ManagedInstance instance = resolveInstance(source, arguments[1]);
        if (instance == null) {
            return;
        }
        if (lobbyProvider.isLobby(instance.id())) {
            source.sendMessage(CommandMessages.message(
                    "The active lobby cannot be stopped with /sls stop.",
                    NamedTextColor.RED
            ));
            return;
        }
        source.sendMessage(CommandMessages.message(
                "Moving players to the lobby before stopping " + instance.id() + "...",
                NamedTextColor.YELLOW
        ));
        lobbyProvider.evacuate(instance.id()).whenComplete((ignored, evacuationFailure) -> {
            if (evacuationFailure != null) {
                source.sendMessage(CommandMessages.message(
                        "Stop cancelled: " + rootMessage(evacuationFailure),
                        NamedTextColor.RED
                ));
                return;
            }
            stopInstance(source, instance.id());
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

    private void restart(CommandSource source, String[] arguments) {
        cyclePersistent(source, arguments, false);
    }

    private void reset(CommandSource source, String[] arguments) {
        cyclePersistent(source, arguments, true);
    }

    private void cyclePersistent(
            CommandSource source,
            String[] arguments,
            boolean reset
    ) {
        String operation = reset ? "reset" : "restart";
        if (!requireAdmin(
                source,
                operation,
                operation + " persistent servers"
        )) {
            return;
        }
        if (arguments.length != 2) {
            source.sendMessage(CommandMessages.usage(
                    "/sls " + operation,
                    "server"
            ));
            return;
        }

        String instanceId = arguments[1];
        ManagedInstance active = null;
        if ("this".equalsIgnoreCase(instanceId)) {
            active = resolveInstance(source, instanceId);
            if (active == null) {
                return;
            }
            instanceId = active.id();
        } else {
            active = findInstanceOrNull(instanceId);
        }
        if (active != null && lobbyProvider.isLobby(active.id())) {
            source.sendMessage(CommandMessages.message(
                    "The active lobby cannot be " + operation + " manually.",
                    NamedTextColor.RED
            ));
            return;
        }

        String targetId = instanceId;
        Runnable beginRestart = () -> {
            try {
                source.sendMessage(CommandMessages.message(
                        (reset ? "Resetting" : "Restarting")
                                + " persistent server " + targetId + "...",
                        NamedTextColor.YELLOW
                ));
                CompletableFuture<ManagedInstance> cycle = reset
                        ? instances.reset(targetId)
                        : instances.restart(targetId);
                cycle.whenComplete((restarted, failure) -> {
                    if (failure != null) {
                        source.sendMessage(CommandMessages.message(
                                capitalize(operation) + " failed: "
                                        + rootMessage(failure),
                                NamedTextColor.RED
                        ));
                        return;
                    }
                    restarted.readyFuture().whenComplete((ready, readyFailure) -> {
                        if (readyFailure == null) {
                            source.sendMessage(CommandMessages.message(
                                    "Server " + ready.id() + " "
                                            + (reset ? "reset" : "restarted") + ".",
                                    NamedTextColor.GREEN
                            ));
                        } else {
                            source.sendMessage(CommandMessages.message(
                                    capitalize(operation) + " failed: "
                                            + rootMessage(readyFailure),
                                    NamedTextColor.RED
                            ));
                        }
                    });
                });
            } catch (InstanceOperationException exception) {
                source.sendMessage(CommandMessages.message(
                        exception.getMessage(), NamedTextColor.RED
                ));
            }
        };

        if (active == null) {
            beginRestart.run();
            return;
        }
        source.sendMessage(CommandMessages.message(
                "Moving players to the lobby before "
                        + (reset ? "resetting " : "restarting ")
                        + targetId + "...",
                NamedTextColor.YELLOW
        ));
        lobbyProvider.evacuate(targetId).whenComplete((ignored, failure) -> {
            if (failure == null) {
                beginRestart.run();
            } else {
                source.sendMessage(CommandMessages.message(
                        capitalize(operation) + " cancelled: " + rootMessage(failure),
                        NamedTextColor.RED
                ));
            }
        });
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
        ManagedInstance instance = resolveInstance(source, arguments[1]);
        if (instance == null) {
            return;
        }
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
        if (authorizer.canAdminister(source, permission)) {
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
                        "Process:",
                        instance.processId().isPresent()
                                ? Long.toString(instance.processId().getAsLong())
                                : "not started"
                ))
                .appendNewline()
                .append(infoLine(
                        "Mem:",
                        instance.blueprint().memoryLimitMiB() + " MiB limit"
                ))
                .appendNewline()
                .append(infoLine("Uptime:", formatDuration(uptimeSeconds)))
                .appendNewline()
                .append(infoLine(
                        "Queued:",
                        joinService.hasPendingJoin(instance.id()) ? "yes" : "no"
                ))
                .appendNewline()
                .append(infoLine(
                        "Logs:",
                        instance.retainedLogLines() + "/"
                                + instance.logRetentionCapacity() + " lines"
                ))
                .appendNewline()
                .append(infoLine(
                        "Log file:",
                        instance.temporaryLogPath()
                                .map(java.nio.file.Path::toString)
                                .orElse("disabled")
                ))
                .appendNewline()
                .append(infoLine("Directory:", instance.directory().toString()))
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
        source.sendMessage(CommandMessages.prefix()
                .append(Component.text("Invalid number " + value, NamedTextColor.RED)));
    }

    private static Component logPaginationFooter(
            String serverId,
            int page,
            int linesPerPage,
            int totalPages,
            int totalLines,
            int retentionCapacity
    ) {
        Component previous = page > 1
                ? logPageArrow(
                        "<<",
                        serverId,
                        page - 1,
                        linesPerPage,
                        "View newer logs"
                )
                : Component.text("<<", NamedTextColor.DARK_GRAY);
        Component next = page < totalPages
                ? logPageArrow(
                        ">>",
                        serverId,
                        page + 1,
                        linesPerPage,
                        "View older logs"
                )
                : Component.text(">>", NamedTextColor.DARK_GRAY);
        Component pageLabel = Component.text(
                "PAGE " + page + "/" + totalPages,
                NamedTextColor.GOLD
        ).hoverEvent(Component.text(
                "Retained lines: " + totalLines + "/" + retentionCapacity
                        + "\nLines per page: " + linesPerPage,
                NamedTextColor.GRAY
        ));
        return previous
                .append(Component.text(" -------------- ", NamedTextColor.DARK_GRAY)
                        .decorate(TextDecoration.STRIKETHROUGH, TextDecoration.BOLD))
                .append(pageLabel)
                .append(Component.text(" -------------- ", NamedTextColor.DARK_GRAY)
                        .decorate(TextDecoration.STRIKETHROUGH, TextDecoration.BOLD))
                .append(next);
    }

    private static Component logPageArrow(
            String arrow,
            String serverId,
            int page,
            int linesPerPage,
            String hover
    ) {
        return Component.text(arrow, NamedTextColor.AQUA)
                .clickEvent(ClickEvent.runCommand(
                        "/sls logs " + serverId + " " + page + " " + linesPerPage
                ))
                .hoverEvent(Component.text(hover, NamedTextColor.GRAY));
    }

    private List<String> instanceIds() {
        return instances.getAll().stream().map(ManagedInstance::id).sorted().toList();
    }

    private List<String> persistentInstanceIds() {
        Set<String> ids = new LinkedHashSet<>(instanceIds());
        ids.addAll(instances.persistentInstanceIds());
        return sorted(ids);
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

    private static String capitalize(String value) {
        return Character.toUpperCase(value.charAt(0)) + value.substring(1);
    }

    private static Throwable rootCause(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current;
    }
}
