package net.slimelabs.slslite.api.internal;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import net.slimelabs.slslite.api.ApiStatus;
import net.slimelabs.slslite.api.ApiVersion;
import net.slimelabs.slslite.api.BlueprintView;
import net.slimelabs.slslite.api.Capability;
import net.slimelabs.slslite.api.DeleteResult;
import net.slimelabs.slslite.api.DiagnosticsSnapshot;
import net.slimelabs.slslite.api.HostCapabilityState;
import net.slimelabs.slslite.api.HostCapabilityView;
import net.slimelabs.slslite.api.InstallationDiagnosticView;
import net.slimelabs.slslite.api.InstanceLogSnapshot;
import net.slimelabs.slslite.api.InstanceOperationResult;
import net.slimelabs.slslite.api.InstanceOverrides;
import net.slimelabs.slslite.api.InstanceStatisticsView;
import net.slimelabs.slslite.api.InstanceStatus;
import net.slimelabs.slslite.api.InstanceView;
import net.slimelabs.slslite.api.LobbyDiagnosticView;
import net.slimelabs.slslite.api.MaintenanceView;
import net.slimelabs.slslite.api.QueueRequest;
import net.slimelabs.slslite.api.QueueResult;
import net.slimelabs.slslite.api.QueueTicket;
import net.slimelabs.slslite.api.SLSLiteApi;
import net.slimelabs.slslite.api.SLSLiteApiException;
import net.slimelabs.slslite.api.StartRequest;
import net.slimelabs.slslite.api.SystemDiagnosticView;
import net.slimelabs.slslite.api.VolumeView;
import net.slimelabs.slslite.api.event.ApiShutdownEvent;
import net.slimelabs.slslite.api.event.CatalogDelta;
import net.slimelabs.slslite.api.event.CatalogReloadEvent;
import net.slimelabs.slslite.api.event.CatalogReloadFailureCategory;
import net.slimelabs.slslite.api.event.CatalogReloadScope;
import net.slimelabs.slslite.api.event.CatalogReloadStatus;
import net.slimelabs.slslite.api.event.InstanceFailureCategory;
import net.slimelabs.slslite.api.event.InstanceFailureEvent;
import net.slimelabs.slslite.api.event.InstanceFailurePhase;
import net.slimelabs.slslite.api.event.InstanceLifecycleEvent;
import net.slimelabs.slslite.api.event.LobbyRoute;
import net.slimelabs.slslite.api.event.LobbyServiceStatus;
import net.slimelabs.slslite.api.event.LobbyStatusEvent;
import net.slimelabs.slslite.api.event.MatchmakingStatus;
import net.slimelabs.slslite.api.event.PlayerMatchmakingEvent;
import net.slimelabs.slslite.api.event.ReconciliationEvent;
import net.slimelabs.slslite.api.event.SLSLiteEvent;
import net.slimelabs.slslite.api.event.SoftwareInstallationEvent;
import net.slimelabs.slslite.api.event.SoftwareInstallationFailureCategory;
import net.slimelabs.slslite.api.event.SoftwareInstallationSource;
import net.slimelabs.slslite.api.event.SoftwareInstallationStatus;
import net.slimelabs.slslite.api.event.SoftwareReleaseChannel;
import net.slimelabs.slslite.api.event.Subscription;
import net.slimelabs.slslite.blueprint.Blueprint;
import net.slimelabs.slslite.blueprint.BlueprintRepository;
import net.slimelabs.slslite.config.DefinitionReloader;
import net.slimelabs.slslite.host.HostCapabilityReport;
import net.slimelabs.slslite.install.SoftwareInstallationService;
import net.slimelabs.slslite.instance.InstanceManager;
import net.slimelabs.slslite.instance.InstanceOperationException;
import net.slimelabs.slslite.instance.ManagedInstance;
import net.slimelabs.slslite.instance.lifecycle.InstanceLifecycle;
import net.slimelabs.slslite.instance.model.InstanceLaunchOverrides;
import net.slimelabs.slslite.instance.reconcile.InstanceReconciliationReport;
import net.slimelabs.slslite.lobby.LobbyProvider;
import net.slimelabs.slslite.log.DiagnosticMessages;
import net.slimelabs.slslite.velocity.LocalJoinService;
import org.slf4j.Logger;

