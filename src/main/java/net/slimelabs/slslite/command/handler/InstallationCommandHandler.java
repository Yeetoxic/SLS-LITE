package net.slimelabs.slslite.command.handler;

import com.velocitypowered.api.command.CommandSource;
import java.time.Duration;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.slimelabs.slslite.blueprint.Blueprint;
import net.slimelabs.slslite.blueprint.BlueprintRepository;
import net.slimelabs.slslite.command.CommandAuthorizer;
import net.slimelabs.slslite.command.CommandMessages;
import net.slimelabs.slslite.install.InstallationKey;
import net.slimelabs.slslite.install.InstallationSnapshot;
import net.slimelabs.slslite.install.SoftwareCacheCleanupReport;
import net.slimelabs.slslite.install.SoftwareInstallationService;
import net.slimelabs.slslite.instance.InstanceOperationException;
import net.slimelabs.slslite.instance.ServerController;
import net.slimelabs.slslite.software.SoftwareProfile;
import net.slimelabs.slslite.software.SoftwareProfileRepository;

/**
 * Owns the complete {@code /sls install} inspection and completion surface.
 */
public final class InstallationCommandHandler {

  private final BlueprintRepository blueprints;
  private final SoftwareProfileRepository softwareProfiles;
  private final InstallationStatusSource installations;
  private final CommandAuthorizer authorizer;
  private final ServerController instances;

  public InstallationCommandHandler(
      BlueprintRepository blueprints,
      SoftwareProfileRepository softwareProfiles,
      SoftwareInstallationService installations,
      CommandAuthorizer authorizer,
      ServerController instances) {
    this(
        blueprints,
        softwareProfiles,
        installations == null
            ? null
            : new InstallationStatusSource() {
              @Override
              public List<InstallationSnapshot> snapshots() {
                return installations.snapshots();
              }

              @Override
              public InstallationSnapshot snapshot(String softwareId, String version) {
                return installations.snapshot(softwareId, version);
              }

              @Override
              public SoftwareCacheCleanupReport cleanup(
                  Duration minimumAge,
                  boolean dryRun,
                  boolean confirmed,
                  Set<InstallationKey> protectedKeys)
                  throws Exception {
                return installations.cleanupCache(minimumAge, dryRun, confirmed, protectedKeys);
              }

              @Override
              public java.util.concurrent.CompletableFuture<java.nio.file.Path> warmup(
                  SoftwareProfile profile, String version) {
                return installations.ensureInstalled(profile, version);
              }
            },
        authorizer,
        instances);
  }

  public InstallationCommandHandler(
      BlueprintRepository blueprints,
      SoftwareProfileRepository softwareProfiles,
      SoftwareInstallationService installations,
      CommandAuthorizer authorizer) {
    this(blueprints, softwareProfiles, installations, authorizer, null);
  }

  InstallationCommandHandler(
      BlueprintRepository blueprints,
      SoftwareProfileRepository softwareProfiles,
      InstallationStatusSource installations,
      CommandAuthorizer authorizer) {
    this(blueprints, softwareProfiles, installations, authorizer, null);
  }

  InstallationCommandHandler(
      BlueprintRepository blueprints,
      SoftwareProfileRepository softwareProfiles,
      InstallationStatusSource installations,
      CommandAuthorizer authorizer,
      ServerController instances) {
    this.blueprints = blueprints;
    this.softwareProfiles = softwareProfiles;
    this.installations = installations;
    this.authorizer = authorizer;
    this.instances = instances;
  }

