package net.slimelabs.slslite.command.handler;

import com.velocitypowered.api.command.CommandSource;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.slimelabs.slslite.BuildInfo;
import net.slimelabs.slslite.api.ExtensionDiagnosticSeverity;
import net.slimelabs.slslite.api.ExtensionDiagnosticView;
import net.slimelabs.slslite.blueprint.BlueprintRepository;
import net.slimelabs.slslite.command.CommandAuthorizer;
import net.slimelabs.slslite.command.CommandMessages;
import net.slimelabs.slslite.config.ManagedOutputConfig;
import net.slimelabs.slslite.host.HostCapability;
import net.slimelabs.slslite.host.HostCapabilityReport;
import net.slimelabs.slslite.instance.ServerController;
import net.slimelabs.slslite.lobby.LobbyProvider;
import net.slimelabs.slslite.lobby.SLSLimboDiagnostics;
import net.slimelabs.slslite.log.CorrelationIds;
import net.slimelabs.slslite.log.SLSDetailLog;
import net.slimelabs.slslite.process.ProcessSupervisor;
import net.slimelabs.slslite.resource.ResourceBudget;
import net.slimelabs.slslite.software.SoftwareProfileRepository;
import net.slimelabs.slslite.velocity.LocalJoinService;

final class HostInspectionHandler {

  private final BlueprintRepository blueprints;
  private final SoftwareProfileRepository softwareProfiles;
  private final ResourceBudget resourceBudget;
  private final ServerController instances;
  private final LocalJoinService joinService;
  private final LobbyProvider lobbyProvider;
  private final ProcessSupervisor processSupervisor;
  private final ManagedOutputConfig outputConfig;
  private final HostCapabilityReport hostCapabilities;
  private final CommandAuthorizer authorizer;
  private final SLSDetailLog detailLog;
  private java.util.function.Supplier<java.util.List<ExtensionDiagnosticView>>
      extensionDiagnostics = java.util.List::of;

  HostInspectionHandler(
      BlueprintRepository blueprints,
      SoftwareProfileRepository softwareProfiles,
      ResourceBudget resourceBudget,
      ServerController instances,
      LocalJoinService joinService,
      LobbyProvider lobbyProvider,
      ProcessSupervisor processSupervisor,
      ManagedOutputConfig outputConfig,
      HostCapabilityReport hostCapabilities,
      CommandAuthorizer authorizer,
      SLSDetailLog detailLog) {
    this.blueprints = blueprints;
    this.softwareProfiles = softwareProfiles;
    this.resourceBudget = resourceBudget;
    this.instances = instances;
    this.joinService = joinService;
    this.lobbyProvider = lobbyProvider;
    this.processSupervisor = processSupervisor;
    this.outputConfig = outputConfig;
    this.hostCapabilities = hostCapabilities;
    this.authorizer = authorizer;
    this.detailLog = java.util.Objects.requireNonNull(detailLog, "detailLog");
  }

  void installExtensionDiagnostics(
      java.util.function.Supplier<java.util.List<ExtensionDiagnosticView>> diagnostics) {
    this.extensionDiagnostics = java.util.Objects.requireNonNull(diagnostics, "diagnostics");
  }

  void summary(CommandSource source) {
    TextComponent.Builder message =
        Component.text()
            .append(Component.text("Info", NamedTextColor.DARK_AQUA))
            .append(Component.text(" (SLS-LITE):", NamedTextColor.DARK_GRAY))
            .appendNewline()
            .append(infoLine("Registries:", Integer.toString(blueprints.getTypes().size())))
            .appendNewline()
            .append(infoLine("Blueprints:", Integer.toString(blueprints.getAll().size())))
            .appendNewline()
            .append(
                infoLine("Software profiles:", Integer.toString(softwareProfiles.getAll().size())))
            .appendNewline()
            .append(infoLine("Active servers:", Integer.toString(instances.getAll().size())))
            .appendNewline()
            .append(infoLine("Lobby status:", lobbyProvider.status().name()))
            .appendNewline()
            .append(infoLine("SLS-Limbo:", limboSummary()))
            .appendNewline()
            .append(
                infoLine("Queued players:", Integer.toString(joinService.queuedPlayers().size())))
            .appendNewline()
            .append(
                infoLine(
                    "Managed memory:",
                    resourceBudget.reservedMemoryMiB()
                        + "/"
                        + resourceBudget.totalMemoryMiB()
                        + " MiB"));
    source.sendMessage(message.build());
  }

