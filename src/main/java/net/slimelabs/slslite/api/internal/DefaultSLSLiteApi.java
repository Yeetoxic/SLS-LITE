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
import net.slimelabs.slslite.api.InstanceOperationResult;
import net.slimelabs.slslite.api.InstanceOverrides;
import net.slimelabs.slslite.api.InstanceStatus;
import net.slimelabs.slslite.api.InstanceView;
import net.slimelabs.slslite.api.QueueRequest;
import net.slimelabs.slslite.api.QueueResult;
import net.slimelabs.slslite.api.QueueTicket;
import net.slimelabs.slslite.api.SLSLiteApi;
import net.slimelabs.slslite.api.SLSLiteApiException;
import net.slimelabs.slslite.api.StartRequest;
import net.slimelabs.slslite.api.VolumeView;
import net.slimelabs.slslite.api.event.CatalogDelta;
import net.slimelabs.slslite.api.event.CatalogReloadEvent;
import net.slimelabs.slslite.api.event.CatalogReloadFailureCategory;
import net.slimelabs.slslite.api.event.CatalogReloadScope;
import net.slimelabs.slslite.api.event.CatalogReloadStatus;
import net.slimelabs.slslite.api.event.InstanceFailureCategory;
import net.slimelabs.slslite.api.event.InstanceFailureEvent;
import net.slimelabs.slslite.api.event.InstanceFailurePhase;
import net.slimelabs.slslite.api.event.InstanceLifecycleEvent;
import net.slimelabs.slslite.api.event.MatchmakingStatus;
import net.slimelabs.slslite.api.event.PlayerMatchmakingEvent;
import net.slimelabs.slslite.api.event.SLSLiteEvent;
import net.slimelabs.slslite.api.event.Subscription;
import net.slimelabs.slslite.blueprint.Blueprint;
import net.slimelabs.slslite.blueprint.BlueprintRepository;
import net.slimelabs.slslite.config.DefinitionReloader;
import net.slimelabs.slslite.instance.InstanceManager;
import net.slimelabs.slslite.instance.InstanceOperationException;
import net.slimelabs.slslite.instance.ManagedInstance;
import net.slimelabs.slslite.instance.lifecycle.InstanceLifecycle;
import net.slimelabs.slslite.instance.model.InstanceLaunchOverrides;
import net.slimelabs.slslite.velocity.LocalJoinService;
import org.slf4j.Logger;

/** Internal adapter that prevents public API consumers from receiving coordinator objects. */
public final class DefaultSLSLiteApi implements SLSLiteApi, AutoCloseable {

  private static final int MAX_SUBSCRIBERS = 128;
  private static final Set<Capability> CAPABILITIES = Set.of(Capability.values());

  private final ProxyServer proxy;
  private final Logger logger;
  private final AtomicReference<ApiStatus> status = new AtomicReference<>(ApiStatus.STARTING);
  private final CompletableFuture<Void> ready = new CompletableFuture<>();
  private final CopyOnWriteArrayList<Consumer<? super SLSLiteEvent>> subscribers =
      new CopyOnWriteArrayList<>();
  private final AtomicLong eventSequence = new AtomicLong();
  private final AtomicLong lastOverflowWarningNanos = new AtomicLong();
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

  public DefaultSLSLiteApi(ProxyServer proxy, Logger logger) {
    this.proxy = java.util.Objects.requireNonNull(proxy, "proxy");
    this.logger = java.util.Objects.requireNonNull(logger, "logger");
  }

  public synchronized void activate(
      BlueprintRepository blueprints, InstanceManager instances, LocalJoinService joins) {
    if (status.get() != ApiStatus.STARTING) {
      throw new IllegalStateException("API can only be activated once");
    }
    this.blueprints = java.util.Objects.requireNonNull(blueprints, "blueprints");
    this.instances = java.util.Objects.requireNonNull(instances, "instances");
    this.joins = java.util.Objects.requireNonNull(joins, "joins");
    instances.installLifecycleObserver(this::publish);
    instances.installFailureObserver(this::publish);
    joins.installMatchmakingObserver(this::publish);
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
    } catch (InstanceOperationException exception) {
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
    } catch (InstanceOperationException exception) {
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
    } catch (InstanceOperationException exception) {
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
    } catch (InstanceOperationException exception) {
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
    AtomicBoolean subscribed = new AtomicBoolean(true);
    return () -> {
      if (subscribed.compareAndSet(true, false)) {
        subscribers.remove(listener);
      }
    };
  }

  synchronized void publish(InstanceLifecycle.Transition transition) {
    InstanceLifecycleEvent event =
        new InstanceLifecycleEvent(
            eventSequence.incrementAndGet(),
            transition.occurredAt(),
            transition.instanceId(),
            status(transition.previous()),
            status(transition.current()));
    submit(event);
  }

  synchronized void publish(InstanceManager.InstanceFailureTransition transition) {
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
    submit(event);
  }

  public synchronized void publishCatalogReload(
      DefinitionReloader.DefinitionReloadTransition transition) {
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

  synchronized void publish(LocalJoinService.MatchmakingTransition transition) {
    PlayerMatchmakingEvent event =
        new PlayerMatchmakingEvent(
            eventSequence.incrementAndGet(),
            transition.occurredAt(),
            view(transition.ticket()),
            transition.instanceCreated(),
            MatchmakingStatus.valueOf(transition.status().name()));
    submit(event);
  }

  private void submit(SLSLiteEvent event) {
    try {
      eventExecutor.execute(() -> deliver(event));
    } catch (RejectedExecutionException exception) {
      if (status.get() != ApiStatus.CLOSED && shouldReportOverflow()) {
        logger.warn(
            "SLS-LITE API event queue is full; slow extension subscribers are dropping events");
      }
    }
  }

  private void deliver(SLSLiteEvent event) {
    for (Consumer<? super SLSLiteEvent> subscriber : subscribers) {
      try {
        subscriber.accept(event);
      } catch (RuntimeException exception) {
        subscribers.remove(subscriber);
        logger.warn("Disabled failing SLS-LITE API event subscriber: {}", exception.toString());
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

  private static SLSLiteApiException apiFailure(Throwable failure) {
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
            ? "Managed operation failed; inspect the SLS-LITE log using its correlation ID"
            : message;
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

  @Override
  public synchronized void close() {
    ApiStatus previous = status.getAndSet(ApiStatus.CLOSED);
    if (previous == ApiStatus.CLOSED) {
      return;
    }
    if (!ready.isDone()) {
      ready.completeExceptionally(
          new SLSLiteApiException(SLSLiteApiException.Code.CLOSED, "SLS-LITE API closed"));
    }
    eventExecutor.shutdown();
    try {
      if (!eventExecutor.awaitTermination(2, TimeUnit.SECONDS)) {
        eventExecutor.shutdownNow();
        logger.warn("SLS-LITE API event dispatcher did not stop within 2 seconds");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      eventExecutor.shutdownNow();
    }
    subscribers.clear();
  }
}
