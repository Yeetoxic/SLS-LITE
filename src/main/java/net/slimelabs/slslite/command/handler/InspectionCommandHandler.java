package net.slimelabs.slslite.command.handler;

import com.velocitypowered.api.command.CommandSource;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.slimelabs.slslite.blueprint.BlueprintRepository;
import net.slimelabs.slslite.command.CommandAuthorizer;
import net.slimelabs.slslite.command.CommandInstanceAccess;
import net.slimelabs.slslite.config.ManagedOutputConfig;
import net.slimelabs.slslite.host.HostCapability;
import net.slimelabs.slslite.host.HostCapabilityReport;
import net.slimelabs.slslite.instance.ServerController;
import net.slimelabs.slslite.lobby.LobbyProvider;
import net.slimelabs.slslite.process.ProcessSupervisor;
import net.slimelabs.slslite.resource.ResourceBudget;
import net.slimelabs.slslite.software.SoftwareProfileRepository;
import net.slimelabs.slslite.velocity.LocalJoinService;

/**
 * Stable routing facade for read-only command families.
 */
public final class InspectionCommandHandler {

  private final CatalogInspectionHandler catalog;
  private final InstanceInspectionHandler instance;
  private final HostInspectionHandler host;

  public InspectionCommandHandler(
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
      CommandInstanceAccess instanceAccess) {
    this.catalog = new CatalogInspectionHandler(blueprints, instances, authorizer);
    this.instance =
        new InstanceInspectionHandler(instances, joinService, authorizer, instanceAccess);
    this.host =
        new HostInspectionHandler(
            blueprints,
            softwareProfiles,
            resourceBudget,
            instances,
            joinService,
            lobbyProvider,
            processSupervisor,
            outputConfig,
            hostCapabilities,
            authorizer);
  }

  public void info(CommandSource source, String[] arguments) {
    if (arguments.length == 1) {
      host.summary(source);
    } else {
      instance.info(source, arguments);
    }
  }

  public void registries(CommandSource source) {
    catalog.registries(source);
  }

  public void blueprints(CommandSource source, String[] arguments) {
    catalog.blueprints(source, arguments);
  }

  public void blueprint(CommandSource source, String[] arguments) {
    catalog.blueprint(source, arguments);
  }

  public void list(CommandSource source) {
    instance.list(source);
  }

  public void status(CommandSource source, String[] arguments) {
    instance.status(source, arguments);
  }

  public void logs(CommandSource source, String[] arguments) {
    instance.logs(source, arguments);
  }

  public void stats(CommandSource source, String[] arguments) {
    instance.stats(source, arguments);
  }

  public void system(CommandSource source, String[] arguments) {
    host.system(source, arguments);
  }

  public List<String> suggestions(CommandSource source, String operation, String[] arguments) {
    if (("blueprint".equals(operation) || "blueprints".equals(operation))
        && arguments.length == 2) {
      return catalog.suggestions(source, operation);
    }
    return instance.suggestions(source, operation, arguments);
  }

  public static Component capabilityLine(HostCapability capability) {
    return HostInspectionHandler.capabilityLine(capability);
  }
}