/** Internal adapter that prevents public API consumers from receiving coordinator objects. */
public final class DefaultSLSLiteApi implements SLSLiteApi, AutoCloseable {

  private static final int MAX_SUBSCRIBERS = 128;
  private static final int MAX_EXTENSION_CONTEXTS = 128;
  private static final Set<Capability> CAPABILITIES = Set.of(Capability.values());

  private final ProxyServer proxy;
  private final Logger logger;
  private final AtomicReference<ApiStatus> status = new AtomicReference<>(ApiStatus.STARTING);
  private final CompletableFuture<Void> ready = new CompletableFuture<>();
  private final CopyOnWriteArrayList<Consumer<? super SLSLiteEvent>> subscribers =
      new CopyOnWriteArrayList<>();
  private final AtomicLong eventSequence = new AtomicLong();
  private final AtomicLong lastOverflowWarningNanos = new AtomicLong();
  private final java.util.concurrent.ConcurrentHashMap<String, DefaultExtensionContext>
      extensionContexts = new java.util.concurrent.ConcurrentHashMap<>();
  private final ThreadPoolExecutor eventExecutor =
      new ThreadPoolExecutor(
          1,
          1,
          0L,
          TimeUnit.MILLISECONDS,
          new ArrayBlockingQueue<>(1024),
          runnable -> {
            Thread thread = new Thread(runnable, "sls-lite-api-events");
            thread.setDaemon(true);
            return thread;
          },
          new ThreadPoolExecutor.AbortPolicy());

  private volatile BlueprintRepository blueprints;
  private volatile InstanceManager instances;
  private volatile LocalJoinService joins;
  private volatile LobbyProvider lobby;
  private volatile SoftwareInstallationService installations;
  private volatile HostCapabilityReport hostCapabilities;
  private volatile ReconciliationEvent reconciliation;
  private volatile LobbyStatusEvent latestLobbyStatus;
  private final java.util.ArrayDeque<InstanceFailureEvent> recentFailures =
      new java.util.ArrayDeque<>(64);

  public DefaultSLSLiteApi(ProxyServer proxy, Logger logger) {
    this.proxy = java.util.Objects.requireNonNull(proxy, "proxy");
    this.logger = java.util.Objects.requireNonNull(logger, "logger");
  }

  public synchronized void activate(
      BlueprintRepository blueprints,
      InstanceManager instances,
      LocalJoinService joins,
      LobbyProvider lobby,
      SoftwareInstallationService installations,
      HostCapabilityReport hostCapabilities) {
    if (status.get() != ApiStatus.STARTING) {
      throw new IllegalStateException("API can only be activated once");
    }
    this.blueprints = java.util.Objects.requireNonNull(blueprints, "blueprints");
    this.instances = java.util.Objects.requireNonNull(instances, "instances");
    this.joins = java.util.Objects.requireNonNull(joins, "joins");
    this.lobby = java.util.Objects.requireNonNull(lobby, "lobby");
    this.installations = java.util.Objects.requireNonNull(installations, "installations");
    this.hostCapabilities = java.util.Objects.requireNonNull(hostCapabilities, "hostCapabilities");
    instances.installLifecycleObserver(this::publish);
    instances.installReadyObserver(this::publishReady);
    instances.installFailureObserver(this::publish);
    joins.installMatchmakingObserver(this::publish);
    lobby.installStatusObserver(this::publish);
    installations.installObserver(this::publish);
    status.set(ApiStatus.READY);
    ready.complete(null);
  }

  public synchronized void fail() {
    if (!status.compareAndSet(ApiStatus.STARTING, ApiStatus.FAILED)) {
      return;
    }
    ready.completeExceptionally(
        new SLSLiteApiException(
            SLSLiteApiException.Code.NOT_READY,
            "SLS-LITE initialization failed; the public API is unavailable"));
  }