  public void execute(CommandSource source, String[] arguments) {
    if (!authorizer.canAdminister(source, "install")) {
      source.sendMessage(
          CommandMessages.message(
              "You do not have permission to inspect software installation.", NamedTextColor.RED));
      return;
    }
    if (installations == null) {
      source.sendMessage(
          CommandMessages.prefix()
              .append(Component.text("/sls install", NamedTextColor.GOLD))
              .append(
                  Component.text(
                      " is not available in this SLS-LITE build yet.", NamedTextColor.GRAY)));
      return;
    }
    if (arguments.length == 2 && "info".equalsIgnoreCase(arguments[1])) {
      sendInfo(source);
      return;
    }
    if (arguments.length == 4 && "logs".equalsIgnoreCase(arguments[1])) {
      sendLogs(source, arguments[2], arguments[3]);
      return;
    }
    if ((arguments.length == 3 || arguments.length == 4)
        && "cleanup".equalsIgnoreCase(arguments[1])) {
      cleanup(source, arguments);
      return;
    }
    if (arguments.length == 4 && "warmup".equalsIgnoreCase(arguments[1])) {
      warmup(source, arguments[2], arguments[3]);
      return;
    }
    source.sendMessage(
        CommandMessages.usage(
            "/sls install",
            "info",
            "logs <software> <version>",
            "warmup <software> <version>",
            "cleanup <minimum-age-hours> [--confirm]"));
  }

  public List<String> suggestions(CommandSource source, String[] arguments) {
    if (!authorizer.canAdminister(source, "install")) {
      return List.of();
    }
    if (arguments.length == 2) {
      return List.of("info", "logs", "warmup", "cleanup");
    }
    if (arguments.length == 3
        && ("logs".equalsIgnoreCase(arguments[1]) || "warmup".equalsIgnoreCase(arguments[1]))) {
      return softwareIds();
    }
    if (arguments.length == 4
        && ("logs".equalsIgnoreCase(arguments[1]) || "warmup".equalsIgnoreCase(arguments[1]))) {
      return versions(arguments[2]);
    }
    if (arguments.length == 4 && "cleanup".equalsIgnoreCase(arguments[1])) {
      return List.of("--confirm");
    }
    return List.of();
  }

  private void warmup(CommandSource source, String softwareId, String version) {
    SoftwareProfile profile = softwareProfiles.get(softwareId).orElse(null);
    if (profile == null) {
      source.sendMessage(
          CommandMessages.message(
              "Unknown software profile " + softwareId + ".", NamedTextColor.RED));
      return;
    }
    source.sendMessage(
        CommandMessages.message(
            "Warming verified software cache " + softwareId + ":" + version + "...",
            NamedTextColor.YELLOW));
    installations
        .warmup(profile, version)
        .whenComplete(
            (path, failure) -> {
              if (failure == null) {
                source.sendMessage(
                    CommandMessages.message(
                        "Software cache ready: " + softwareId + ":" + version + ".",
                        NamedTextColor.GREEN));
              } else {
                source.sendMessage(
                    CommandMessages.message(
                        "Software warmup failed: " + rootMessage(failure), NamedTextColor.RED));
              }
            });
  }

  private static String rootMessage(Throwable failure) {
    Throwable current = failure;
    while (current.getCause() != null) {
      current = current.getCause();
    }
    return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
  }

