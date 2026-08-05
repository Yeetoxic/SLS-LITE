package net.slimelabs.slslite.messaging;

import com.velocitypowered.api.event.connection.PluginMessageEvent;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.Locale;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.slimelabs.slslite.config.BackendMessageAction;
import net.slimelabs.slslite.config.BackendMessageSourceConfig;
import net.slimelabs.slslite.config.BackendMessagingConfig;
import net.slimelabs.slslite.instance.InstanceOperationException;
import net.slimelabs.slslite.instance.ServerController;
import net.slimelabs.slslite.log.SLSDetailLog;
import net.slimelabs.slslite.velocity.LocalJoinService;
import org.slf4j.Logger;

/** Receives source-verified, player-bound integration requests from authorized backends. */
public final class BackendMessagingService implements AutoCloseable {

  public static final String CHANNEL_NAME = "slslite:request";
  public static final MinecraftChannelIdentifier CHANNEL =
      MinecraftChannelIdentifier.from(CHANNEL_NAME);

  private final ProxyServer proxy;
  private final MatchmakingDispatcher matchmaking;
  private final CommandDispatcher commands;
  private final ManagedSourceResolver managedSources;
  private final BackendMessagingConfig config;
  private final Logger logger;
  private final SLSDetailLog detailLog;
  private final BackendMessageGuard guard;
  private final AtomicBoolean started = new AtomicBoolean();
  private volatile boolean closed;

  public BackendMessagingService(
      ProxyServer proxy,
      LocalJoinService joins,
      ServerController instances,
      BackendMessagingConfig config,
      Logger logger,
      SLSDetailLog detailLog) {
    this.proxy = java.util.Objects.requireNonNull(proxy, "proxy");
    LocalJoinService joinService = java.util.Objects.requireNonNull(joins, "joins");
    this.matchmaking =
        (player, registry, target) -> joinService.join(player, registry, target).instance().id();
    this.commands = (player, command) -> proxy.getCommandManager().executeAsync(player, command);
    ServerController controller = java.util.Objects.requireNonNull(instances, "instances");
    this.managedSources =
        serverName ->
            controller.getAll().stream()
                .filter(instance -> instance.id().equals(serverName))
                .findFirst()
                .map(instance -> instance.blueprint().type() + "/" + instance.blueprint().id());
    this.config = java.util.Objects.requireNonNull(config, "config");
    this.logger = java.util.Objects.requireNonNull(logger, "logger");
    this.detailLog = java.util.Objects.requireNonNull(detailLog, "detailLog");
    this.guard = new BackendMessageGuard(config);
  }

  BackendMessagingService(
      ProxyServer proxy,
      ManagedSourceResolver managedSources,
      BackendMessagingConfig config,
      Logger logger,
      SLSDetailLog detailLog,
      MatchmakingDispatcher matchmaking,
      CommandDispatcher commands) {
    this.proxy = java.util.Objects.requireNonNull(proxy, "proxy");
    this.managedSources = java.util.Objects.requireNonNull(managedSources, "managedSources");
    this.config = java.util.Objects.requireNonNull(config, "config");
    this.logger = java.util.Objects.requireNonNull(logger, "logger");
    this.detailLog = java.util.Objects.requireNonNull(detailLog, "detailLog");
    this.matchmaking = java.util.Objects.requireNonNull(matchmaking, "matchmaking");
    this.commands = java.util.Objects.requireNonNull(commands, "commands");
    this.guard = new BackendMessageGuard(config);
  }

  public void start() {
    if (closed || !started.compareAndSet(false, true)) {
      return;
    }
    proxy.getChannelRegistrar().register(CHANNEL);
    if (config.enabled()) {
      logger.info(
          "Secure backend messaging enabled on {} for {} authorized source(s); "
              + "command relay={}",
          CHANNEL_NAME,
          config.sources().size(),
          config.commandRelayEnabled() ? "enabled" : "disabled");
    }
  }

  public void handle(PluginMessageEvent event) {
    if (!CHANNEL.equals(event.getIdentifier())) {
      return;
    }
    event.setResult(PluginMessageEvent.ForwardResult.handled());
    if (closed || !config.enabled()) {
      return;
    }
    if (!(event.getSource() instanceof ServerConnection connection)) {
      reject("non-backend-source", "unknown", "none", "matching client-originated payload");
      return;
    }
    Player player = connection.getPlayer();
    String serverName = connection.getServerInfo().getName();
    if (event.getTarget() != player
        || !player.isActive()
        || player.getCurrentServer().filter(current -> current == connection).isEmpty()) {
      reject(
          "carrier-mismatch",
          serverName,
          player.getUniqueId().toString(),
          "source is not the carrier player's current server connection");
      return;
    }
    Optional<BackendMessageSourceConfig> authorized = authorizedSource(serverName);
    if (authorized.isEmpty()) {
      reject(
          "unauthorized-source",
          serverName,
          player.getUniqueId().toString(),
          "server is not configured as an authorized source");
      return;
    }
    BackendMessageSourceConfig source = authorized.orElseThrow();
    long now = System.nanoTime();
    if (!guard.allowRate(source.id(), player.getUniqueId(), now)) {
      reject(
          "rate-limit",
          serverName,
          player.getUniqueId().toString(),
          "source/player request window exhausted");
      return;
    }

    BackendMessageRequest request;
    try {
      request = BackendMessageProtocol.decode(event.getData());
    } catch (BackendMessageProtocol.ProtocolException exception) {
      reject("malformed", serverName, player.getUniqueId().toString(), exception.getMessage());
      return;
    }
    if (!guard.firstRequest(request.requestId(), now)) {
      reject(
          "duplicate",
          serverName,
          player.getUniqueId().toString(),
          "request=" + request.requestId());
      return;
    }

    if (request instanceof BackendMessageRequest.Matchmake matchmake) {
      if (!source.actions().contains(BackendMessageAction.MATCHMAKE)) {
        rejectAction(source, player, serverName, request, "matchmake");
        return;
      }
      dispatchMatchmake(source, player, serverName, matchmake);
      return;
    }
    BackendMessageRequest.Command command = (BackendMessageRequest.Command) request;
    if (!config.commandRelayEnabled() || !source.actions().contains(BackendMessageAction.COMMAND)) {
      rejectAction(source, player, serverName, request, "command");
      return;
    }
    dispatchCommand(source, player, serverName, command);
  }