  public synchronized void recordReconciliation(
      InstanceReconciliationReport report, String correlationId) {
    java.util.Objects.requireNonNull(report, "report");
    if (status.get() == ApiStatus.CLOSED) {
      return;
    }
    if (reconciliation != null) {
      throw new IllegalStateException("Startup reconciliation was already recorded");
    }
    ReconciliationEvent event =
        new ReconciliationEvent(
            eventSequence.incrementAndGet(),
            java.time.Instant.now(),
            correlationId,
            report.recoveredStorageTransactions(),
            report.removedEphemeral(),
            report.preservedPersistent(),
            report.preservedRunning(),
            report.preservedUnknown(),
            report.failures());
    reconciliation = event;
    if (!subscribers.isEmpty()) {
      submit(event);
    }
  }

  @Override
  public ApiVersion version() {
    return ApiVersion.CURRENT;
  }

  @Override
  public ApiStatus status() {
    return status.get();
  }

  @Override
  public CompletionStage<Void> ready() {
    return ready.thenApply(ignored -> null);
  }

  @Override
  public Set<Capability> capabilities() {
    return CAPABILITIES;
  }

  @Override
  public synchronized net.slimelabs.slslite.api.ExtensionContext extension(String namespace) {
    if (status.get() == ApiStatus.CLOSED) {
      throw new SLSLiteApiException(SLSLiteApiException.Code.CLOSED, "SLS-LITE API is closed");
    }
    String normalized = validateNamespace(namespace);
    if (extensionContexts.size() >= MAX_EXTENSION_CONTEXTS) {
      throw new SLSLiteApiException(
          SLSLiteApiException.Code.REJECTED, "SLS-LITE extension context limit reached");
    }
    DefaultExtensionContext context = new DefaultExtensionContext(normalized, this, logger);
    DefaultExtensionContext existing = extensionContexts.putIfAbsent(normalized, context);
    if (existing != null) {
      throw new SLSLiteApiException(
          SLSLiteApiException.Code.CONFLICT,
          "An extension context is already registered for " + normalized);
    }
    return context;
  }

  void release(DefaultExtensionContext context) {
    extensionContexts.remove(context.namespace(), context);
  }

  boolean closed() {
    return status.get() == ApiStatus.CLOSED;
  }

  @Override
  public DiagnosticsSnapshot diagnostics() {
    requireReady();
    var allManaged = instances.getAll();
    List<ManagedInstance> managed = allManaged.stream().limit(256).toList();
    List<InstallationDiagnosticView> installationViews =
        installations.snapshots().stream()
            .map(
                snapshot ->
                    new InstallationDiagnosticView(
                        snapshot.key().softwareId(),
                        snapshot.key().version(),
                        switch (snapshot.state()) {
                          case INSTALLING -> SoftwareInstallationStatus.STARTED;
                          case READY -> SoftwareInstallationStatus.READY;
                          case FAILED -> SoftwareInstallationStatus.FAILED;
                        },
                        snapshot.startedAt(),
                        Optional.ofNullable(snapshot.completedAt()),
                        safe(snapshot.detail()),
                        tail(snapshot.logs(), 20).stream().map(DefaultSLSLiteApi::safe).toList()))
            .toList();
    List<HostCapabilityView> capabilityViews =
        hostCapabilities.capabilities().stream()
            .limit(64)
            .map(
                capability ->
                    new HostCapabilityView(
                        safe(capability.name()),
                        HostCapabilityState.valueOf(capability.status().name()),
                        safe(capability.detail())))
            .toList();
    var maintenance = instances.maintenanceStatus();
    return new DiagnosticsSnapshot(
        java.time.Instant.now(),
        new SystemDiagnosticView(
            status.get(),
            blueprints.getAll().size(),
            allManaged.size(),
            joins.queuedPlayers().size()),
        new MaintenanceView(
            maintenance.enabled(), maintenance.changedAt(), safe(maintenance.reason())),
        lobbyDiagnostics(),
        installationViews,
        capabilityViews,
        managed.stream().map(this::statistics).toList(),
        managed.stream().map(DefaultSLSLiteApi::logs).toList(),
        recentFailureSnapshot());
  }

  private synchronized List<InstanceFailureEvent> recentFailureSnapshot() {
    return List.copyOf(recentFailures);
  }

  @Override
  public List<BlueprintView> blueprints() {
    requireReady();
    return blueprints.getAll().stream().map(DefaultSLSLiteApi::view).toList();
  }