  private void cleanup(CommandSource source, String[] arguments) {
    if (instances == null) {
      source.sendMessage(
          CommandMessages.message(
              "Software cache cleanup is unavailable without instance ownership data.",
              NamedTextColor.RED));
      return;
    }
    long hours;
    try {
      if (!arguments[2].matches("[0-9]+")) {
        throw new NumberFormatException();
      }
      hours = Long.parseLong(arguments[2]);
    } catch (NumberFormatException exception) {
      source.sendMessage(
          CommandMessages.message(
              "Minimum age must be a whole number of hours.", NamedTextColor.RED));
      return;
    }
    boolean confirmed = arguments.length == 4 && "--confirm".equalsIgnoreCase(arguments[3]);
    if (arguments.length == 4 && !confirmed) {
      source.sendMessage(
          CommandMessages.usage("/sls install", "cleanup <minimum-age-hours> [--confirm]"));
      return;
    }
    Set<InstallationKey> protectedKeys = new java.util.HashSet<>();
    blueprints.getAll().stream()
        .map(blueprint -> new InstallationKey(blueprint.software(), blueprint.version()))
        .forEach(protectedKeys::add);
    try {
      protectedKeys.addAll(instances.protectedSoftwareVersions());
      SoftwareCacheCleanupReport report =
          installations.cleanup(
              Duration.ofHours(hours), !confirmed, confirmed, Set.copyOf(protectedKeys));
      source.sendMessage(
          CommandMessages.message(
              (report.dryRun() ? "Software cleanup dry run" : "Software cleanup complete")
                  + ": "
                  + report.eligible().size()
                  + " eligible, "
                  + report.removed().size()
                  + " removed, "
                  + report.protectedCount()
                  + " protected, "
                  + report.tooNewCount()
                  + " too new.",
              report.removed().isEmpty() ? NamedTextColor.YELLOW : NamedTextColor.GREEN));
      if (report.dryRun() && !report.eligible().isEmpty()) {
        source.sendMessage(
            CommandMessages.message(
                "Review the candidates, then repeat with --confirm to delete them.",
                NamedTextColor.GRAY));
      }
      report
          .eligible()
          .forEach(
              entry ->
                  source.sendMessage(
                      CommandMessages.message(
                          (report.dryRun() ? "Would remove " : "Removed ") + entry.key(),
                          NamedTextColor.GRAY)));
    } catch (InstanceOperationException exception) {
      source.sendMessage(
          CommandMessages.message(
              "Unable to determine protected software: " + exception.getMessage(),
              NamedTextColor.RED));
    } catch (Exception exception) {
      source.sendMessage(
          CommandMessages.message(
              "Software cleanup failed: " + exception.getMessage(), NamedTextColor.RED));
    }
  }

  private void sendInfo(CommandSource source) {
    List<InstallationSnapshot> snapshots = installations.snapshots();
    if (snapshots.isEmpty()) {
      source.sendMessage(
          CommandMessages.message("No software installation activity.", NamedTextColor.GRAY));
      return;
    }
    source.sendMessage(
        CommandMessages.message("Software installations:", NamedTextColor.DARK_AQUA));
    for (InstallationSnapshot snapshot : snapshots) {
      NamedTextColor color =
          switch (snapshot.state()) {
            case INSTALLING -> NamedTextColor.YELLOW;
            case READY -> NamedTextColor.GREEN;
            case FAILED -> NamedTextColor.RED;
          };
      source.sendMessage(
          CommandMessages.message(
              snapshot.key() + " - " + snapshot.state() + " - " + snapshot.detail(), color));
    }
  }

  private void sendLogs(CommandSource source, String softwareId, String version) {
    InstallationSnapshot snapshot = installations.snapshot(softwareId, version);
    if (snapshot == null) {
      source.sendMessage(
          CommandMessages.message(
              "No installation record for " + softwareId + ":" + version + ".",
              NamedTextColor.RED));
      return;
    }
    source.sendMessage(
        CommandMessages.message(
            "Installation log for " + snapshot.key() + ":", NamedTextColor.DARK_AQUA));
    int first = Math.max(0, snapshot.logs().size() - 10);
    if (first > 0) {
      source.sendMessage(
          Component.text(
              "Showing the latest 10 of " + snapshot.logs().size() + " retained lines.",
              NamedTextColor.DARK_GRAY));
    }
    snapshot
        .logs()
        .subList(first, snapshot.logs().size())
        .forEach(line -> source.sendMessage(Component.text(line, NamedTextColor.GRAY)));
  }

  private List<String> softwareIds() {
    Set<String> ids = new LinkedHashSet<>();
    softwareProfiles.getAll().stream().map(profile -> profile.id()).forEach(ids::add);
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

    default SoftwareCacheCleanupReport cleanup(
        Duration minimumAge, boolean dryRun, boolean confirmed, Set<InstallationKey> protectedKeys)
        throws Exception {
      throw new UnsupportedOperationException("Software cleanup is unavailable");
    }

    default java.util.concurrent.CompletableFuture<java.nio.file.Path> warmup(
        SoftwareProfile profile, String version) {
      return java.util.concurrent.CompletableFuture.failedFuture(
          new UnsupportedOperationException("Software warmup is unavailable"));
    }
  }
}