  void system(CommandSource source, String[] arguments) {
    if (!requireAdmin(source)) {
      return;
    }
    if (arguments.length != 1) {
      source.sendMessage(CommandMessages.usage("/sls system"));
      return;
    }

    Runtime runtime = Runtime.getRuntime();
    long usedJvmMiB = (runtime.totalMemory() - runtime.freeMemory()) / (1024L * 1024L);
    long maxJvmMiB = runtime.maxMemory() / (1024L * 1024L);
    TextComponent.Builder message =
        Component.text()
            .append(
                Component.text("----------------", NamedTextColor.DARK_GRAY)
                    .decorate(TextDecoration.STRIKETHROUGH, TextDecoration.BOLD))
            .append(
                Component.text(" INFO ", NamedTextColor.DARK_AQUA)
                    .decoration(TextDecoration.STRIKETHROUGH, false))
            .append(
                Component.text("----------------", NamedTextColor.DARK_GRAY)
                    .decorate(TextDecoration.STRIKETHROUGH, TextDecoration.BOLD))
            .appendNewline()
            .append(infoLine("Version:", BuildInfo.VERSION))
            .appendNewline()
            .append(infoLine("Architecture:", System.getProperty("os.arch", "unknown")))
            .appendNewline()
            .append(infoLine("CPU Threads:", Integer.toString(runtime.availableProcessors())))
            .appendNewline()
            .append(infoLine("Velocity JVM:", usedJvmMiB + "/" + maxJvmMiB + " MiB"))
            .appendNewline()
            .append(
                infoLine(
                    "Managed memory:",
                    resourceBudget.reservedMemoryMiB()
                        + "/"
                        + resourceBudget.totalMemoryMiB()
                        + " MiB"))
            .appendNewline()
            .append(
                infoLine(
                    "Managed processes:",
                    processSupervisor.activeProcessCount()
                        + "/"
                        + processSupervisor.maximumProcesses()))
            .appendNewline()
            .append(infoLine("Managed servers:", Integer.toString(instances.getAll().size())))
            .appendNewline()
            .append(
                infoLine(
                    "Safe lobby:", lobbyProvider.bothUnavailable() ? "UNAVAILABLE" : "available"))
            .appendNewline()
            .append(infoLine("SLS-Limbo:", limboSummary()))
            .appendNewline()
            .append(infoLine("Java:", System.getProperty("java.version", "unknown")))
            .appendNewline()
            .append(
                infoLine(
                    "OS:",
                    System.getProperty("os.name", "unknown")
                        + " "
                        + System.getProperty("os.version", "unknown")))
            .appendNewline()
            .append(
                infoLine(
                    "Proxy log mirror:",
                    outputConfig.mirrorToProxyConsole() ? "enabled" : "disabled"))
            .appendNewline()
            .append(
                infoLine(
                    "Temporary logs:",
                    outputConfig.writeTemporaryFile()
                        ? "enabled (" + outputConfig.temporaryFileMaxKiB() + " KiB/server)"
                        : "disabled"));
    lobbyProvider
        .limboDiagnostics()
        .flatMap(SLSLimboDiagnostics::lastFailure)
        .ifPresent(
            failure -> message.appendNewline().append(infoLine("SLS-Limbo failure:", failure)));
    for (HostCapability capability : hostCapabilities.capabilities()) {
      message.appendNewline().append(capabilityLine(capability));
    }
    java.util.List<ExtensionDiagnosticView> extensionViews = extensionDiagnostics.get();
    long information = findings(extensionViews, ExtensionDiagnosticSeverity.INFO);
    long warnings = findings(extensionViews, ExtensionDiagnosticSeverity.WARNING);
    long errors = findings(extensionViews, ExtensionDiagnosticSeverity.ERROR);
    message
        .appendNewline()
        .append(
            infoLine(
                "Extension diagnostics:",
                extensionViews.size()
                    + " extension(s), info="
                    + information
                    + ", warnings="
                    + warnings
                    + ", errors="
                    + errors));
    String correlationId = CorrelationIds.next("system");
    extensionViews.forEach(
        view ->
            view.findings()
                .forEach(
                    finding ->
                        detailLog.normal(
                            correlationId,
                            "extension-diagnostic",
                            "{} {} {}: {}",
                            view.namespace(),
                            finding.severity(),
                            finding.code(),
                            finding.message())));
    source.sendMessage(message.build());
  }

