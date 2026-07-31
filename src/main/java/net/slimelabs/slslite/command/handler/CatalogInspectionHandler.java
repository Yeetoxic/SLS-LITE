package net.slimelabs.slslite.command.handler;

import com.velocitypowered.api.command.CommandSource;
import java.util.Collection;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.slimelabs.slslite.blueprint.Blueprint;
import net.slimelabs.slslite.blueprint.BlueprintRepository;
import net.slimelabs.slslite.command.CommandAuthorizer;
import net.slimelabs.slslite.command.CommandMessages;
import net.slimelabs.slslite.instance.ServerController;

final class CatalogInspectionHandler {

  private final BlueprintRepository blueprints;
  private final ServerController instances;
  private final CommandAuthorizer authorizer;

  CatalogInspectionHandler(
      BlueprintRepository blueprints, ServerController instances, CommandAuthorizer authorizer) {
    this.blueprints = blueprints;
    this.instances = instances;
    this.authorizer = authorizer;
  }

  void registries(CommandSource source) {
    if (blueprints.getTypes().isEmpty()) {
      source.sendMessage(
          CommandMessages.message("No registries are loaded.", NamedTextColor.YELLOW));
      return;
    }
    source.sendMessage(CommandMessages.message("Registries", NamedTextColor.GREEN));
    blueprints.getTypes().stream()
        .sorted()
        .forEach(
            type ->
                source.sendMessage(
                    CommandMessages.prefix()
                        .append(Component.text("- " + type, NamedTextColor.GOLD))
                        .append(
                            Component.text(" (" + blueprints.getByType(type).size() + " server(s))")
                                .color(NamedTextColor.GRAY))));
  }

  void blueprints(CommandSource source, String[] arguments) {
    if (!requireAdmin(source)) {
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
      source.sendMessage(
          CommandMessages.message("No matching blueprints are loaded.", NamedTextColor.YELLOW));
      return;
    }
    source.sendMessage(CommandMessages.message("Blueprints", NamedTextColor.GREEN));
    selected.forEach(
        blueprint ->
            source.sendMessage(
                CommandMessages.prefix()
                    .append(Component.text("- ", NamedTextColor.GOLD))
                    .append(CommandMessages.blueprint(blueprint, instances.getAll()))));
  }

  void blueprint(CommandSource source, String[] arguments) {
    if (!requireAdmin(source, "blueprint", "inspect blueprints")) {
      return;
    }
    if (arguments.length != 2) {
      source.sendMessage(CommandMessages.usage("/sls blueprint", "id"));
      return;
    }
    Blueprint blueprint = blueprints.get(arguments[1]).orElse(null);
    if (blueprint == null) {
      source.sendMessage(
          CommandMessages.message("Blueprint not found: " + arguments[1], NamedTextColor.YELLOW));
      return;
    }
    source.sendMessage(
        CommandMessages.message(
            "Blueprint " + blueprint.type() + "/" + blueprint.id(), NamedTextColor.GREEN));
    source.sendMessage(
        CommandMessages.prefix()
            .append(CommandMessages.blueprintDetails(blueprint, instances.getAll())));
  }

  List<String> suggestions(CommandSource source, String operation) {
    if ("blueprint".equals(operation)) {
      return authorizer.canAdminister(source, operation)
          ? blueprints.getAll().stream().map(Blueprint::id).sorted().toList()
          : List.of();
    }
    return authorizer.canAdminister(source, "blueprints")
        ? blueprints.getTypes().stream().sorted().toList()
        : List.of();
  }

  private boolean requireAdmin(CommandSource source) {
    return requireAdmin(source, "blueprints", "inspect blueprints");
  }

  private boolean requireAdmin(CommandSource source, String operation, String action) {
    if (authorizer.canAdminister(source, operation)) {
      return true;
    }
    source.sendMessage(
        CommandMessages.message(
            "You do not have permission to " + action + ".", NamedTextColor.RED));
    return false;
  }
}
