package net.slimelabs.slslite.command.handler;

import com.velocitypowered.api.command.CommandSource;
import java.util.Collection;
import java.util.List;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.slimelabs.slslite.blueprint.Blueprint;
import net.slimelabs.slslite.blueprint.BlueprintRepository;
import net.slimelabs.slslite.blueprint.readiness.BlueprintReadinessCatalog;
import net.slimelabs.slslite.blueprint.readiness.BlueprintReadinessReport;
import net.slimelabs.slslite.blueprint.readiness.BlueprintReadinessState;
import net.slimelabs.slslite.command.CommandAuthorizer;
import net.slimelabs.slslite.command.CommandMessages;
import net.slimelabs.slslite.instance.ServerController;

final class CatalogInspectionHandler {

  private final BlueprintRepository blueprints;
  private final ServerController instances;
  private final CommandAuthorizer authorizer;
  private BlueprintReadinessCatalog readiness;

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
    List<BlueprintRepository.Rejection> rejected =
        arguments.length == 1 ? blueprints.rejections() : List.of();
    if (selected.isEmpty() && rejected.isEmpty()) {
      source.sendMessage(
          CommandMessages.message("No matching blueprints are loaded.", NamedTextColor.YELLOW));
      return;
    }
    source.sendMessage(CommandMessages.message("Blueprints", NamedTextColor.GREEN));
    selected.forEach(
        blueprint -> {
          Component line =
              CommandMessages.prefix()
                  .append(Component.text("- ", NamedTextColor.GOLD))
                  .append(CommandMessages.blueprint(blueprint, instances.getAll()));
          BlueprintReadinessReport report = report(blueprint.id());
          if (report != null) {
            line =
                line.append(
                    Component.text(" [" + stateName(report.state()) + "]", color(report.state())));
          }
          source.sendMessage(line);
        });
    rejected.forEach(
        rejection ->
            source.sendMessage(
                CommandMessages.prefix()
                    .append(Component.text("- " + rejection.path(), NamedTextColor.GOLD))
                    .append(Component.text(" [action needed]", NamedTextColor.RED))));
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
      BlueprintRepository.Rejection rejection = rejected(arguments[1]);
      if (rejection != null) {
        source.sendMessage(
            CommandMessages.message("Blueprint file " + rejection.path(), NamedTextColor.GREEN));
        source.sendMessage(CommandMessages.message("Readiness: action needed", NamedTextColor.RED));
        source.sendMessage(
            CommandMessages.prefix()
                .append(Component.text("- " + rejection.error(), NamedTextColor.RED)));
        return;
      }
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
    BlueprintReadinessReport report = report(blueprint.id());
    if (report != null) {
      source.sendMessage(
          CommandMessages.message(
              "Readiness: " + stateName(report.state()), color(report.state())));
      report
          .issues()
          .forEach(
              issue ->
                  source.sendMessage(
                      CommandMessages.prefix()
                          .append(Component.text("- " + issue.message(), color(issue.state())))));
    }
  }

  void installReadinessCatalog(BlueprintReadinessCatalog catalog) {
    readiness = java.util.Objects.requireNonNull(catalog, "catalog");
  }

  List<String> suggestions(CommandSource source, String operation) {
    if ("blueprint".equals(operation)) {
      return authorizer.canAdminister(source, operation)
          ? java.util.stream.Stream.concat(
                  blueprints.getAll().stream().map(Blueprint::id),
                  blueprints.rejections().stream().map(BlueprintRepository.Rejection::path))
              .sorted()
              .toList()
          : List.of();
    }
    return authorizer.canAdminister(source, "blueprints")
        ? blueprints.getTypes().stream().sorted().toList()
        : List.of();
  }

  private boolean requireAdmin(CommandSource source) {
    return requireAdmin(source, "blueprints", "inspect blueprints");
  }

  private BlueprintReadinessReport report(String id) {
    return readiness == null ? null : readiness.get(id).orElse(null);
  }

  private BlueprintRepository.Rejection rejected(String path) {
    return blueprints.rejections().stream()
        .filter(rejection -> rejection.path().equals(path))
        .findFirst()
        .orElse(null);
  }

  private static NamedTextColor color(BlueprintReadinessState state) {
    return switch (state) {
      case READY -> NamedTextColor.GREEN;
      case ACTION_NEEDED -> NamedTextColor.RED;
      case TEMPORARILY_UNAVAILABLE -> NamedTextColor.YELLOW;
    };
  }

  private static String stateName(BlueprintReadinessState state) {
    return switch (state) {
      case READY -> "ready";
      case ACTION_NEEDED -> "action needed";
      case TEMPORARILY_UNAVAILABLE -> "temporarily unavailable";
    };
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
