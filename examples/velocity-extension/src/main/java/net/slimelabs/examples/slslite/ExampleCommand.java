package net.slimelabs.examples.slslite;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.command.SimpleCommand;
import com.velocitypowered.api.proxy.Player;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.function.Function;
import net.slimelabs.slslite.api.ExtensionContext;
import net.slimelabs.slslite.api.InstanceTransferRequest;
import net.slimelabs.slslite.api.QueueRequest;
import net.slimelabs.slslite.api.SLSLiteApi;
import net.slimelabs.slslite.api.SLSLiteApiException;
import net.slimelabs.slslite.api.StartRequest;
import net.slimelabs.slslite.api.SoftwareInstallationRequest;
import net.slimelabs.slslite.api.event.CatalogReloadScope;
import org.slf4j.Logger;

final class ExampleCommand implements SimpleCommand {

  private static final String ADMIN_PERMISSION = "slslite.example.admin";

  private final SLSLiteApi api;
  private final ExtensionContext context;
  private final Logger logger;

  ExampleCommand(SLSLiteApi api, ExtensionContext context, Logger logger) {
    this.api = api;
    this.context = context;
    this.logger = logger;
  }

  @Override
  public void execute(Invocation invocation) {
    try {
      executeSafely(invocation);
    } catch (IllegalArgumentException | SLSLiteApiException failure) {
      reportFailure(invocation.source(), invocation.arguments(), failure);
    }
  }

  private void executeSafely(Invocation invocation) {
    String[] arguments = invocation.arguments();
    if (arguments.length == 0) {
      help(invocation.source());
      return;
    }
    String operation = arguments[0].toLowerCase(Locale.ROOT);
    if (operation.equals("queue") || operation.equals("dequeue")) {
      playerOperation(invocation.source(), arguments, operation);
      return;
    }
    if (!canAdminister(invocation.source())) {
      invocation.source().sendPlainMessage("Missing permission: " + ADMIN_PERMISSION);
      return;
    }
    switch (operation) {
      case "status" -> status(invocation.source());
      case "start" -> start(invocation.source(), arguments);
      case "stop" -> stop(invocation.source(), arguments);
      case "restart" -> restart(invocation.source(), arguments, false);
      case "reset" -> restart(invocation.source(), arguments, true);
      case "delete" -> delete(invocation.source(), arguments);
      case "install" -> install(invocation.source(), arguments);
      case "reload" -> reload(invocation.source(), arguments);
      case "maintenance" -> maintenance(invocation.source(), arguments);
      case "transfer" -> transfer(invocation.source(), arguments);
      default -> help(invocation.source());
    }
  }

  @Override
  public List<String> suggest(Invocation invocation) {
    if (invocation.arguments().length <= 1) {
      return List.of(
          "status",
          "start",
          "stop",
          "restart",
          "reset",
          "delete",
          "install",
          "reload",
          "maintenance",
          "transfer",
          "queue",
          "dequeue");
    }
    return List.of();
  }

  private void status(CommandSource source) {
    source.sendPlainMessage(
        "API "
            + api.version()
            + ": blueprints="
            + api.blueprints().stream().map(blueprint -> blueprint.id()).toList()
            + ", instances="
            + api.instances().stream()
                .map(instance -> instance.id() + ":" + instance.status())
                .toList());
  }

  private void start(CommandSource source, String[] arguments) {
    if (!arity(source, arguments, 2, "start <blueprint>")) {
      return;
    }
    observe(
        source,
        "start",
        api.start(new StartRequest(arguments[1])),
        instance -> instance.id() + " is " + instance.status());
  }

  private void stop(CommandSource source, String[] arguments) {
    if (!arity(source, arguments, 2, "stop <instance>")) {
      return;
    }
    observe(
        source,
        "stop",
        api.stop(arguments[1]),
        result -> result.instanceId() + " is " + result.status());
  }

  private void delete(CommandSource source, String[] arguments) {
    if (!arity(source, arguments, 2, "delete <instance>")) {
      return;
    }
    observe(
        source,
        "delete",
        api.delete(arguments[1]),
        result ->
            result.instanceId()
                + " deleted; markerCleaned="
                + result.reconciliationMarkerCleaned());
  }

  private void restart(CommandSource source, String[] arguments, boolean reset) {
    String operation = reset ? "reset" : "restart";
    if (!arity(source, arguments, 2, operation + " <instance>")) {
      return;
    }
    observe(
        source,
        operation,
        reset ? api.reset(arguments[1]) : api.restart(arguments[1]),
        instance -> instance.id() + " is " + instance.status());
  }

  private void install(CommandSource source, String[] arguments) {
    if (!arity(source, arguments, 3, "install <software> <version>")) {
      return;
    }
    observe(
        source,
        "install",
        api.install(new SoftwareInstallationRequest(arguments[1], arguments[2])),
        result -> result.softwareId() + "/" + result.version() + " is ready");
  }