  @Override
  public void close() {
    closed = true;
    guard.clear();
    if (started.getAndSet(false)) {
      proxy.getChannelRegistrar().unregister(CHANNEL);
    }
  }

  private Optional<BackendMessageSourceConfig> authorizedSource(String serverName) {
    Optional<BackendMessageSourceConfig> exact =
        config.sources().stream()
            .filter(BackendMessageSourceConfig::exactServer)
            .filter(source -> source.server().equals(serverName))
            .findFirst();
    if (exact.isPresent()) {
      return exact;
    }
    Optional<String> managed = managedSources.blueprint(serverName);
    if (managed.isEmpty()) {
      return Optional.empty();
    }
    String blueprint = managed.orElseThrow();
    return config.sources().stream()
        .filter(source -> !source.exactServer())
        .filter(source -> source.blueprint().equals(blueprint))
        .sorted(Comparator.comparing(BackendMessageSourceConfig::id))
        .findFirst();
  }

  private void dispatchMatchmake(
      BackendMessageSourceConfig source,
      Player player,
      String serverName,
      BackendMessageRequest.Matchmake request) {
    try {
      String instanceId = matchmaking.join(player, request.registry(), request.target());
      detailLog.normal(
          request.requestId().toString(),
          "backend-message",
          "accepted action=matchmake source={} server={} player={} target={}/{} instance={}",
          source.id(),
          serverName,
          player.getUniqueId(),
          request.registry(),
          request.target(),
          instanceId);
    } catch (InstanceOperationException exception) {
      detailLog.normal(
          request.requestId().toString(),
          "backend-message",
          "rejected action=matchmake source={} server={} player={} reason={}",
          source.id(),
          serverName,
          player.getUniqueId(),
          exception.getMessage());
      player.sendMessage(
          Component.text("SLS-LITE: ", NamedTextColor.GOLD)
              .append(Component.text(exception.getMessage(), NamedTextColor.RED)));
    }
  }

  private void dispatchCommand(
      BackendMessageSourceConfig source,
      Player player,
      String serverName,
      BackendMessageRequest.Command request) {
    String command;
    try {
      command = executableCommand(request.command());
      if (!source.permitsCommand(command)) {
        rejectAction(source, player, serverName, request, "command-root");
        return;
      }
    } catch (IllegalArgumentException exception) {
      reject(
          "invalid-command", serverName, player.getUniqueId().toString(), exception.getMessage());
      return;
    }
    detailLog.normal(
        request.requestId().toString(),
        "backend-message",
        "accepted action=command source={} server={} player={} root={}",
        source.id(),
        serverName,
        player.getUniqueId(),
        commandRoot(command));
    commands
        .execute(player, command)
        .whenComplete(
            (executed, failure) -> {
              if (failure != null || !Boolean.TRUE.equals(executed)) {
                detailLog.normal(
                    request.requestId().toString(),
                    "backend-message",
                    "command dispatch failed source={} server={} player={} reason={}",
                    source.id(),
                    serverName,
                    player.getUniqueId(),
                    failure == null
                        ? "command was not accepted"
                        : failure.getClass().getSimpleName());
              }
            });
  }

  private void rejectAction(
      BackendMessageSourceConfig source,
      Player player,
      String serverName,
      BackendMessageRequest request,
      String action) {
    reject(
        "action-denied",
        serverName,
        player.getUniqueId().toString(),
        "source=" + source.id() + " action=" + action + " request=" + request.requestId());
  }

  private void reject(String category, String server, String player, String detail) {
    detailLog.normal(
        "backend-message",
        "backend-message-security",
        "rejected category={} server={} player={} detail={}",
        category,
        server,
        player,
        detail);
  }

  private static String executableCommand(String raw) {
    String command = raw == null ? "" : raw.strip();
    if (command.startsWith("/")) {
      command = command.substring(1).stripLeading();
    }
    if (command.isBlank()
        || command.getBytes(StandardCharsets.UTF_8).length
            > BackendMessageProtocol.MAX_COMMAND_BYTES) {
      throw new IllegalArgumentException("command must contain 1-512 UTF-8 bytes");
    }
    for (int index = 0; index < command.length(); index++) {
      if (Character.isISOControl(command.charAt(index))) {
        throw new IllegalArgumentException("command contains control characters");
      }
    }
    return command;
  }

  private static String commandRoot(String command) {
    String[] parts = command.toLowerCase(Locale.ROOT).split("\\s+");
    return parts.length < 2 ? parts[0] : parts[0] + " " + parts[1];
  }

  @FunctionalInterface
  interface MatchmakingDispatcher {

    String join(Player player, String registry, String target) throws InstanceOperationException;
  }

  @FunctionalInterface
  interface CommandDispatcher {

    CompletableFuture<Boolean> execute(Player player, String command);
  }

  @FunctionalInterface
  interface ManagedSourceResolver {

    Optional<String> blueprint(String serverName);
  }
}