  @Override
  public Optional<BlueprintView> blueprint(String blueprintId) {
    requireReady();
    requireId(blueprintId, "blueprintId");
    return blueprints.get(blueprintId).map(DefaultSLSLiteApi::view);
  }

  @Override
  public List<InstanceView> instances() {
    requireReady();
    return instances.getAll().stream().map(this::view).toList();
  }

  @Override
  public Optional<InstanceView> instance(String instanceId) {
    requireReady();
    requireId(instanceId, "instanceId");
    return instances.getAll().stream()
        .filter(instance -> instance.id().equals(instanceId))
        .findFirst()
        .map(this::view);
  }

  @Override
  public CompletionStage<InstanceView> start(StartRequest request) {
    requireReady();
    java.util.Objects.requireNonNull(request, "request");
    try {
      ManagedInstance instance =
          instances.create(request.blueprintId(), internal(request.overrides()));
      return map(instance.readyFuture().thenApply(this::view));
    } catch (Exception exception) {
      return CompletableFuture.failedFuture(apiFailure(exception));
    }
  }

  @Override
  public CompletionStage<InstanceOperationResult> stop(String instanceId) {
    requireReady();
    requireId(instanceId, "instanceId");
    try {
      instances.get(instanceId);
      return map(
          instances
              .stop(instanceId)
              .thenApply(
                  ignored -> new InstanceOperationResult(instanceId, InstanceStatus.STOPPED)));
    } catch (Exception exception) {
      return CompletableFuture.failedFuture(apiFailure(exception));
    }
  }

  @Override
  public CompletionStage<DeleteResult> delete(String instanceId) {
    requireReady();
    requireId(instanceId, "instanceId");
    try {
      return map(
          instances
              .delete(instanceId)
              .thenApply(
                  result -> new DeleteResult(result.instanceId(), result.tombstoneCleaned())));
    } catch (Exception exception) {
      return CompletableFuture.failedFuture(apiFailure(exception));
    }
  }

  @Override
  public CompletionStage<QueueResult> enqueue(QueueRequest request) {
    requireReady();
    java.util.Objects.requireNonNull(request, "request");
    var player = proxy.getPlayer(request.playerId()).orElse(null);
    if (player == null) {
      return CompletableFuture.failedFuture(
          new SLSLiteApiException(
              SLSLiteApiException.Code.PLAYER_OFFLINE,
              "Player is not connected to this Velocity proxy"));
    }
    try {
      LocalJoinService.JoinAttempt attempt =
          joins.join(player, request.registry(), request.blueprintId());
      QueueTicket ticket = view(attempt.ticket());
      return map(
          attempt
              .connection()
              .thenApply(result -> new QueueResult(ticket, attempt.created(), connected(result))));
    } catch (Exception exception) {
      return CompletableFuture.failedFuture(apiFailure(exception));
    }
  }

  @Override
  public Optional<QueueTicket> queued(UUID playerId) {
    requireReady();
    return joins
        .queued(java.util.Objects.requireNonNull(playerId, "playerId"))
        .map(DefaultSLSLiteApi::view);
  }

  @Override
  public Optional<QueueTicket> dequeue(UUID playerId) {
    requireReady();
    return joins
        .dequeue(java.util.Objects.requireNonNull(playerId, "playerId"))
        .map(DefaultSLSLiteApi::view);
  }

  @Override
  public synchronized Subscription subscribe(Consumer<? super SLSLiteEvent> listener) {
    java.util.Objects.requireNonNull(listener, "listener");
    if (status.get() == ApiStatus.CLOSED) {
      throw new SLSLiteApiException(SLSLiteApiException.Code.CLOSED, "SLS-LITE API is closed");
    }
    if (subscribers.size() >= MAX_SUBSCRIBERS) {
      throw new SLSLiteApiException(
          SLSLiteApiException.Code.REJECTED, "SLS-LITE API subscriber limit reached");
    }
    subscribers.add(listener);
    ReconciliationEvent retained = reconciliation;
    if (retained != null) {
      submit(retained, List.of(listener), false);
    }
    AtomicBoolean subscribed = new AtomicBoolean(true);
    return () -> {
      if (subscribed.compareAndSet(true, false)) {
        subscribers.remove(listener);
      }
    };
  }