  private static long findings(
      java.util.List<ExtensionDiagnosticView> views, ExtensionDiagnosticSeverity severity) {
    return views.stream()
        .flatMap(view -> view.findings().stream())
        .filter(finding -> finding.severity() == severity)
        .count();
  }

  private boolean requireAdmin(CommandSource source) {
    if (authorizer.canAdminister(source, "system")) {
      return true;
    }
    source.sendMessage(
        CommandMessages.message(
            "You do not have permission to view local host information.", NamedTextColor.RED));
    return false;
  }

  private String limboSummary() {
    return lobbyProvider
        .limboDiagnostics()
        .map(
            diagnostics -> {
              String port =
                  diagnostics.port().isPresent()
                      ? Integer.toString(diagnostics.port().getAsInt())
                      : "unassigned";
              return diagnostics.status().name()
                  + ", "
                  + diagnostics.memoryMiB()
                  + " MiB"
                  + ", port "
                  + port
                  + ", protocol "
                  + (diagnostics.advertisedProtocol() == -1
                      ? "native"
                      : diagnostics.advertisedProtocol())
                  + ", recovery "
                  + diagnostics.recoveryAttempts()
                  + "/"
                  + diagnostics.maxRecoveryAttempts();
            })
        .orElse("not configured");
  }

  private static Component infoLine(String label, String value) {
    return Component.text(" - ", NamedTextColor.GOLD)
        .append(Component.text(label, NamedTextColor.DARK_GRAY))
        .append(Component.text(" " + value, NamedTextColor.BLUE));
  }

  static Component capabilityLine(HostCapability capability) {
    NamedTextColor color =
        switch (capability.status()) {
          case PASS -> NamedTextColor.GREEN;
          case INFO -> NamedTextColor.AQUA;
          case WARNING -> NamedTextColor.YELLOW;
          case FAILURE -> NamedTextColor.RED;
        };
    return Component.text(" - ", NamedTextColor.GOLD)
        .append(Component.text(capability.name() + ":", NamedTextColor.DARK_GRAY))
        .append(Component.text(" " + capability.status(), color))
        .append(
            Component.text(
                    " - " + boundedCapabilityDetail(capability.detail()), NamedTextColor.GRAY)
                .hoverEvent(Component.text(singleLine(capability.detail()), NamedTextColor.GRAY)));
  }

  private static String boundedCapabilityDetail(String detail) {
    String normalized = singleLine(detail);
    int limit = 180;
    return normalized.length() <= limit
        ? normalized
        : normalized.substring(0, limit - 1) + "\u2026";
  }

  private static String singleLine(String detail) {
    if (detail == null || detail.isBlank()) {
      return "no detail";
    }
    return detail.replace('\r', ' ').replace('\n', ' ').strip();
  }
}
