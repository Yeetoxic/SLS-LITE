package net.slimelabs.slslite.command.handler;

import com.velocitypowered.api.command.CommandSource;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.slimelabs.slslite.blueprint.Blueprint;
import net.slimelabs.slslite.blueprint.BlueprintRepository;
import net.slimelabs.slslite.command.CommandAuthorizer;
import net.slimelabs.slslite.command.CommandMessages;
import net.slimelabs.slslite.install.InstallationSnapshot;
import net.slimelabs.slslite.install.SoftwareInstallationService;
import net.slimelabs.slslite.software.SoftwareProfileRepository;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Owns the complete {@code /sls install} inspection and completion surface.
 */
public final class InstallationCommandHandler {

    private final BlueprintRepository blueprints;
    private final SoftwareProfileRepository softwareProfiles;
    private final InstallationStatusSource installations;
    private final CommandAuthorizer authorizer;

    public InstallationCommandHandler(
            BlueprintRepository blueprints,
            SoftwareProfileRepository softwareProfiles,
            SoftwareInstallationService installations,
            CommandAuthorizer authorizer
    ) {
        this(
                blueprints,
                softwareProfiles,
                installations == null ? null : new InstallationStatusSource() {
                    @Override
                    public List<InstallationSnapshot> snapshots() {
                        return installations.snapshots();
                    }

                    @Override
                    public InstallationSnapshot snapshot(
                            String softwareId,
                            String version
                    ) {
                        return installations.snapshot(softwareId, version);
                    }
                },
                authorizer
        );
    }

    InstallationCommandHandler(
            BlueprintRepository blueprints,
            SoftwareProfileRepository softwareProfiles,
            InstallationStatusSource installations,
            CommandAuthorizer authorizer
    ) {
        this.blueprints = blueprints;
        this.softwareProfiles = softwareProfiles;
        this.installations = installations;
        this.authorizer = authorizer;
    }

    public void execute(CommandSource source, String[] arguments) {
        if (!authorizer.canAdminister(source, "install")) {
            source.sendMessage(CommandMessages.message(
                    "You do not have permission to inspect software installation.",
                    NamedTextColor.RED
            ));
            return;
        }
        if (installations == null) {
            source.sendMessage(CommandMessages.prefix()
                    .append(Component.text(
                            "/sls install",
                            NamedTextColor.GOLD
                    ))
                    .append(Component.text(
                            " is not available in this SLS-LITE build yet.",
                            NamedTextColor.GRAY
                    )));
            return;
        }
        if (arguments.length == 2
                && "info".equalsIgnoreCase(arguments[1])) {
            sendInfo(source);
            return;
        }
        if (arguments.length == 4
                && "logs".equalsIgnoreCase(arguments[1])) {
            sendLogs(source, arguments[2], arguments[3]);
            return;
        }
        source.sendMessage(CommandMessages.usage(
                "/sls install", "info", "logs <software> <version>"
        ));
    }

    public List<String> suggestions(CommandSource source, String[] arguments) {
        if (!authorizer.canAdminister(source, "install")) {
            return List.of();
        }
        if (arguments.length == 2) {
            return List.of("info", "logs");
        }
        if (arguments.length == 3
                && "logs".equalsIgnoreCase(arguments[1])) {
            return softwareIds();
        }
        if (arguments.length == 4
                && "logs".equalsIgnoreCase(arguments[1])) {
            return versions(arguments[2]);
        }
        return List.of();
    }

    private void sendInfo(CommandSource source) {
        List<InstallationSnapshot> snapshots = installations.snapshots();
        if (snapshots.isEmpty()) {
            source.sendMessage(CommandMessages.message(
                    "No software installation activity.", NamedTextColor.GRAY
            ));
            return;
        }
        source.sendMessage(CommandMessages.message(
                "Software installations:", NamedTextColor.DARK_AQUA
        ));
        for (InstallationSnapshot snapshot : snapshots) {
            NamedTextColor color = switch (snapshot.state()) {
                case INSTALLING -> NamedTextColor.YELLOW;
                case READY -> NamedTextColor.GREEN;
                case FAILED -> NamedTextColor.RED;
            };
            source.sendMessage(CommandMessages.message(
                    snapshot.key() + " - " + snapshot.state()
                            + " - " + snapshot.detail(),
                    color
            ));
        }
    }

    private void sendLogs(
            CommandSource source,
            String softwareId,
            String version
    ) {
        InstallationSnapshot snapshot = installations.snapshot(softwareId, version);
        if (snapshot == null) {
            source.sendMessage(CommandMessages.message(
                    "No installation record for " + softwareId + ":" + version + ".",
                    NamedTextColor.RED
            ));
            return;
        }
        source.sendMessage(CommandMessages.message(
                "Installation log for " + snapshot.key() + ":",
                NamedTextColor.DARK_AQUA
        ));
        int first = Math.max(0, snapshot.logs().size() - 10);
        if (first > 0) {
            source.sendMessage(Component.text(
                    "Showing the latest 10 of " + snapshot.logs().size()
                            + " retained lines.",
                    NamedTextColor.DARK_GRAY
            ));
        }
        snapshot.logs().subList(first, snapshot.logs().size())
                .forEach(line -> source.sendMessage(
                        Component.text(line, NamedTextColor.GRAY)
                ));
    }

    private List<String> softwareIds() {
        Set<String> ids = new LinkedHashSet<>();
        softwareProfiles.getAll().stream()
                .map(profile -> profile.id())
                .forEach(ids::add);
        if (installations != null) {
            installations.snapshots().stream()
                    .map(snapshot -> snapshot.key().softwareId())
                    .forEach(ids::add);
        }
        return ids.stream().sorted().toList();
    }

    private List<String> versions(String softwareId) {
        Set<String> versions = new LinkedHashSet<>();
        blueprints.getAll().stream()
                .filter(blueprint -> blueprint.software().equals(softwareId))
                .map(Blueprint::version)
                .forEach(versions::add);
        if (installations != null) {
            installations.snapshots().stream()
                    .filter(snapshot -> snapshot.key().softwareId().equals(softwareId))
                    .map(snapshot -> snapshot.key().version())
                    .forEach(versions::add);
        }
        return versions.stream().sorted().toList();
    }

    interface InstallationStatusSource {

        List<InstallationSnapshot> snapshots();

        InstallationSnapshot snapshot(String softwareId, String version);
    }
}