  synchronized void publish(InstanceLifecycle.Transition transition) {
    if (status.get() == ApiStatus.CLOSED) {
      return;
    }
    InstanceLifecycleEvent event =
        new InstanceLifecycleEvent(
            eventSequence.incrementAndGet(),
            transition.occurredAt(),
            transition.instanceId(),
            status(transition.previous()),
            status(transition.current()));
    submit(event);
  }

  synchronized void publishReady(InstanceManager.RegisteredReady ready) {
    if (status.get() == ApiStatus.CLOSED) {
      return;
    }
    if (!extensionContexts.isEmpty()) {
      submitInstanceReadyActions(ready.instance(), ready.occurredAt());
    }
  }

  synchronized void publish(InstanceManager.InstanceFailureTransition transition) {
    if (status.get() == ApiStatus.CLOSED) {
      return;
    }
    InstanceFailureEvent event =
        new InstanceFailureEvent(
            eventSequence.incrementAndGet(),
            transition.occurredAt(),
            transition.instanceId(),
            transition.blueprintId(),
            transition.blueprintType(),
            transition.correlationId(),
            InstanceFailurePhase.valueOf(transition.phase().name()),
            InstanceFailureCategory.valueOf(transition.category().name()));
    if (recentFailures.size() == 64) {
      recentFailures.removeFirst();
    }
    recentFailures.addLast(event);
    submit(event);
  }

  public synchronized void publishCatalogReload(
      DefinitionReloader.DefinitionReloadTransition transition) {
    if (status.get() == ApiStatus.CLOSED) {
      return;
    }
    CatalogReloadEvent event =
        new CatalogReloadEvent(
            eventSequence.incrementAndGet(),
            transition.occurredAt(),
            transition.correlationId(),
            CatalogReloadScope.valueOf(transition.scope().name()),
            CatalogReloadStatus.valueOf(transition.status().name()),
            CatalogReloadFailureCategory.valueOf(transition.failureCategory().name()),
            new CatalogDelta(
                transition.blueprintsAdded(),
                transition.blueprintsUpdated(),
                transition.blueprintsRemoved()),
            new CatalogDelta(
                transition.softwareAdded(),
                transition.softwareUpdated(),
                transition.softwareRemoved()));
    submit(event);
  }

  synchronized void publish(LobbyProvider.LobbyStatusTransition transition) {
    if (status.get() == ApiStatus.CLOSED) {
      return;
    }
    LobbyProvider.LobbyStatusSnapshot snapshot = transition.snapshot();
    LobbyStatusEvent event =
        new LobbyStatusEvent(
            eventSequence.incrementAndGet(),
            transition.occurredAt(),
            LobbyServiceStatus.valueOf(snapshot.primaryStatus().name()),
            LobbyServiceStatus.valueOf(snapshot.holdingStatus().name()),
            LobbyRoute.valueOf(snapshot.route().name()));
    latestLobbyStatus = event;
    submit(event);
  }

  synchronized void publish(SoftwareInstallationService.InstallationTransition transition) {
    if (status.get() == ApiStatus.CLOSED) {
      return;
    }
    SoftwareInstallationEvent event =
        new SoftwareInstallationEvent(
            eventSequence.incrementAndGet(),
            transition.occurredAt(),
            transition.key().softwareId(),
            transition.key().version(),
            SoftwareInstallationSource.valueOf(transition.source().name()),
            SoftwareReleaseChannel.valueOf(transition.channel().name()),
            SoftwareInstallationStatus.valueOf(transition.status().name()),
            SoftwareInstallationFailureCategory.valueOf(transition.failureCategory().name()));
    submit(event);
  }

  synchronized void publish(LocalJoinService.MatchmakingTransition transition) {
    if (status.get() == ApiStatus.CLOSED) {
      return;
    }
    PlayerMatchmakingEvent event =
        new PlayerMatchmakingEvent(
            eventSequence.incrementAndGet(),
            transition.occurredAt(),
            view(transition.ticket()),
            transition.instanceCreated(),
            MatchmakingStatus.valueOf(transition.status().name()));
    submit(event);
    if (transition.playerMoved() && !extensionContexts.isEmpty()) {
      submitPostTransferActions(
          event.ticket(),
          transition.instanceCreated(),
          view(transition.blueprint()),
          transition.occurredAt());
    }
  }

