package net.slimelabs.slslite.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.slimelabs.slslite.BuildInfo;
import net.slimelabs.slslite.blueprint.Blueprint;
import net.slimelabs.slslite.blueprint.BlueprintRepository;
import net.slimelabs.slslite.resource.ResourceBudget;
import net.slimelabs.slslite.software.SoftwareProfileRepository;
import org.slf4j.Logger;

import java.util.List;
import java.util.concurrent.CompletableFuture;

public final class SLSCommand implements SimpleCommand {

    private static final String ADMIN_PERMISSION = "sls.command.admin";

    private final BlueprintRepository blueprints;
    private final SoftwareProfileRepository softwareProfiles;
    private final ResourceBudget resourceBudget;
    private final Logger logger;

    public SLSCommand(
            BlueprintRepository blueprints,
            SoftwareProfileRepository softwareProfiles,
            ResourceBudget resourceBudget,
            Logger logger
    ) {
        this.blueprints = blueprints;
        this.softwareProfiles = softwareProfiles;
        this.resourceBudget = resourceBudget;
        this.logger = logger;
    }

    @Override
    public void execute(Invocation invocation) {
        String[] arguments = invocation.arguments();
        if (arguments.length == 0) {
            sendSummary(invocation.source());
            return;
        }

        switch (arguments[0].toLowerCase(java.util.Locale.ROOT)) {
            case "blueprints" -> sendBlueprints(invocation.source());
            case "reload" -> reload(invocation.source());
            case "version" -> sendVersion(invocation.source());
            default -> invocation.source().sendMessage(
                    Component.text("Unknown subcommand. Use /sls, /sls blueprints, or /sls version.")
                            .color(NamedTextColor.RED)
            );
        }
    }

    @Override
    public CompletableFuture<List<String>> suggestAsync(Invocation invocation) {
        if (invocation.arguments().length <= 1) {
            List<String> suggestions = invocation.source().hasPermission(ADMIN_PERMISSION)
                    ? List.of("blueprints", "reload", "version")
                    : List.of("blueprints", "version");
            return CompletableFuture.completedFuture(suggestions);
        }
        return CompletableFuture.completedFuture(List.of());
    }

    private void sendSummary(CommandSource source) {
        source.sendMessage(Component.text("SLS-LITE")
                .color(NamedTextColor.GREEN)
                .append(Component.text(" - standalone local server management for Velocity")
                        .color(NamedTextColor.GRAY)));
        source.sendMessage(Component.text("Loaded blueprints: " + blueprints.getAll().size())
                .color(NamedTextColor.GRAY));
        source.sendMessage(Component.text(
                "Software profiles: " + softwareProfiles.getAll().size()
                        + " | Managed memory: " + resourceBudget.totalMemoryMiB() + " MiB"
        ).color(NamedTextColor.GRAY));
    }

    private void sendBlueprints(CommandSource source) {
        if (blueprints.getAll().isEmpty()) {
            source.sendMessage(Component.text("No blueprints are loaded.")
                    .color(NamedTextColor.YELLOW));
            return;
        }

        source.sendMessage(Component.text("Blueprints").color(NamedTextColor.GREEN));
        for (Blueprint blueprint : blueprints.getAll()) {
            source.sendMessage(Component.text("- " + blueprint.id())
                    .color(NamedTextColor.GOLD)
                    .append(Component.text(" (" + blueprint.software() + " "
                                    + blueprint.version() + ", " + blueprint.memoryLimitMiB() + " MiB)")
                            .color(NamedTextColor.GRAY)));
        }
    }

    private void reload(CommandSource source) {
        if (!source.hasPermission(ADMIN_PERMISSION)) {
            source.sendMessage(Component.text("You do not have permission to reload SLS-LITE.")
                    .color(NamedTextColor.RED));
            return;
        }

        try {
            blueprints.reload();
            source.sendMessage(Component.text(
                    "Reloaded " + blueprints.getAll().size() + " blueprint(s)."
            ).color(NamedTextColor.GREEN));
        } catch (Exception exception) {
            logger.error("Unable to reload SLS-LITE blueprints", exception);
            source.sendMessage(Component.text("Blueprint reload failed: " + exception.getMessage())
                    .color(NamedTextColor.RED));
        }
    }

    private void sendVersion(CommandSource source) {
        source.sendMessage(Component.text("SLS-LITE " + BuildInfo.VERSION)
                .color(NamedTextColor.GREEN));
    }
}
