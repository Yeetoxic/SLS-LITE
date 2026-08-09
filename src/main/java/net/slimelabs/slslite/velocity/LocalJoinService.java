package net.slimelabs.slslite.velocity;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.ServerConnection;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.slimelabs.slslite.blueprint.Blueprint;
import net.slimelabs.slslite.blueprint.BlueprintQueuePolicy;
import net.slimelabs.slslite.blueprint.BlueprintRepository;
import net.slimelabs.slslite.blueprint.VSLSBlueprintAnnotations;
import net.slimelabs.slslite.config.TransferActionBarConfig;
import net.slimelabs.slslite.instance.InstanceOperationException;
import net.slimelabs.slslite.instance.ManagedInstance;
import net.slimelabs.slslite.instance.ProvisioningFeedback;
import net.slimelabs.slslite.instance.ServerController;
import net.slimelabs.slslite.instance.configuration.ManagedPlayerCapacity;
import net.slimelabs.slslite.instance.lifecycle.IdleAdmissionControl;
import net.slimelabs.slslite.instance.model.InstanceState;
import net.slimelabs.slslite.log.SLSDetailLog;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class LocalJoinService implements AutoCloseable, IdleAdmissionControl {

  private static final Logger DEFAULT_LOGGER = LoggerFactory.getLogger(LocalJoinService.class);

  private final ProxyServer proxy;
  private final BlueprintRepository blueprints;
  private final ServerController instances;
  private final Duration queueTimeout;
  private final ScheduledExecutorService scheduler;
  private final TransferActionBar actionBar;
  private final JoinTimingReporter timingReporter;
  private final BlueprintSelectionStrategy blueprintSelection;
  private final Map<UUID, QueueEntry> queue = new HashMap<>();
  private final Map<AdmissionKey, PendingAdmission> pendingAdmissions = new HashMap<>();
  private final Set<String> queueOwnedInstances = new java.util.HashSet<>();
  private final Set<String> drainingInstances = new java.util.HashSet<>();
  private volatile java.util.function.Consumer<MatchmakingTransition> matchmakingObserver =
      ignored -> {};
  private boolean closed;

  public LocalJoinService(
      ProxyServer proxy,
      BlueprintRepository blueprints,
      ServerController instances,
      Duration queueTimeout) {
    this(
        proxy,
        blueprints,
        instances,
        queueTimeout,
        Executors.newSingleThreadScheduledExecutor(threadFactory()),
        DEFAULT_LOGGER,
        SLSDetailLog.disabled(),
        BlueprintSelectionStrategy.firstAvailable());
  }

  public LocalJoinService(
      ProxyServer proxy,
      BlueprintRepository blueprints,
      ServerController instances,
      Duration queueTimeout,
      Logger logger) {
    this(
        proxy,
        blueprints,
        instances,
        queueTimeout,
        Executors.newSingleThreadScheduledExecutor(threadFactory()),
        logger,
        SLSDetailLog.disabled(),
        BlueprintSelectionStrategy.firstAvailable());
  }

  public LocalJoinService(
      ProxyServer proxy,
      BlueprintRepository blueprints,
      ServerController instances,
      Duration queueTimeout,
      Logger logger,
      SLSDetailLog detailLog) {
    this(
        proxy,
        blueprints,
        instances,
        queueTimeout,
        logger,
        detailLog,
        BlueprintSelectionStrategy.firstAvailable());
  }

  public LocalJoinService(
      ProxyServer proxy,
      BlueprintRepository blueprints,
      ServerController instances,
      Duration queueTimeout,
      Logger logger,
      SLSDetailLog detailLog,
      BlueprintSelectionStrategy blueprintSelection) {
    this(
        proxy,
        blueprints,
        instances,
        queueTimeout,
        logger,
        detailLog,
        blueprintSelection,
        TransferActionBarConfig.defaults());
  }

  public LocalJoinService(
      ProxyServer proxy,
      BlueprintRepository blueprints,
      ServerController instances,
      Duration queueTimeout,
      Logger logger,
      SLSDetailLog detailLog,
      BlueprintSelectionStrategy blueprintSelection,
      TransferActionBarConfig actionBarConfig) {
    this(
        proxy,
        blueprints,
        instances,
        queueTimeout,
        Executors.newSingleThreadScheduledExecutor(threadFactory()),
        logger,
        detailLog,
        blueprintSelection,
        actionBarConfig);
  }

  LocalJoinService(
      ProxyServer proxy,
      BlueprintRepository blueprints,
      ServerController instances,
      Duration queueTimeout,
      ScheduledExecutorService scheduler,
      Logger logger) {
    this(
        proxy,
        blueprints,
        instances,
        queueTimeout,
        scheduler,
        logger,
        SLSDetailLog.disabled(),
        BlueprintSelectionStrategy.firstAvailable());
  }

  LocalJoinService(
      ProxyServer proxy,
      BlueprintRepository blueprints,
      ServerController instances,
      Duration queueTimeout,
      ScheduledExecutorService scheduler,
      Logger logger,
      SLSDetailLog detailLog) {
    this(
        proxy,
        blueprints,
        instances,
        queueTimeout,
        scheduler,
        logger,
        detailLog,
        BlueprintSelectionStrategy.firstAvailable());
  }

  LocalJoinService(
      ProxyServer proxy,
      BlueprintRepository blueprints,
      ServerController instances,
      Duration queueTimeout,
      ScheduledExecutorService scheduler,
      Logger logger,
      SLSDetailLog detailLog,
      BlueprintSelectionStrategy blueprintSelection) {
    this(
        proxy,
        blueprints,
        instances,
        queueTimeout,
        scheduler,
        logger,
        detailLog,
        blueprintSelection,
        TransferActionBarConfig.defaults());
  }

  LocalJoinService(
      ProxyServer proxy,
      BlueprintRepository blueprints,
      ServerController instances,
      Duration queueTimeout,
      ScheduledExecutorService scheduler,
      Logger logger,
      SLSDetailLog detailLog,
      BlueprintSelectionStrategy blueprintSelection,
      TransferActionBarConfig actionBarConfig) {
    if (queueTimeout.isZero() || queueTimeout.isNegative()) {
      throw new IllegalArgumentException("queueTimeout must be positive");
    }
    this.proxy = proxy;
    this.blueprints = blueprints;
    this.instances = instances;
    this.queueTimeout = queueTimeout;
    this.scheduler = scheduler;
    this.actionBar = new TransferActionBar(scheduler, actionBarConfig);
    this.timingReporter = new JoinTimingReporter(instances, logger, detailLog);
    this.blueprintSelection =
        java.util.Objects.requireNonNull(blueprintSelection, "blueprintSelection");
  }

  /** Installs the one process-wide observer before matchmaking accepts a request. */
  public synchronized void installMatchmakingObserver(
      java.util.function.Consumer<MatchmakingTransition> observer) {
    if (!queue.isEmpty()) {
      throw new IllegalStateException("Matchmaking observer must be installed before queue use");
    }
    matchmakingObserver = java.util.Objects.requireNonNull(observer, "observer");
  }

  public JoinAttempt join(Player player, String registry, String server)
      throws InstanceOperationException {
    Blueprint blueprint =
        blueprints
            .get(registry, server)
            .orElseThrow(
                () ->
                    new InstanceOperationException(
                        "Unknown server '" + server + "' in registry '" + registry + "'"));

    QueueEntry entry;
    boolean created;
    synchronized (this) {
      if (closed) {
        throw new InstanceOperationException("Matchmaking is shutting down");
      }
      QueueEntry current = queue.get(player.getUniqueId());
      if (current != null) {
        throw new InstanceOperationException(
            player.getUsername()
                + " is already queued for "
                + current.ticket.registry()
                + "/"
                + current.ticket.server());
      }

      List<Blueprint> pool = matchmakingPool(blueprint);
      Optional<ManagedInstance> existing = selectInstance(pool);
      created = existing.isEmpty();
      Blueprint provision = created ? provisionBlueprint(blueprint, pool).orElse(null) : null;
      if (created && provision == null) {
        throw new InstanceOperationException(
            "All instances in matchmaking pool '"
                + gameType(blueprint)
                + "' are full and every "
                + "blueprint has reached its instance limit");
      }
      ManagedInstance instance =
          existing.isPresent() ? existing.get() : instances.start(provision.id());
      if (created) {
        queueOwnedInstances.add(instance.id());
      }
      QueueTicket ticket =
          new QueueTicket(
              player.getUniqueId(),
              player.getUsername(),
              registry,
              server,
              instance.id(),
              Instant.now());
      Duration effectiveTimeout =
          BlueprintQueuePolicy.from(instance.blueprint(), queueTimeout).timeout();
      entry = new QueueEntry(ticket, instance, created, effectiveTimeout);
      queue.put(ticket.playerId(), entry);
      if (!effectiveTimeout.isZero()) {
        entry.timeout =
            scheduler.schedule(
                () -> timeout(entry), effectiveTimeout.toMillis(), TimeUnit.MILLISECONDS);
      }
      entry.feedback =
          actionBar.start(
              player, instance.blueprint().name(), () -> ProvisioningFeedback.progress(instance));
      publish(entry, MatchmakingTransitionStatus.QUEUED);
    }

    entry
        .instance
        .readyFuture()
        .whenComplete(
            (ready, failure) -> {
              if (failure == null) {
                entry.timings.backendReady();
                connect(entry, ready);
              } else {
                fail(entry, failure);
              }
            });
    return new JoinAttempt(entry.ticket, entry.instance, created, entry.completion);
  }

  public long queueTimeoutSeconds() {
    return queueTimeout.toSeconds();
  }

  /** Reuses the configured transfer surface for a player-issued manual start. */
  public Runnable showPreparation(Player player, ManagedInstance instance) {
    return actionBar.start(
        player, instance.blueprint().name(), () -> ProvisioningFeedback.progress(instance));
  }

  public long queueTimeoutSeconds(Blueprint blueprint) {
    return BlueprintQueuePolicy.from(blueprint, queueTimeout).timeout().toSeconds();
  }

  public synchronized Optional<QueueTicket> queued(UUID playerId) {
    QueueEntry entry = queue.get(playerId);
    return entry == null ? Optional.empty() : Optional.of(entry.ticket);
  }

  /** Whether built-in transfer feedback currently owns this player's action bar. */
  public boolean isPresentingActionBar(UUID playerId) {
    return actionBar.isRunning(playerId);
  }

  public synchronized DirectJoin joinPlayer(Player player, Player target)
      throws InstanceOperationException {
    return joinPlayer(player, target, false);
  }

  public synchronized DirectJoin joinPlayer(Player player, Player target, boolean force)
      throws InstanceOperationException {
    ServerConnection targetConnection =
        target
            .getCurrentServer()
            .orElseThrow(
                () ->
                    new InstanceOperationException(
                        target.getUsername() + " is not connected to a backend server"));
    String instanceId = targetConnection.getServerInfo().getName();
    ManagedInstance instance = instances.get(instanceId);
    return joinInstance(player, instance, force);
  }

  public synchronized DirectJoin joinInstance(
      Player player, ManagedInstance requestedInstance, boolean force)
      throws InstanceOperationException {
    java.util.Objects.requireNonNull(player, "player");
    java.util.Objects.requireNonNull(requestedInstance, "requestedInstance");
    ManagedInstance instance = instances.get(requestedInstance.id());
    if (instance.state() != InstanceState.READY) {
      throw InstanceOperationException.notReady(instance.id());
    }
    RegisteredServer registered =
        proxy
            .getServer(instance.id())
            .orElseThrow(() -> InstanceOperationException.notRegistered(instance.id()));
    int occupiedSlots = occupiedSlots(instance);
    if (!force && occupiedSlots >= instance.blueprint().maxPlayers()) {
      throw InstanceOperationException.blueprintCapacity(
          instance.id(), occupiedSlots, instance.blueprint().maxPlayers());
    }
    if (force && backendOccupiedSlots(instance) >= backendPlayerLimit(instance)) {
      throw InstanceOperationException.backendCapacity(instance.id());
    }
    if (force) {
      actionBar.forceJoining(player, instance.id());
    } else {
      actionBar.joining(player, instance.id());
    }
    JoinPhaseTimings timings = new JoinPhaseTimings(false);
    timings.backendReady();
    timings.transferStarted();
    PendingAdmission admission = reserveAdmission(player.getUniqueId(), instance.id(), force);
    CompletableFuture<ConnectionRequestBuilder.Result> connection;
    try {
      connection = player.createConnectionRequest(registered).connect();
    } catch (RuntimeException failure) {
      removeAdmission(admission);
      throw new InstanceOperationException(
          "Unable to request connection to managed instance: " + instance.id(), failure);
    }
    connection.whenComplete(
        (result, failure) -> {
          removeAdmission(admission);
          timingReporter.connection(instance.id(), timings, result, failure);
        });
    return new DirectJoin(instance, connection);
  }

  public synchronized ConnectionAdmission admitConnection(Player player, RegisteredServer target) {
    if (closed) {
      return ConnectionAdmission.SHUTDOWN;
    }
    String instanceId = target.getServerInfo().getName();
    ManagedInstance instance =
        instances.getAll().stream()
            .filter(candidate -> candidate.id().equals(instanceId))
            .findFirst()
            .orElse(null);
    if (instance == null) {
      return ConnectionAdmission.UNMANAGED;
    }
    if (instance.state() != InstanceState.READY) {
      return ConnectionAdmission.NOT_READY;
    }

    AdmissionKey key = new AdmissionKey(player.getUniqueId(), instanceId);
    PendingAdmission pending = pendingAdmissions.get(key);
    if (pending != null) {
      return pending.force ? ConnectionAdmission.FORCED : ConnectionAdmission.RESERVED;
    }
    QueueEntry queued = queue.get(player.getUniqueId());
    if (queued != null
        && queued.state == QueueState.TRANSFERRING
        && queued.instance.id().equals(instanceId)) {
      return ConnectionAdmission.RESERVED;
    }
    if (occupiedSlots(instance) >= instance.blueprint().maxPlayers()) {
      return ConnectionAdmission.FULL;
    }
    reserveAdmission(player.getUniqueId(), instanceId, false);
    return ConnectionAdmission.AVAILABLE;
  }

  public void connected(Player player, RegisteredServer server) {
    synchronized (this) {
      removeAdmissions(player.getUniqueId());
    }
    timingReporter.connected(server);
  }

  public synchronized List<QueueTicket> queuedPlayers() {
    return queue.values().stream()
        .map(entry -> entry.ticket)
        .sorted(Comparator.comparing(QueueTicket::queuedAt))
        .toList();
  }

  @Override
  public synchronized boolean hasPendingJoin(String instanceId) {
    return queue.values().stream().anyMatch(entry -> entry.instance.id().equals(instanceId));
  }

  @Override
  public synchronized boolean tryDrain(String instanceId) {
    return !hasPendingJoin(instanceId) && drainingInstances.add(instanceId);
  }

  @Override
  public synchronized void cancelDrain(String instanceId) {
    drainingInstances.remove(instanceId);
  }

  public Optional<QueueTicket> dequeue(UUID playerId) {
    return dequeue(playerId, true, MatchmakingTransitionStatus.CANCELLED);
  }

  private Optional<QueueTicket> dequeue(
      UUID playerId, boolean showFeedback, MatchmakingTransitionStatus transitionStatus) {
    QueueEntry entry;
    synchronized (this) {
      entry = queue.get(playerId);
      if (entry == null || entry.state != QueueState.QUEUED) {
        return Optional.empty();
      }
      queue.remove(playerId);
      entry.state = QueueState.CANCELLED;
    }
    cancel(entry, "Matchmaking request was cancelled", transitionStatus);
    proxy
        .getPlayer(playerId)
        .filter(Player::isActive)
        .ifPresent(
            player -> {
              if (showFeedback) {
                actionBar.showDequeued(player);
              }
            });
    stopOrphaned(entry.instance);
    return Optional.of(entry.ticket);
  }

  public List<QueueTicket> dequeue(Iterable<UUID> playerIds) {
    List<QueueTicket> removed = new ArrayList<>();
    for (UUID playerId : playerIds) {
      dequeue(playerId).ifPresent(removed::add);
    }
    return List.copyOf(removed);
  }

  public List<QueueTicket> dequeueAll() {
    List<UUID> playerIds;
    synchronized (this) {
      playerIds = List.copyOf(queue.keySet());
    }
    return dequeue(playerIds);
  }

  public void disconnect(UUID playerId) {
    synchronized (this) {
      removeAdmissions(playerId);
    }
    dequeue(playerId, false, MatchmakingTransitionStatus.DISCONNECTED);
  }

  public Optional<RegisteredServer> initialServer() {
    return instances.getAll().stream()
        .filter(instance -> instance.state() == InstanceState.READY)
        .sorted(Comparator.comparing(ManagedInstance::id))
        .map(instance -> proxy.getServer(instance.id()))
        .flatMap(Optional::stream)
        .findFirst();
  }

  @Override
  public void close() {
    List<QueueEntry> entries;
    synchronized (this) {
      if (closed) {
        return;
      }
      closed = true;
      entries = List.copyOf(queue.values());
      entries.forEach(entry -> entry.state = QueueState.CANCELLED);
      queue.clear();
      pendingAdmissions.clear();
      drainingInstances.clear();
    }
    entries.forEach(
        entry ->
            cancel(entry, "Matchmaking is shutting down", MatchmakingTransitionStatus.SHUTDOWN));
    entries.stream().map(entry -> entry.instance).distinct().forEach(this::stopOrphaned);
    actionBar.close();
    scheduler.shutdownNow();
  }

  private Optional<ManagedInstance> selectInstance(List<Blueprint> pool) {
    Set<String> blueprintIds =
        pool.stream().map(Blueprint::id).collect(java.util.stream.Collectors.toUnmodifiableSet());
    return instances.getAll().stream()
        .filter(instance -> blueprintIds.contains(instance.blueprint().id()))
        .filter(this::isActive)
        .filter(instance -> !isDraining(instance.id()))
        .filter(instance -> occupiedSlots(instance) < instance.blueprint().maxPlayers())
        .sorted(
            Comparator.comparing(
                    (ManagedInstance instance) -> instance.state() == InstanceState.READY ? 0 : 1)
                .thenComparing(ManagedInstance::createdAt)
                .thenComparing(ManagedInstance::id))
        .findFirst();
  }

  private List<Blueprint> matchmakingPool(Blueprint requested) {
    String gameType = VSLSBlueprintAnnotations.gameType(requested.annotations()).orElse(null);
    if (gameType == null) {
      return List.of(requested);
    }
    return blueprints.getAll().stream()
        .filter(
            candidate ->
                VSLSBlueprintAnnotations.gameType(candidate.annotations())
                    .filter(gameType::equals)
                    .isPresent())
        .sorted(
            Comparator.comparing(
                candidate -> candidate.id().equals(requested.id()) ? "" : candidate.id()))
        .toList();
  }

  private Optional<Blueprint> provisionBlueprint(Blueprint requested, List<Blueprint> pool) {
    List<Blueprint> eligible =
        pool.stream()
            .filter(candidate -> activeInstanceCount(candidate) < candidate.maxInstances())
            .toList();
    Optional<Blueprint> selected = blueprintSelection.select(requested, eligible);
    if (selected.isPresent() && !eligible.contains(selected.get())) {
      throw new IllegalStateException("Blueprint selection returned an ineligible definition");
    }
    return selected;
  }

  private static String gameType(Blueprint blueprint) {
    return VSLSBlueprintAnnotations.gameType(blueprint.annotations()).orElse(blueprint.id());
  }

  private long activeInstanceCount(Blueprint blueprint) {
    return instances.getAll().stream()
        .filter(instance -> instance.blueprint().id().equals(blueprint.id()))
        .filter(this::isActive)
        .count();
  }

  private boolean isActive(ManagedInstance instance) {
    return instance.state() != InstanceState.STOPPING
        && instance.state() != InstanceState.STOPPED
        && instance.state() != InstanceState.FAILED;
  }

  private int occupiedSlots(ManagedInstance instance) {
    int connected =
        proxy.getServer(instance.id()).map(server -> server.getPlayersConnected().size()).orElse(0);
    return connected + queuedFor(instance.id()) + pendingAdmissions(instance.id(), false);
  }

  private int backendOccupiedSlots(ManagedInstance instance) {
    int connected =
        proxy.getServer(instance.id()).map(server -> server.getPlayersConnected().size()).orElse(0);
    return connected + queuedFor(instance.id()) + pendingAdmissions(instance.id(), true);
  }

  private int backendPlayerLimit(ManagedInstance instance) {
    int proxyLimit =
        proxy.getConfiguration() == null ? 0 : proxy.getConfiguration().getShowMaxPlayers();
    return ManagedPlayerCapacity.backendLimit(instance.blueprint().maxPlayers(), proxyLimit);
  }

  private int pendingAdmissions(String instanceId, boolean includeForced) {
    return (int)
        pendingAdmissions.values().stream()
            .filter(admission -> admission.key.instanceId().equals(instanceId))
            .filter(admission -> includeForced || !admission.force)
            .count();
  }

  private PendingAdmission reserveAdmission(UUID playerId, String instanceId, boolean force) {
    removeAdmissions(playerId);
    AdmissionKey key = new AdmissionKey(playerId, instanceId);
    PendingAdmission admission = new PendingAdmission(key, force);
    pendingAdmissions.put(key, admission);
    try {
      scheduler.schedule(
          () -> {
            synchronized (LocalJoinService.this) {
              pendingAdmissions.remove(key, admission);
            }
          },
          15,
          TimeUnit.SECONDS);
    } catch (RejectedExecutionException failure) {
      pendingAdmissions.remove(key, admission);
      throw failure;
    }
    return admission;
  }

  private synchronized void removeAdmission(PendingAdmission admission) {
    pendingAdmissions.remove(admission.key, admission);
  }

  private void removeAdmissions(UUID playerId) {
    pendingAdmissions.keySet().removeIf(key -> key.playerId().equals(playerId));
  }

  private int queuedFor(String instanceId) {
    return (int)
        queue.values().stream()
            .filter(entry -> entry.ticket.instanceId().equals(instanceId))
            .count();
  }

  private synchronized boolean isDraining(String instanceId) {
    return drainingInstances.contains(instanceId);
  }

  private void connect(QueueEntry entry, ManagedInstance instance) {
    Player player = proxy.getPlayer(entry.ticket.playerId()).filter(Player::isActive).orElse(null);
    if (player == null) {
      dequeue(entry.ticket.playerId(), false, MatchmakingTransitionStatus.DISCONNECTED);
      return;
    }

    RegisteredServer registered = proxy.getServer(instance.id()).orElse(null);
    if (registered == null) {
      fail(
          entry,
          new IllegalStateException(
              "Ready instance is not registered with Velocity: " + instance.id()),
          MatchmakingTransitionStatus.BACKEND_UNAVAILABLE);
      return;
    }
    if (!beginTransfer(entry)) {
      return;
    }
    publish(entry, MatchmakingTransitionStatus.TRANSFER_STARTED);
    entry.stopFeedback();
    actionBar.joining(player, instance.blueprint().name());
    entry.timings.transferStarted();
    try {
      player
          .createConnectionRequest(registered)
          .connect()
          .whenComplete((result, failure) -> finish(entry, result, failure));
    } catch (RuntimeException failure) {
      finish(entry, null, failure);
    }
  }

  private void finish(QueueEntry entry, ConnectionRequestBuilder.Result result, Throwable failure) {
    if (!remove(entry, QueueState.TRANSFERRING, QueueState.COMPLETE)) {
      return;
    }
    entry.cancelTimeout();
    boolean connected =
        failure == null
            && result != null
            && (result.getStatus() == ConnectionRequestBuilder.Status.SUCCESS
                || result.getStatus() == ConnectionRequestBuilder.Status.ALREADY_CONNECTED);
    boolean playerMoved =
        failure == null
            && result != null
            && result.getStatus() == ConnectionRequestBuilder.Status.SUCCESS;
    if (connected) {
      synchronized (this) {
        queueOwnedInstances.remove(entry.instance.id());
      }
    } else {
      stopOrphaned(entry.instance);
    }
    publish(
        entry,
        connected
            ? MatchmakingTransitionStatus.TRANSFER_SUCCEEDED
            : failure == null
                ? MatchmakingTransitionStatus.TRANSFER_REJECTED
                : MatchmakingTransitionStatus.TRANSFER_FAILED,
        playerMoved);
    timingReporter.connection(entry.instance.id(), entry.timings, result, failure);
    if (!connected) {
      notifyTerminalFailure(entry, "transfer");
    }
    if (failure == null) {
      entry.completion.complete(result);
    } else {
      entry.completion.completeExceptionally(failure);
    }
  }

  private void timeout(QueueEntry entry) {
    if (!remove(entry, QueueState.QUEUED, QueueState.TIMED_OUT)) {
      return;
    }
    String phase = ProvisioningFeedback.progress(entry.instance).toLowerCase(java.util.Locale.ROOT);
    entry.stopFeedback();
    timingReporter.complete(entry.instance.id(), entry.timings, "timeout");
    stopOrphaned(entry.instance);
    publish(entry, MatchmakingTransitionStatus.TIMED_OUT);
    notifyTerminalFailure(entry, phase);
    entry.completion.completeExceptionally(
        new TimeoutException(
            "Queue timed out after " + entry.queueTimeout.toSeconds() + " seconds"));
  }

  private void fail(QueueEntry entry, Throwable failure) {
    fail(entry, failure, MatchmakingTransitionStatus.INSTANCE_FAILED);
  }

  private void fail(
      QueueEntry entry, Throwable failure, MatchmakingTransitionStatus transitionStatus) {
    if (!remove(entry, QueueState.QUEUED, QueueState.FAILED)) {
      return;
    }
    entry.stopFeedback();
    entry.cancelTimeout();
    timingReporter.complete(entry.instance.id(), entry.timings, "failed");
    stopOrphaned(entry.instance);
    publish(entry, transitionStatus);
    notifyTerminalFailure(entry, ProvisioningFeedback.failure(entry.instance));
    entry.completion.completeExceptionally(failure);
  }

  private void notifyTerminalFailure(QueueEntry entry, String phase) {
    proxy
        .getPlayer(entry.ticket.playerId())
        .filter(Player::isActive)
        .ifPresent(
            player ->
                player.sendMessage(
                    Component.text("SLS-LITE: ", NamedTextColor.DARK_AQUA)
                        .append(
                            Component.text(
                                "Unable to connect; failed during "
                                    + phase
                                    + ". Ask an operator to check SLS-LITE logs; reference "
                                    + entry.instance.correlationId()
                                    + ".",
                                NamedTextColor.RED))));
  }

  private void cancel(
      QueueEntry entry, String message, MatchmakingTransitionStatus transitionStatus) {
    entry.stopFeedback();
    entry.cancelTimeout();
    publish(entry, transitionStatus);
    entry.completion.completeExceptionally(new QueueCancelledException(message));
    timingReporter.complete(entry.instance.id(), entry.timings, "cancelled");
  }

  private synchronized boolean beginTransfer(QueueEntry entry) {
    if (queue.get(entry.ticket.playerId()) != entry || entry.state != QueueState.QUEUED) {
      return false;
    }
    entry.state = QueueState.TRANSFERRING;
    return true;
  }

  private synchronized boolean remove(
      QueueEntry entry, QueueState expected, QueueState terminalState) {
    if (queue.get(entry.ticket.playerId()) != entry || entry.state != expected) {
      return false;
    }
    queue.remove(entry.ticket.playerId());
    entry.state = terminalState;
    return true;
  }

  private void stopOrphaned(ManagedInstance instance) {
    synchronized (this) {
      boolean stillQueued =
          queue.values().stream().anyMatch(entry -> entry.instance.id().equals(instance.id()));
      if (stillQueued || !queueOwnedInstances.remove(instance.id())) {
        return;
      }
    }
    try {
      instances.stop(instance.id());
    } catch (InstanceOperationException ignored) {
      // The process may have exited between queue removal and this stop request.
    }
  }

  private void publish(QueueEntry entry, MatchmakingTransitionStatus transitionStatus) {
    publish(entry, transitionStatus, false);
  }

  private void publish(
      QueueEntry entry, MatchmakingTransitionStatus transitionStatus, boolean playerMoved) {
    try {
      matchmakingObserver.accept(
          new MatchmakingTransition(
              entry.ticket,
              entry.instanceCreated,
              transitionStatus,
              playerMoved,
              entry.instance.blueprint(),
              Instant.now()));
    } catch (RuntimeException ignored) {
      // Extension observability cannot roll back an accepted matchmaking state change.
    }
  }

  private static ThreadFactory threadFactory() {
    return runnable -> {
      Thread thread = new Thread(runnable, "sls-lite-matchmaking");
      thread.setDaemon(true);
      return thread;
    };
  }

  public record QueueTicket(
      UUID playerId,
      String playerName,
      String registry,
      String server,
      String instanceId,
      Instant queuedAt) {}

  public record JoinAttempt(
      QueueTicket ticket,
      ManagedInstance instance,
      boolean created,
      CompletableFuture<ConnectionRequestBuilder.Result> connection) {}

  public record DirectJoin(
      ManagedInstance instance, CompletableFuture<ConnectionRequestBuilder.Result> connection) {}

  public enum ConnectionAdmission {
    UNMANAGED(true),
    AVAILABLE(true),
    RESERVED(true),
    FORCED(true),
    FULL(false),
    NOT_READY(false),
    SHUTDOWN(false);

    private final boolean allowed;

    ConnectionAdmission(boolean allowed) {
      this.allowed = allowed;
    }

    public boolean allowed() {
      return allowed;
    }
  }

  public record MatchmakingTransition(
      QueueTicket ticket,
      boolean instanceCreated,
      MatchmakingTransitionStatus status,
      boolean playerMoved,
      Blueprint blueprint,
      Instant occurredAt) {

    public MatchmakingTransition {
      ticket = java.util.Objects.requireNonNull(ticket, "ticket");
      status = java.util.Objects.requireNonNull(status, "status");
      occurredAt = java.util.Objects.requireNonNull(occurredAt, "occurredAt");
      if (playerMoved && status != MatchmakingTransitionStatus.TRANSFER_SUCCEEDED) {
        throw new IllegalArgumentException("A player move requires a successful transfer");
      }
      if (playerMoved) {
        blueprint = java.util.Objects.requireNonNull(blueprint, "blueprint");
      }
    }

    public MatchmakingTransition(
        QueueTicket ticket,
        boolean instanceCreated,
        MatchmakingTransitionStatus status,
        Instant occurredAt) {
      this(ticket, instanceCreated, status, false, null, occurredAt);
    }
  }

  public enum MatchmakingTransitionStatus {
    QUEUED,
    TRANSFER_STARTED,
    TRANSFER_SUCCEEDED,
    TRANSFER_REJECTED,
    TRANSFER_FAILED,
    CANCELLED,
    DISCONNECTED,
    TIMED_OUT,
    INSTANCE_FAILED,
    BACKEND_UNAVAILABLE,
    SHUTDOWN
  }

  private static final class QueueEntry {
    private final QueueTicket ticket;
    private final ManagedInstance instance;
    private final JoinPhaseTimings timings = new JoinPhaseTimings();
    private final boolean instanceCreated;
    private final Duration queueTimeout;
    private final CompletableFuture<ConnectionRequestBuilder.Result> completion =
        new CompletableFuture<>();
    private volatile ScheduledFuture<?> timeout;
    private volatile Runnable feedback = () -> {};
    private QueueState state = QueueState.QUEUED;

    private QueueEntry(
        QueueTicket ticket,
        ManagedInstance instance,
        boolean instanceCreated,
        Duration queueTimeout) {
      this.ticket = ticket;
      this.instance = instance;
      this.instanceCreated = instanceCreated;
      this.queueTimeout = queueTimeout;
    }

    private void cancelTimeout() {
      if (timeout != null) {
        timeout.cancel(false);
      }
    }

    private void stopFeedback() {
      feedback.run();
      feedback = () -> {};
    }
  }

  private enum QueueState {
    QUEUED,
    TRANSFERRING,
    COMPLETE,
    CANCELLED,
    TIMED_OUT,
    FAILED
  }

  private record AdmissionKey(UUID playerId, String instanceId) {}

  private static final class PendingAdmission {
    private final AdmissionKey key;
    private final boolean force;

    private PendingAdmission(AdmissionKey key, boolean force) {
      this.key = key;
      this.force = force;
    }
  }

  public static final class QueueCancelledException extends RuntimeException {
    private QueueCancelledException(String message) {
      super(message);
    }
  }
}