  private void submitInstanceReadyActions(ManagedInstance instance, java.time.Instant occurredAt) {
    InstanceView instanceView = view(instance);
    BlueprintView blueprintView = view(instance.blueprint());
    List<Runnable> deliveries =
        extensionContexts.values().stream()
            .sorted(java.util.Comparator.comparing(DefaultExtensionContext::namespace))
            .map(
                context -> {
                  try {
                    return context.captureInstanceReady(instanceView, blueprintView, occurredAt);
                  } catch (RuntimeException exception) {
                    logger.warn(
                        "Skipped invalid instance-ready extension action payload for {}: {}",
                        context.namespace(),
                        exception.getClass().getSimpleName());
                    return null;
                  }
                })
            .filter(java.util.Objects::nonNull)
            .toList();
    submitActions(deliveries);
  }

  private void submitPostTransferActions(
      QueueTicket ticket,
      boolean instanceCreated,
      BlueprintView blueprint,
      java.time.Instant occurredAt) {
    List<Runnable> deliveries =
        extensionContexts.values().stream()
            .sorted(java.util.Comparator.comparing(DefaultExtensionContext::namespace))
            .map(
                context -> {
                  try {
                    return context.capturePostTransfer(
                        ticket, instanceCreated, blueprint, occurredAt);
                  } catch (RuntimeException exception) {
                    logger.warn(
                        "Skipped invalid post-transfer extension action payload for {}: {}",
                        context.namespace(),
                        exception.getClass().getSimpleName());
                    return null;
                  }
                })
            .filter(java.util.Objects::nonNull)
            .toList();
    submitActions(deliveries);
  }

  private void submitActions(List<Runnable> deliveries) {
    if (deliveries.isEmpty()) {
      return;
    }
    try {
      eventExecutor.execute(() -> deliveries.forEach(Runnable::run));
    } catch (RejectedExecutionException exception) {
      if (status.get() != ApiStatus.CLOSED && shouldReportOverflow()) {
        logger.warn(
            "SLS-LITE API event/action queue is full; slow extensions are dropping callbacks");
      }
    }
  }

  private void submit(SLSLiteEvent event) {
    submit(event, List.copyOf(subscribers), false);
  }

  private void submit(
      SLSLiteEvent event, List<Consumer<? super SLSLiteEvent>> recipients, boolean terminal) {
    Runnable delivery = () -> deliver(event, recipients);
    try {
      eventExecutor.execute(delivery);
    } catch (RejectedExecutionException exception) {
      if (terminal && eventExecutor.getQueue().poll() != null) {
        try {
          eventExecutor.execute(delivery);
          return;
        } catch (RejectedExecutionException ignored) {
          // The dispatcher was concurrently forced down after the terminal event was created.
        }
      }
      if (status.get() != ApiStatus.CLOSED && shouldReportOverflow()) {
        logger.warn(
            "SLS-LITE API event queue is full; slow extension subscribers are dropping events");
      }
    }
  }

  private void deliver(SLSLiteEvent event, List<Consumer<? super SLSLiteEvent>> recipients) {
    for (Consumer<? super SLSLiteEvent> subscriber : recipients) {
      if (!subscribers.contains(subscriber)) {
        continue;
      }
      try {
        subscriber.accept(event);
      } catch (RuntimeException exception) {
        subscribers.remove(subscriber);
        logger.warn(
            "Disabled failing SLS-LITE API event subscriber: {}",
            exception.getClass().getSimpleName());
      }
    }
  }

  private boolean shouldReportOverflow() {
    long now = System.nanoTime();
    long previous = lastOverflowWarningNanos.get();
    long interval = TimeUnit.MINUTES.toNanos(1);
    return (previous == 0L || now - previous >= interval)
        && lastOverflowWarningNanos.compareAndSet(previous, now);
  }

  private InstanceView view(ManagedInstance instance) {
    int players =
        proxy.getServer(instance.id()).map(server -> server.getPlayersConnected().size()).orElse(0);
    return new InstanceView(
        instance.id(),
        instance.blueprint().id(),
        instance.blueprint().type(),
        status(instance.state()),
        instance.port(),
        instance.blueprint().memoryLimitMiB(),
        players,
        instance.blueprint().save(),
        instance.createdAt(),
        instance.correlationId());
  }

