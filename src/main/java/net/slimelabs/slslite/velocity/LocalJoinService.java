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
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import net.slimelabs.slslite.blueprint.Blueprint;
import net.slimelabs.slslite.blueprint.BlueprintRepository;
import net.slimelabs.slslite.blueprint.VSLSBlueprintAnnotations;
import net.slimelabs.slslite.instance.InstanceOperationException;
import net.slimelabs.slslite.instance.ManagedInstance;
import net.slimelabs.slslite.instance.ServerController;
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
        Executors.newSingleThreadScheduledExecutor(threadFactory()),
        logger,
        detailLog,
        blueprintSelection);
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
    if (queueTimeout.isZero() || queueTimeout.isNegative()) {
      throw new IllegalArgumentException("queueTimeout must be positive");
    }
    this.proxy = proxy;
    this.blueprints = blueprints;
    this.instances = instances;
    this.queueTimeout = queueTimeout;
    this.scheduler = scheduler;
    this.actionBar = new TransferActionBar(scheduler);
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
      entry = new QueueEntry(ticket, instance, created);
      queue.put(ticket.playerId(), entry);
      entry.timeout =
          scheduler.schedule(() -> timeout(entry), queueTimeout.toMillis(), TimeUnit.MILLISECONDS);
      actionBar.start(player);
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

  public synchronized Optional<QueueTicket> queued(UUID playerId) {
    QueueEntry entry = queue.get(playerId);
    return entry == null ? Optional.empty() : Optional.of(entry.ticket);
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
    if (instance.state() != InstanceState.READY) {
      throw new InstanceOperationException("Instance is not ready: " + instance.id());
    }
    RegisteredServer registered =
        proxy
            .getServer(instance.id())
            .orElseThrow(
                () ->
                    new InstanceOperationException(
                        "Managed instance is not registered with Velocity: " + instance.id()));
    if (!force
        && registered.getPlayersConnected().size() + queuedFor(instance.id())
            >= instance.blueprint().maxPlayers()) {
      throw new InstanceOperationException("Instance is full: " + instance.id());
    }
    if (force) {
      actionBar.forceJoining(player, instance.id());
    } else {
      actionBar.joining(player, instance.id());
    }
    JoinPhaseTimings timings = new JoinPhaseTimings(false);
    timings.backendReady();
    timings.transferStarted();
    CompletableFuture<ConnectionRequestBuilder.Result> connection =
        player.createConnectionRequest(registered).connect();
    connection.whenComplete(
        (result, failure) -> timingReporter.connection(instance.id(), timings, result, failure));
    return new DirectJoin(instance, connection);
  }

  public void connected(Player player, RegisteredServer server) {
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
                actionBar.dequeued(player);
              } else {
                actionBar.stop(playerId);
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
    return connected + queuedFor(instance.id());
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
    actionBar.stop(entry.ticket.playerId());
    timingReporter.complete(entry.instance.id(), entry.timings, "timeout");
    stopOrphaned(entry.instance);
    publish(entry, MatchmakingTransitionStatus.TIMED_OUT);
    entry.completion.completeExceptionally(
        new TimeoutException("Queue timed out after " + queueTimeout.toSeconds() + " seconds"));
  }

  private void fail(QueueEntry entry, Throwable failure) {
    fail(entry, failure, MatchmakingTransitionStatus.INSTANCE_FAILED);
  }

  private void fail(
      QueueEntry entry, Throwable failure, MatchmakingTransitionStatus transitionStatus) {
    if (!remove(entry, QueueState.QUEUED, QueueState.FAILED)) {
      return;
    }
    actionBar.stop(entry.ticket.playerId());
    entry.cancelTimeout();
    timingReporter.complete(entry.instance.id(), entry.timings, "failed");
    stopOrphaned(entry.instance);
    publish(entry, transitionStatus);
    entry.completion.completeExceptionally(failure);
  }

  private void cancel(
      QueueEntry entry, String message, MatchmakingTransitionStatus transitionStatus) {
    actionBar.stop(entry.ticket.playerId());
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
    private final CompletableFuture<ConnectionRequestBuilder.Result> completion =
        new CompletableFuture<>();
    private volatile ScheduledFuture<?> timeout;
    private QueueState state = QueueState.QUEUED;

    private QueueEntry(QueueTicket ticket, ManagedInstance instance, boolean instanceCreated) {
      this.ticket = ticket;
      this.instance = instance;
      this.instanceCreated = instanceCreated;
    }

    private void cancelTimeout() {
      if (timeout != null) {
        timeout.cancel(false);
      }
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

  public static final class QueueCancelledException extends RuntimeException {
    private QueueCancelledException(String message) {
      super(message);
    }
  }
}