  private void reload(CommandSource source, String[] arguments) {
    if (!arity(source, arguments, 2, "reload <all|blueprints|software>")) {
      return;
    }
    CatalogReloadScope scope = CatalogReloadScope.valueOf(arguments[1].toUpperCase(Locale.ROOT));
    observe(
        source,
        "reload",
        api.reload(scope),
        result ->
            result.scope()
                + " committed as "
                + result.correlationId()
                + "; "
                + result.impact().nextAction());
  }

  private void maintenance(CommandSource source, String[] arguments) {
    if (arguments.length < 2 || !List.of("on", "off").contains(arguments[1])) {
      source.sendPlainMessage("Usage: /sls-api-example maintenance <on|off> [reason]");
      return;
    }
    boolean enabled = arguments[1].equals("on");
    String reason =
        enabled && arguments.length > 2
            ? String.join(" ", java.util.Arrays.copyOfRange(arguments, 2, arguments.length))
            : "";
    observe(
        source,
        "maintenance",
        api.setMaintenance(enabled, reason),
        result -> "enabled=" + result.enabled());
  }

  private void transfer(CommandSource source, String[] arguments) {
    if (!(source instanceof Player player)) {
      source.sendPlainMessage("transfer must be run by a player");
      return;
    }
    if (arguments.length < 2
        || arguments.length > 3
        || arguments.length == 3 && !arguments[2].equals("--force")) {
      source.sendPlainMessage("Usage: /sls-api-example transfer <instance> [--force]");
      return;
    }
    observe(
        source,
        "transfer",
        api.transfer(
            new InstanceTransferRequest(player.getUniqueId(), arguments[1], arguments.length == 3)),
        result -> "status=" + result.status() + ", instance=" + result.instanceId());
  }

  private void playerOperation(CommandSource source, String[] arguments, String operation) {
    if (!(source instanceof Player player)) {
      source.sendPlainMessage(operation + " must be run by a player");
      return;
    }
    if (operation.equals("dequeue")) {
      source.sendPlainMessage(
          api.dequeue(player.getUniqueId())
              .map(ticket -> "Dequeued " + ticket.instanceId())
              .orElse("No queued request"));
      return;
    }
    if (!arity(source, arguments, 3, "queue <registry> <blueprint>")) {
      return;
    }
    observe(
        source,
        "queue",
        api.enqueue(new QueueRequest(player.getUniqueId(), arguments[1], arguments[2])),
        result ->
            "connected="
                + result.connected()
                + ", instance="
                + result.ticket().instanceId()
                + ", created="
                + result.instanceCreated());
  }

  private <T> void observe(
      CommandSource source,
      String operation,
      CompletionStage<? extends T> stage,
      Function<? super T, String> success) {
    context.onComplete(
        stage,
        (result, failure) -> {
          if (failure == null) {
            source.sendPlainMessage(operation + " succeeded: " + success.apply(result));
            return;
          }
          Throwable root = root(failure);
          String category =
              root instanceof SLSLiteApiException apiFailure
                  ? apiFailure.code().name()
                  : root.getClass().getSimpleName();
          source.sendPlainMessage(operation + " rejected: " + category);
          if (root instanceof SLSLiteApiException) {
            logger.info("Example API operation {} was rejected: {}", operation, category);
          } else {
            logger.warn("Example API operation {} failed: {}", operation, category);
          }
        });
  }

  private void reportFailure(CommandSource source, String[] arguments, RuntimeException failure) {
    String operation = arguments.length == 0 ? "command" : arguments[0];
    String category =
        failure instanceof SLSLiteApiException apiFailure
            ? apiFailure.code().name()
            : failure.getClass().getSimpleName();
    source.sendPlainMessage(operation + " rejected: " + category);
    logger.info("Example API operation {} was rejected synchronously: {}", operation, category);
  }

  private static boolean arity(
      CommandSource source, String[] arguments, int expected, String usage) {
    if (arguments.length == expected) {
      return true;
    }
    source.sendPlainMessage("Usage: /sls-api-example " + usage);
    return false;
  }

  private static boolean canAdminister(CommandSource source) {
    return !(source instanceof Player) || source.hasPermission(ADMIN_PERMISSION);
  }

  private static Throwable root(Throwable failure) {
    Throwable current = failure;
    while ((current instanceof CompletionException
            || current instanceof java.util.concurrent.ExecutionException)
        && current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }

  private static void help(CommandSource source) {
    source.sendPlainMessage(
        "Usage: /sls-api-example <status|start|stop|restart|reset|delete|install|reload|maintenance|transfer|queue|dequeue> ...");
  }
}