  private InstanceStatisticsView statistics(ManagedInstance instance) {
    int players =
        proxy.getServer(instance.id()).map(server -> server.getPlayersConnected().size()).orElse(0);
    var resources = instance.processResources();
    return new InstanceStatisticsView(
        instance.id(),
        status(instance.state()),
        players,
        instance.retainedLogLines(),
        instance.logRetentionCapacity(),
        instance.processCpuTime(),
        resources
            .map(ManagedInstance.ProcessResourceSnapshot::residentBytes)
            .orElseGet(java.util.OptionalLong::empty),
        resources
            .map(ManagedInstance.ProcessResourceSnapshot::storageBytesRead)
            .orElseGet(java.util.OptionalLong::empty),
        resources
            .map(ManagedInstance.ProcessResourceSnapshot::storageBytesWritten)
            .orElseGet(java.util.OptionalLong::empty));
  }

  private static InstanceLogSnapshot logs(ManagedInstance instance) {
    var page = instance.logs(1, 20);
    return new InstanceLogSnapshot(
        instance.id(),
        page.lines().stream().map(DefaultSLSLiteApi::safe).toList(),
        page.totalRetainedLines(),
        page.retentionCapacity());
  }

  private LobbyDiagnosticView lobbyDiagnostics() {
    LobbyStatusEvent current = latestLobbyStatus;
    var limbo = lobby.limboDiagnostics();
    LobbyServiceStatus primary;
    LobbyServiceStatus holding;
    LobbyRoute route;
    if (current != null) {
      primary = current.primaryStatus();
      holding = current.holdingStatus();
      route = current.route();
    } else {
      primary = LobbyServiceStatus.valueOf(lobby.status().name());
      holding =
          limbo
              .map(value -> LobbyServiceStatus.valueOf(value.status().name()))
              .orElse(LobbyServiceStatus.OFFLINE);
      route =
          lobby.primaryServer().isPresent()
              ? LobbyRoute.PRIMARY
              : lobby.server().isPresent() ? LobbyRoute.HOLDING : LobbyRoute.NONE;
    }
    return new LobbyDiagnosticView(
        primary,
        holding,
        route,
        limbo.map(value -> value.enabled()).orElse(false),
        limbo.map(value -> value.recoveryAttempts()).orElse(0),
        limbo.map(value -> value.maxRecoveryAttempts()).orElse(0),
        limbo.flatMap(value -> value.lastFailure()).map(DefaultSLSLiteApi::safe).orElse(""));
  }

  private static List<String> tail(List<String> values, int maximum) {
    int start = Math.max(0, values.size() - maximum);
    return List.copyOf(values.subList(start, values.size()));
  }

  private static String safe(String value) {
    return value == null || value.isBlank() ? "" : DiagnosticMessages.safe(value);
  }

  private static BlueprintView view(Blueprint blueprint) {
    return new BlueprintView(
        blueprint.id(),
        blueprint.name(),
        blueprint.type(),
        blueprint.software(),
        blueprint.version(),
        blueprint.memoryLimitMiB(),
        blueprint.maxPlayers(),
        blueprint.maxInstances(),
        blueprint.save(),
        blueprint.volumes().stream()
            .map(
                volume ->
                    new VolumeView(
                        volume.name(),
                        volume.source(),
                        volume.target(),
                        volume.mode().name().toLowerCase(java.util.Locale.ROOT)))
            .toList(),
        !blueprint.copies().isEmpty(),
        blueprint.environment().keySet(),
        blueprint.annotations());
  }

  private static QueueTicket view(LocalJoinService.QueueTicket ticket) {
    return new QueueTicket(
        ticket.playerId(),
        ticket.playerName(),
        ticket.registry(),
        ticket.server(),
        ticket.instanceId(),
        ticket.queuedAt());
  }

  private static InstanceLaunchOverrides internal(InstanceOverrides overrides) {
    return new InstanceLaunchOverrides(
        overrides.memoryLimitMiB(),
        overrides.persistent(),
        overrides.seed(),
        overrides.viewDistance(),
        overrides.simulationDistance(),
        overrides.enableCommandBlock());
  }

  private static InstanceStatus status(net.slimelabs.slslite.instance.model.InstanceState state) {
    return InstanceStatus.valueOf(state.name());
  }

  private static boolean connected(ConnectionRequestBuilder.Result result) {
    return result.isSuccessful()
        || result.getStatus() == ConnectionRequestBuilder.Status.ALREADY_CONNECTED;
  }

  private static <T> CompletionStage<T> map(CompletionStage<T> source) {
    CompletableFuture<T> mapped = new CompletableFuture<>();
    source.whenComplete(
        (value, failure) -> {
          if (failure == null) {
            mapped.complete(value);
          } else {
            Throwable root = rootCause(failure);
            mapped.completeExceptionally(
                root instanceof SLSLiteApiException apiException ? apiException : apiFailure(root));
          }
        });
    return mapped;
  }

  static SLSLiteApiException apiFailure(Throwable failure) {
    if (failure instanceof SLSLiteApiException apiException) {
      return apiException;
    }
    String message =
        failure.getMessage() == null || failure.getMessage().isBlank()
            ? "SLS-LITE operation failed"
            : failure.getMessage();
    String normalized = message.toLowerCase(java.util.Locale.ROOT);
    SLSLiteApiException.Code code =
        normalized.contains("unknown") || normalized.contains("not found")
            ? SLSLiteApiException.Code.NOT_FOUND
            : normalized.contains("already")
                    || normalized.contains("in progress")
                    || normalized.contains("limit")
                ? SLSLiteApiException.Code.CONFLICT
                : normalized.contains("cancel") || normalized.contains("timeout")
                    ? SLSLiteApiException.Code.REJECTED
                    : failure instanceof InstanceOperationException
                        ? SLSLiteApiException.Code.REJECTED
                        : SLSLiteApiException.Code.INTERNAL;
    String publicMessage =
        code == SLSLiteApiException.Code.INTERNAL
            ? "Managed operation failed; inspect the SLS-LITE logs for correlated details"
            : DiagnosticMessages.safe(message);
    return new SLSLiteApiException(code, publicMessage);
  }

  private static Throwable rootCause(Throwable failure) {
    Throwable current = failure;
    while ((current instanceof CompletionException
            || current instanceof java.util.concurrent.ExecutionException)
        && current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }

  private void requireReady() {
    ApiStatus current = status.get();
    if (current == ApiStatus.READY) {
      return;
    }
    SLSLiteApiException.Code code =
        current == ApiStatus.CLOSED
            ? SLSLiteApiException.Code.CLOSED
            : SLSLiteApiException.Code.NOT_READY;
    throw new SLSLiteApiException(code, "SLS-LITE API is " + current.name().toLowerCase());
  }

  private static void requireId(String id, String field) {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }

  private static String validateNamespace(String namespace) {
    if (namespace == null) {
      throw new IllegalArgumentException("namespace must not be null");
    }
    String normalized = namespace.strip().toLowerCase(java.util.Locale.ROOT);
    if (!normalized.matches("[a-z][a-z0-9._-]{0,63}")) {
      throw new IllegalArgumentException("namespace must match [a-z][a-z0-9._-]{0,63}");
    }
    return normalized;
  }

  @Override
  public void close() {
    synchronized (this) {
      ApiStatus previous = status.getAndSet(ApiStatus.CLOSED);
      if (previous == ApiStatus.CLOSED) {
        return;
      }
      if (!ready.isDone()) {
        ready.completeExceptionally(
            new SLSLiteApiException(SLSLiteApiException.Code.CLOSED, "SLS-LITE API closed"));
      }
      submit(
          new ApiShutdownEvent(eventSequence.incrementAndGet(), java.time.Instant.now()),
          List.copyOf(subscribers),
          true);
      eventExecutor.shutdown();
    }
    try {
      if (!eventExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
        eventExecutor.shutdownNow();
        logger.warn("SLS-LITE API event dispatcher did not stop within 2 seconds");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      eventExecutor.shutdownNow();
    }
    extensionContexts.values().forEach(DefaultExtensionContext::closeFromApi);
    extensionContexts.clear();
    subscribers.clear();
  }
}
