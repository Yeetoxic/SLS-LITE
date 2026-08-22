package net.slimelabs.slslite.instance;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;
import net.slimelabs.slslite.blueprint.Blueprint;
import net.slimelabs.slslite.blueprint.BlueprintRepository;
import net.slimelabs.slslite.blueprint.readiness.BlueprintReadinessCatalog;
import net.slimelabs.slslite.config.DefinitionCatalog;
import net.slimelabs.slslite.config.ForwardingConfig;
import net.slimelabs.slslite.config.ManagedOutputConfig;
import net.slimelabs.slslite.install.InstallationKey;
import net.slimelabs.slslite.install.SoftwareInstallationException;
import net.slimelabs.slslite.install.SoftwareInstallationService;
import net.slimelabs.slslite.instance.configuration.InstanceLaunchConfigurator;
import net.slimelabs.slslite.instance.diagnostics.FailedStartDiagnostics;
import net.slimelabs.slslite.instance.diagnostics.FailurePhase;
import net.slimelabs.slslite.instance.diagnostics.InstanceTimingReporter;
import net.slimelabs.slslite.instance.lifecycle.InstanceCrashRecovery;
import net.slimelabs.slslite.instance.lifecycle.InstanceLifecycle;
import net.slimelabs.slslite.instance.lifecycle.InstancePhaseTimings;
import net.slimelabs.slslite.instance.lifecycle.MaintenanceStatus;
import net.slimelabs.slslite.instance.metadata.InstanceMetadataService;
import net.slimelabs.slslite.instance.model.InstanceDefinitionIdentity;
import net.slimelabs.slslite.instance.model.InstanceIdGenerator;
import net.slimelabs.slslite.instance.model.InstanceLaunchOverrides;
import net.slimelabs.slslite.instance.model.InstanceMetadata;
import net.slimelabs.slslite.instance.model.InstanceState;
import net.slimelabs.slslite.instance.storage.InstanceDirectoryPreparer;
import net.slimelabs.slslite.log.DiagnosticRedactor;
import net.slimelabs.slslite.log.SLSDetailLog;
import net.slimelabs.slslite.network.LoopbackPortAllocator;
import net.slimelabs.slslite.network.PortAllocationException;
import net.slimelabs.slslite.process.JavaJarProcessSpecFactory;
import net.slimelabs.slslite.process.ProcessStartException;
import net.slimelabs.slslite.process.ProcessSupervisor;
import net.slimelabs.slslite.process.SupervisedProcess;
import net.slimelabs.slslite.resource.ResourceBudget;
import net.slimelabs.slslite.software.SoftwareProfile;
import net.slimelabs.slslite.software.SoftwareProfileRepository;
import net.slimelabs.slslite.velocity.BackendRegistry;
import org.slf4j.Logger;

public final class InstanceManager implements ServerController {

  private static final int ID_ATTEMPTS = 20;

  private final BlueprintRepository blueprints;
  private final SoftwareProfileRepository softwareProfiles;
  private final ResourceBudget resourceBudget;
  private final ManagedOutputConfig outputConfig;
  private final int consoleTailLines;
  private final LoopbackPortAllocator portAllocator;
  private final InstanceDirectoryPreparer directoryPreparer;
  private final InstanceMetadataService metadata;
  private final SoftwareBaseDirectoryResolver softwareDirectories;
  private final InstanceLaunchConfigurator launchConfigurator;
  private final ProcessSupervisor processSupervisor;
  private final BackendRegistry backendRegistry;
  private final FailedStartDiagnostics failedStartDiagnostics;
  private final DiagnosticRedactor diagnosticRedactor;
  private final InstanceTimingReporter timingReporter;
  private final Logger logger;
  private final SLSDetailLog detailLog;
  private final InstanceIdGenerator idGenerator;
  private final ThreadPoolExecutor operationExecutor;
  private final PersistentInstanceOperations persistentOperations;
  private final InstancePreparationPipeline preparationPipeline;
  private final InstanceSoftwareProtectionInventory softwareProtection;
  private final InstanceCrashRecovery crashRecovery;
  private volatile java.util.function.Consumer<InstanceLifecycle.Transition> lifecycleObserver =
      ignored -> {};
  private volatile java.util.function.Consumer<RegisteredReady> readyObserver = ignored -> {};
  private volatile java.util.function.Consumer<InstanceFailureTransition> failureObserver =
      ignored -> {};
  private final ExecutorService finalizationExecutor;
  private final Map<String, ManagedInstance> instances = new java.util.HashMap<>();
  private final Set<String> deferredShutdownCleanup =
      java.util.concurrent.ConcurrentHashMap.newKeySet();

  private volatile boolean closed;
  private volatile BlueprintReadinessCatalog readinessCatalog;
  private volatile MaintenanceStatus maintenance = MaintenanceStatus.accepting();

  public InstanceManager(
      BlueprintRepository blueprints,
      SoftwareProfileRepository softwareProfiles,
      ResourceBudget resourceBudget,
      ManagedOutputConfig outputConfig,
      ForwardingConfig forwardingConfig,
      LoopbackPortAllocator portAllocator,
      InstanceDirectoryPreparer directoryPreparer,
      JavaJarProcessSpecFactory processSpecFactory,
      ProcessSupervisor processSupervisor,
      BackendRegistry backendRegistry,
      Logger logger) {
    this(
        blueprints,
        softwareProfiles,
        resourceBudget,
        outputConfig,
        forwardingConfig,
        portAllocator,
        directoryPreparer,
        processSpecFactory,
        processSupervisor,
        backendRegistry,
        null,
        logger);
  }

  public InstanceManager(
      BlueprintRepository blueprints,
      SoftwareProfileRepository softwareProfiles,
      ResourceBudget resourceBudget,
      ManagedOutputConfig outputConfig,
      ForwardingConfig forwardingConfig,
      LoopbackPortAllocator portAllocator,
      InstanceDirectoryPreparer directoryPreparer,
      JavaJarProcessSpecFactory processSpecFactory,
      ProcessSupervisor processSupervisor,
      BackendRegistry backendRegistry,
      SoftwareInstallationService installationService,
      Logger logger) {
    this(
        blueprints,
        softwareProfiles,
        resourceBudget,
        outputConfig,
        forwardingConfig,
        portAllocator,
        directoryPreparer,
        processSpecFactory,
        processSupervisor,
        backendRegistry,
        installationService,
        SLSDetailLog.disabled(),
        logger);
  }

  public InstanceManager(
      BlueprintRepository blueprints,
      SoftwareProfileRepository softwareProfiles,
      ResourceBudget resourceBudget,
      ManagedOutputConfig outputConfig,
      ForwardingConfig forwardingConfig,
      LoopbackPortAllocator portAllocator,
      InstanceDirectoryPreparer directoryPreparer,
      JavaJarProcessSpecFactory processSpecFactory,
      ProcessSupervisor processSupervisor,
      BackendRegistry backendRegistry,
      SoftwareInstallationService installationService,
      SLSDetailLog detailLog,
      Logger logger) {
    this(
        blueprints,
        softwareProfiles,
        resourceBudget,
        outputConfig,
        forwardingConfig,
        portAllocator,
        directoryPreparer,
        processSpecFactory,
        processSupervisor,
        backendRegistry,
        installationService,
        detailLog,
        net.slimelabs.slslite.config.DiagnosticRetentionConfig.defaults().consoleTailLines(),
        logger);
  }

  public InstanceManager(
      BlueprintRepository blueprints,
      SoftwareProfileRepository softwareProfiles,
      ResourceBudget resourceBudget,
      ManagedOutputConfig outputConfig,
      ForwardingConfig forwardingConfig,
      LoopbackPortAllocator portAllocator,
      InstanceDirectoryPreparer directoryPreparer,
      JavaJarProcessSpecFactory processSpecFactory,
      ProcessSupervisor processSupervisor,
      BackendRegistry backendRegistry,
      SoftwareInstallationService installationService,
      SLSDetailLog detailLog,
      int consoleTailLines,
      Logger logger) {
    this(
        blueprints,
        softwareProfiles,
        resourceBudget,
        outputConfig,
        forwardingConfig,
        portAllocator,
        directoryPreparer,
        processSpecFactory,
        processSupervisor,
        backendRegistry,
        installationService,
        detailLog,
        consoleTailLines,
        0,
        logger);
  }

  public InstanceManager(
      BlueprintRepository blueprints,
      SoftwareProfileRepository softwareProfiles,
      ResourceBudget resourceBudget,
      ManagedOutputConfig outputConfig,
      ForwardingConfig forwardingConfig,
      LoopbackPortAllocator portAllocator,
      InstanceDirectoryPreparer directoryPreparer,
      JavaJarProcessSpecFactory processSpecFactory,
      ProcessSupervisor processSupervisor,
      BackendRegistry backendRegistry,
      SoftwareInstallationService installationService,
      SLSDetailLog detailLog,
      int consoleTailLines,
      int proxyPlayerLimit,
      Logger logger) {
    this.blueprints = blueprints;
    this.softwareProfiles = softwareProfiles;
    this.resourceBudget = resourceBudget;
    this.outputConfig = outputConfig;
    if (consoleTailLines < 0) {
      throw new IllegalArgumentException("consoleTailLines must not be negative");
    }
    this.consoleTailLines = consoleTailLines;
    this.portAllocator = portAllocator;
    this.directoryPreparer = directoryPreparer;
    this.metadata = new InstanceMetadataService(directoryPreparer.root(), logger);
    this.softwareDirectories =
        new SoftwareBaseDirectoryResolver(processSpecFactory, installationService);
    this.launchConfigurator =
        new InstanceLaunchConfigurator(forwardingConfig, processSpecFactory, proxyPlayerLimit);
    this.processSupervisor = processSupervisor;
    this.backendRegistry = backendRegistry;
    this.diagnosticRedactor =
        new DiagnosticRedactor(
            processSpecFactory.dataDirectory(), forwardingConfig.secretFile().getParent(), true);
    this.failedStartDiagnostics =
        new FailedStartDiagnostics(
            processSpecFactory.dataDirectory(),
            processSpecFactory.dataDirectory().resolve("diagnostics").resolve("failed-starts"),
            diagnosticRedactor);
    this.logger = logger;
    this.detailLog = java.util.Objects.requireNonNull(detailLog, "detailLog");
    this.timingReporter = new InstanceTimingReporter(logger, detailLog);
    this.idGenerator = new InstanceIdGenerator();
    this.operationExecutor =
        new ThreadPoolExecutor(
            2,
            2,
            0L,
            TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(32),
            threadFactory(),
            new ThreadPoolExecutor.AbortPolicy());
    this.persistentOperations =
        new PersistentInstanceOperations(
            this, this.metadata, directoryPreparer, softwareDirectories, operationExecutor, logger);
    this.preparationPipeline =
        new InstancePreparationPipeline(
            this,
            softwareDirectories,
            directoryPreparer,
            outputConfig,
            metadata,
            launchConfigurator,
            processSupervisor,
            timingReporter,
            logger,
            detailLog);
    this.softwareProtection = new InstanceSoftwareProtectionInventory(metadata, blueprints);
    this.crashRecovery = new InstanceCrashRecovery(this::startPersistentForRecovery, logger);
    this.finalizationExecutor =
        Executors.newFixedThreadPool(2, threadFactory("sls-lite-instance-finalization-"));
  }

  @Override
  public ManagedInstance start(String blueprintId) throws InstanceOperationException {
    return create(blueprintId, InstanceLaunchOverrides.NONE);
  }

  @Override
  public ManagedInstance create(String blueprintId, InstanceLaunchOverrides overrides)
      throws InstanceOperationException {
    ResolvedDefinition definition =
        resolveDefinition(blueprintId, "Unknown blueprint: " + blueprintId);
    BlueprintReadinessCatalog readiness = readinessCatalog;
    if (readiness != null) {
      readiness.requireReady(definition.blueprint().id());
    }
    Blueprint blueprint;
    try {
      blueprint = overrides.applyTo(definition.blueprint());
    } catch (IllegalArgumentException exception) {
      throw new InstanceOperationException(exception.getMessage(), exception);
    }
    SoftwareProfile profile = definition.softwareProfile();
    InstanceDefinitionIdentity definitionIdentity =
        InstanceDefinitionIdentity.from(blueprint, profile);
    List<String> retainedPersistentIds =
        blueprint.save() ? List.copyOf(metadata.persistentInstanceIds(blueprint.id())) : List.of();

    String instanceId;
    int port;
    synchronized (this) {
      if (closed) {
        throw new InstanceOperationException("Instance manager is shutting down");
      }
      if (maintenance.enabled()) {
        throw new InstanceOperationException(maintenanceMessage(maintenance));
      }
      requireNoInactivePersistentReplacement(blueprint, retainedPersistentIds);
      enforceInstanceLimit(blueprint, null);
      instanceId = uniqueInstanceId(blueprint.id());
      if (!resourceBudget.tryReserve(instanceId, blueprint.memoryLimitMiB())) {
        throw new InstanceOperationException(
            "Insufficient managed memory for " + blueprint.memoryLimitMiB() + " MiB");
      }
      try {
        port = portAllocator.allocate();
      } catch (PortAllocationException exception) {
        resourceBudget.release(instanceId);
        throw new InstanceOperationException(exception.getMessage(), exception);
      }

      InstanceLifecycle lifecycle =
          new InstanceLifecycle(instanceId, this::observeLifecycleTransition);
      Path directory = directoryPreparer.root().resolve(instanceId);
      ManagedInstance instance =
          new ManagedInstance(
              instanceId,
              blueprint,
              definitionIdentity,
              overrides,
              port,
              directory,
              lifecycle,
              Instant.now(),
              consoleTailLines);
      instances.put(instanceId, instance);
      lifecycle.transitionTo(InstanceState.PREPARING);
      logger.info(
          "Instance start accepted [{}]: {} from {}/{} ({} {}, {} MiB, port {})",
          instance.correlationId(),
          instanceId,
          blueprint.type(),
          blueprint.id(),
          blueprint.software(),
          blueprint.version(),
          blueprint.memoryLimitMiB(),
          port);

      try {
        operationExecutor.execute(() -> preparationPipeline.run(instance, profile, false));
      } catch (RuntimeException exception) {
        instances.remove(instanceId);
        portAllocator.release(port);
        resourceBudget.release(instanceId);
        lifecycle.transitionTo(InstanceState.FAILED);
        recordFailedStart(instance, FailurePhase.PREPARATION, exception);
        throw new InstanceOperationException("Instance preparation queue is full", exception);
      }
      return instance;
    }
  }

  @Override
  public synchronized Collection<ManagedInstance> getAll() {
    return instances.values().stream().sorted(Comparator.comparing(ManagedInstance::id)).toList();
  }

  /** Installs the one process-wide lifecycle observer before managed instances are created. */
  public synchronized void installLifecycleObserver(
      java.util.function.Consumer<InstanceLifecycle.Transition> observer) {
    if (!instances.isEmpty()) {
      throw new IllegalStateException(
          "Lifecycle observer must be installed before instances exist");
    }
    lifecycleObserver = java.util.Objects.requireNonNull(observer, "observer");
  }

  /** Installs the one process-wide registered-ready observer before managed instances exist. */
  public synchronized void installReadyObserver(
      java.util.function.Consumer<RegisteredReady> observer) {
    if (!instances.isEmpty()) {
      throw new IllegalStateException("Ready observer must be installed before instances exist");
    }
    readyObserver = java.util.Objects.requireNonNull(observer, "observer");
  }

  /** Installs the one process-wide failure observer before managed instances are created. */
  public synchronized void installFailureObserver(
      java.util.function.Consumer<InstanceFailureTransition> observer) {
    if (!instances.isEmpty()) {
      throw new IllegalStateException("Failure observer must be installed before instances exist");
    }
    failureObserver = java.util.Objects.requireNonNull(observer, "observer");
  }

  /** Installs bounded preflight admission before any new instance is created. */
  public synchronized void installReadinessCatalog(BlueprintReadinessCatalog catalog) {
    if (readinessCatalog != null) {
      throw new IllegalStateException("Blueprint readiness catalog is already installed");
    }
    readinessCatalog = java.util.Objects.requireNonNull(catalog, "catalog");
  }

  @Override
  public MaintenanceStatus maintenanceStatus() {
    return maintenance;
  }

  @Override
  public synchronized MaintenanceStatus setMaintenance(boolean enabled, String reason)
      throws InstanceOperationException {
    if (closed) {
      throw new InstanceOperationException("Instance manager is shutting down");
    }
    MaintenanceStatus current = maintenance;
    String normalized = reason == null ? "" : reason.strip();
    if (current.enabled() == enabled && current.reason().equals(normalized)) {
      return current;
    }
    try {
      maintenance = new MaintenanceStatus(enabled, Instant.now(), normalized);
    } catch (IllegalArgumentException exception) {
      throw new InstanceOperationException(exception.getMessage(), exception);
    }
    logger.info(
        "Instance creation admission changed: {}{}",
        enabled ? "maintenance/draining" : "accepting",
        normalized.isBlank() ? "" : " (" + normalized + ")");
    return maintenance;
  }

  @Override
  public Collection<String> persistentInstanceIds() {
    return persistentInstanceIds(null);
  }

  @Override
  public Collection<String> persistentInstanceIds(String blueprintId) {
    return metadata.persistentInstanceIds(blueprintId);
  }

  @Override
  public Collection<InstallationKey> protectedSoftwareVersions() throws InstanceOperationException {
    List<ManagedInstance> active;
    synchronized (this) {
      active = List.copyOf(instances.values());
    }
    return softwareProtection.collect(active);
  }

  @Override
  public synchronized ManagedInstance get(String instanceId) throws InstanceOperationException {
    ManagedInstance instance = instances.get(instanceId);
    if (instance == null) {
      throw new InstanceOperationException("Unknown active instance: " + instanceId);
    }
    return instance;
  }

  @Override
  public void sendCommand(String instanceId, String command) throws InstanceOperationException {
    String normalized = normalizeConsoleCommand(command);
    ManagedInstance instance = get(instanceId);
    synchronized (instance) {
      SupervisedProcess process = instance.process();
      if (process == null) {
        throw new InstanceOperationException("Instance has no active process: " + instanceId);
      }
      try {
        process.sendCommand(normalized);
      } catch (IOException | IllegalArgumentException | IllegalStateException exception) {
        throw new InstanceOperationException(exception.getMessage(), exception);
      }
    }
  }

  @Override
  public CompletableFuture<Integer> stop(String instanceId) throws InstanceOperationException {
    ManagedInstance instance = get(instanceId);
    synchronized (instance) {
      instance.requestStop();
      crashRecovery.cancelRecovery(instanceId);
      instance.timings().begin(InstancePhaseTimings.Phase.SHUTDOWN);
      logger.info("Instance stop requested: {} (state {})", instanceId, instance.state());
      if (instance.state() == InstanceState.PREPARING) {
        instance.lifecycle().transitionTo(InstanceState.STOPPING);
        metadata.writeBestEffort(instance, InstanceState.STOPPING, null);
        instance
            .readyFuture()
            .completeExceptionally(
                new CancellationException("Instance startup was cancelled: " + instanceId));
        return instance.stoppedFuture();
      }
      if (instance.state() == InstanceState.STOPPING) {
        return instance.stoppedFuture();
      }
      SupervisedProcess process = instance.process();
      if (process == null) {
        throw new InstanceOperationException("Instance has no active process: " + instanceId);
      }
      unregister(instance);
      try {
        if (instance.state() == InstanceState.STARTING) {
          metadata.writeBestEffort(instance, InstanceState.STOPPING, process);
          instance
              .readyFuture()
              .completeExceptionally(
                  new CancellationException("Instance startup was cancelled: " + instanceId));
          process.cancelStartup();
        } else {
          metadata.writeBestEffort(instance, InstanceState.STOPPING, process);
          process.stop();
        }
        return instance.stoppedFuture();
      } catch (IllegalStateException exception) {
        throw new InstanceOperationException(exception.getMessage(), exception);
      }
    }
  }

  @Override
  public CompletableFuture<Integer> kill(String instanceId) throws InstanceOperationException {
    return kill(instanceId, false);
  }

  @Override
  public CompletableFuture<Integer> kill(String instanceId, boolean unregisterOnFailure)
      throws InstanceOperationException {
    ManagedInstance instance = get(instanceId);
    synchronized (instance) {
      instance.requestStop();
      crashRecovery.cancelRecovery(instanceId);
      instance.timings().begin(InstancePhaseTimings.Phase.SHUTDOWN);
      logger.warn(
          "Instance force termination requested: {} (state {})", instanceId, instance.state());
      if (instance.state() == InstanceState.PREPARING) {
        instance.lifecycle().transitionTo(InstanceState.STOPPING);
        metadata.writeBestEffort(instance, InstanceState.STOPPING, null);
        instance
            .readyFuture()
            .completeExceptionally(
                new CancellationException("Instance startup was force-cancelled: " + instanceId));
        return instance.stoppedFuture();
      }

      SupervisedProcess process = instance.process();
      if (process == null) {
        throw new InstanceOperationException("Instance has no active process: " + instanceId);
      }
      try {
        if (instance.state() == InstanceState.STARTING) {
          instance
              .readyFuture()
              .completeExceptionally(
                  new CancellationException("Instance startup was force-cancelled: " + instanceId));
        }
        metadata.writeBestEffort(instance, InstanceState.STOPPING, process);
        process.kill();
        unregister(instance);
        return instance.stoppedFuture();
      } catch (RuntimeException exception) {
        if (unregisterOnFailure) {
          unregister(instance);
        }
        throw new InstanceOperationException(exception.getMessage(), exception);
      }
    }
  }

  @Override
  public CompletableFuture<ManagedInstance> restart(String instanceId)
      throws InstanceOperationException {
    return persistentOperations.restart(instanceId);
  }

  @Override
  public CompletableFuture<ManagedInstance> reset(String instanceId)
      throws InstanceOperationException {
    return persistentOperations.reset(instanceId);
  }

  @Override
  public CompletableFuture<InstanceDeletionResult> delete(String instanceId)
      throws InstanceOperationException {
    return persistentOperations.delete(instanceId);
  }

  @Override
  public void shutdown(Duration timeout) {
    long deadline = System.nanoTime() + timeout.toNanos();
    List<ManagedInstance> snapshot;
    synchronized (this) {
      if (closed) {
        return;
      }
      closed = true;
      snapshot = List.copyOf(instances.values());
    }

    crashRecovery.close(remaining(deadline));

    snapshot.forEach(instance -> instance.timings().begin(InstancePhaseTimings.Phase.SHUTDOWN));
    for (ManagedInstance instance : snapshot) {
      synchronized (instance) {
        if (instance.process() == null && instance.state() == InstanceState.PREPARING) {
          instance.requestStop();
          instance.lifecycle().transitionTo(InstanceState.STOPPING);
          metadata.writeBestEffort(instance, InstanceState.STOPPING, null);
          instance
              .readyFuture()
              .completeExceptionally(
                  new CancellationException(
                      "Instance startup was cancelled during proxy shutdown: " + instance.id()));
        } else if (instance.process() != null) {
          metadata.writeBestEffort(instance, InstanceState.STOPPING, instance.process());
        }
      }
    }
    operationExecutor.shutdownNow();
    processSupervisor.shutdown(remaining(deadline));
    awaitExecutor(operationExecutor, deadline, "managed instance operation");
    awaitTerminalCleanup(snapshot, deadline);
    finalizationExecutor.shutdown();
    awaitExecutor(finalizationExecutor, deadline, "instance finalization");
    finalizationExecutor.shutdownNow();
    for (ManagedInstance instance : snapshot) {
      if (findActive(instance.id()) == instance) {
        deferShutdownCleanup(instance);
      }
    }
  }

  private void awaitTerminalCleanup(List<ManagedInstance> instances, long deadline) {
    CompletableFuture<?>[] stopped =
        instances.stream().map(ManagedInstance::stoppedFuture).toArray(CompletableFuture[]::new);
    try {
      CompletableFuture.allOf(stopped)
          .get(Math.max(0L, deadline - System.nanoTime()), TimeUnit.NANOSECONDS);
    } catch (java.util.concurrent.ExecutionException | TimeoutException ignored) {
      // A failed process still reaches terminal cleanup. A timeout is reported once per active
      // instance below, after the finalization executor has stopped accepting work.
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      logger.warn("Interrupted while waiting for managed instance cleanup to stop");
    }
  }

  private void awaitExecutor(ExecutorService executor, long deadline, String description) {
    try {
      long remainingNanos = Math.max(0L, deadline - System.nanoTime());
      if (!executor.awaitTermination(remainingNanos, TimeUnit.NANOSECONDS)) {
        logger.warn("Timed out waiting for {} tasks to stop", description);
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      logger.warn("Interrupted while waiting for managed instance operations to stop");
    }
  }

  private static Duration remaining(long deadline) {
    return Duration.ofNanos(Math.max(0L, deadline - System.nanoTime()));
  }

  void dispatchExitFinalization(
      ManagedInstance instance, InstancePhaseTimings timings, Integer exitCode, Throwable failure) {
    Runnable finalization = () -> finalizeExitedInstance(instance, timings, exitCode, failure);
    try {
      finalizationExecutor.execute(finalization);
    } catch (RejectedExecutionException rejected) {
      if (!closed) {
        finalization.run();
        return;
      }
      // Never let a late child exit start blocking storage work after the bounded plugin shutdown
      // has ended. Durable metadata and the instance directory remain for startup reconciliation.
      deferShutdownCleanup(instance);
      if (failure == null) {
        instance.stoppedFuture().complete(exitCode);
      } else {
        instance.stoppedFuture().completeExceptionally(failure);
      }
    }
  }

  private void deferShutdownCleanup(ManagedInstance instance) {
    if (deferredShutdownCleanup.add(instance.id())) {
      logger.warn(
          "Shutdown deadline expired before cleanup completed for {}; preserving its metadata "
              + "and storage for startup reconciliation",
          instance.id());
    }
  }

  private void finalizeExitedInstance(
      ManagedInstance instance, InstancePhaseTimings timings, Integer exitCode, Throwable failure) {
    boolean unexpected = !closed && !instance.stopRequested();
    boolean failed = instance.state() == InstanceState.FAILED;
    boolean reachedReadiness = instance.registered();
    if (failure == null) {
      logger.info(
          "Instance process exited: {} with code {} (state {})",
          instance.id(),
          exitCode,
          instance.state());
    } else {
      logger.warn(
          "Instance process failed: {} (state {}): {}",
          instance.id(),
          instance.state(),
          safeRootMessage(failure));
    }
    if (failed) {
      Throwable diagnosticFailure =
          failure == null
              ? new ProcessStartException(
                  "Managed process exited with code " + exitCode + " before readiness")
              : failure;
      recordFailedStart(
          instance,
          reachedReadiness ? FailurePhase.RUNTIME : FailurePhase.STARTUP,
          diagnosticFailure);
    }
    timings.finish(InstancePhaseTimings.Phase.SHUTDOWN);
    InstancePreparationException cleanupFailure = cleanup(instance);
    if (failure != null && cleanupFailure != null) {
      failure.addSuppressed(cleanupFailure);
    }
    crashRecovery.exited(instance, unexpected && cleanupFailure == null);
    if (failure == null && cleanupFailure == null) {
      instance.stoppedFuture().complete(exitCode);
    } else {
      instance.stoppedFuture().completeExceptionally(failure == null ? cleanupFailure : failure);
    }
  }

  void registerReady(ManagedInstance instance) {
    InstancePhaseTimings timings = instance.timings();
    timings.begin(InstancePhaseTimings.Phase.REGISTRATION);
    synchronized (instance) {
      if (instance.state() != InstanceState.READY || !isActive(instance)) {
        timings.finish(InstancePhaseTimings.Phase.REGISTRATION);
        timingReporter.logProvisioning(
            instance.correlationId(), instance.id(), instance.timings(), "registration-cancelled");
        instance
            .readyFuture()
            .completeExceptionally(
                new IllegalStateException("Instance exited before Velocity registration"));
        return;
      }
      try {
        metadata.write(instance, InstanceState.READY, instance.process());
        backendRegistry.register(
            instance.id(),
            new InetSocketAddress(InetAddress.getLoopbackAddress(), instance.port()),
            instance.blueprint().version());
        instance.registered(true);
        timings.finish(InstancePhaseTimings.Phase.REGISTRATION);
        timings.provisioned();
        timingReporter.logProvisioning(
            instance.correlationId(), instance.id(), instance.timings(), "ready");
        publishRegisteredReady(instance);
        instance.readyFuture().complete(instance);
        crashRecovery.ready(instance);
        logger.info(
            "Instance ready [{}]: {} on loopback port {}",
            instance.correlationId(),
            instance.id(),
            instance.port());
      } catch (InstancePreparationException | RuntimeException exception) {
        timings.finish(InstancePhaseTimings.Phase.REGISTRATION);
        timingReporter.logProvisioning(
            instance.correlationId(), instance.id(), instance.timings(), "registration-failed");
        instance.readyFuture().completeExceptionally(exception);
        recordFailedStart(instance, FailurePhase.REGISTRATION, exception);
        logger.warn(
            "Instance registration failed: {}: {}. Last output: {}",
            instance.id(),
            safeRootMessage(exception),
            lastOutput(instance));
        SupervisedProcess process = instance.process();
        if (process != null) {
          timings.begin(InstancePhaseTimings.Phase.SHUTDOWN);
          process.forceStop();
        }
      }
    }
  }

  void failPreparation(ManagedInstance instance, FailurePhase phase, Exception exception) {
    SupervisedProcess process;
    synchronized (instance) {
      if (instance.state() == InstanceState.STOPPING && instance.stopRequested()) {
        finishCancelledPreparation(instance);
        return;
      }
      if (instance.state() == InstanceState.PREPARING) {
        instance.lifecycle().transitionTo(InstanceState.FAILED);
      }
      instance.readyFuture().completeExceptionally(exception);
      process = instance.process();
    }
    recordFailedStart(instance, phase, exception);
    timingReporter.logProvisioning(
        instance.correlationId(), instance.id(), instance.timings(), "preparation-failed");
    logger.error(
        "Unable to start managed instance {} during {}: {}",
        instance.id(),
        phase.id(),
        safeRootMessage(exception));
    detailLog.normal(
        instance.correlationId(),
        "failure",
        "phase={} instance={} failure={}",
        phase.id(),
        instance.id(),
        safeRootMessage(exception));
    if (process != null) {
      instance.timings().begin(InstancePhaseTimings.Phase.SHUTDOWN);
      process.forceStop();
      return;
    }
    InstancePreparationException cleanupFailure = cleanup(instance);
    if (cleanupFailure != null) {
      exception.addSuppressed(cleanupFailure);
    }
    crashRecovery.exited(instance, true);
  }

  void finishCancelledPreparation(ManagedInstance instance) {
    if (instance.state() == InstanceState.STOPPING) {
      instance.lifecycle().transitionTo(InstanceState.STOPPED);
    }
    instance.timings().finish(InstancePhaseTimings.Phase.SHUTDOWN);
    timingReporter.logProvisioning(
        instance.correlationId(), instance.id(), instance.timings(), "cancelled");
    InstancePreparationException cleanupFailure = cleanup(instance);
    if (cleanupFailure == null) {
      instance.stoppedFuture().complete(0);
    } else {
      instance.stoppedFuture().completeExceptionally(cleanupFailure);
    }
    logger.info("Cancelled instance startup for {}", instance.id());
  }

  private InstancePreparationException cleanup(ManagedInstance instance) {
    synchronized (instance) {
      synchronized (this) {
        if (instances.get(instance.id()) != instance) {
          return null;
        }
      }
      InstancePreparationException persistentFileFailure = null;
      InstancePhaseTimings timings = instance.timings();
      timings.begin(InstancePhaseTimings.Phase.CLEANUP);
      unregister(instance);
      instance.closeOutput();
      instance
          .takeOutputFailure()
          .ifPresent(
              failure ->
                  logger.warn(
                      "Unable to close temporary console log for {}: {}",
                      instance.id(),
                      diagnosticRedactor.redact(failure.getMessage())));
      portAllocator.release(instance.port());
      resourceBudget.release(instance.id());
      try {
        directoryPreparer.publishPersistentFiles(
            instance.id(), !instance.blueprint().persistentFiles().isEmpty());
      } catch (InstancePreparationException exception) {
        persistentFileFailure = exception;
        logger.error(
            "Unable to publish persistent files for {} during cleanup: {}",
            instance.id(),
            safeRootMessage(exception));
        detailLog.normal(
            instance.correlationId(),
            "failure",
            "phase={} instance={} operation=persistent-file-publication failure={}",
            FailurePhase.CLEANUP.id(),
            instance.id(),
            safeRootMessage(exception));
      }
      try {
        directoryPreparer.suspend(instance.id());
      } catch (InstancePreparationException exception) {
        logger.error(
            "Unable to suspend storage for {} during cleanup: {}",
            instance.id(),
            safeRootMessage(exception));
        detailLog.normal(
            instance.correlationId(),
            "failure",
            "phase={} instance={} operation=suspend failure={}",
            FailurePhase.CLEANUP.id(),
            instance.id(),
            safeRootMessage(exception));
      }
      if (!instance.blueprint().save() && persistentFileFailure == null) {
        try {
          directoryPreparer.delete(instance.id());
        } catch (InstancePreparationException exception) {
          logger.error(
              "Unable to delete ephemeral instance {} during cleanup: {}",
              instance.id(),
              safeRootMessage(exception));
          detailLog.normal(
              instance.correlationId(),
              "failure",
              "phase={} instance={} operation=delete failure={}",
              FailurePhase.CLEANUP.id(),
              instance.id(),
              safeRootMessage(exception));
        }
      } else {
        metadata.writeBestEffort(instance, instance.state(), null);
        if (!instance.blueprint().save() && persistentFileFailure != null) {
          logger.warn(
              "Preserving ephemeral instance {} because persistent file publication failed",
              instance.id());
        }
      }
      if (!instance.readyFuture().isDone()) {
        instance
            .readyFuture()
            .completeExceptionally(
                new IllegalStateException("Instance stopped before becoming ready"));
      }
      synchronized (this) {
        instances.remove(instance.id(), instance);
      }
      timings.finish(InstancePhaseTimings.Phase.CLEANUP);
      timingReporter.logProvisioning(
          instance.correlationId(), instance.id(), instance.timings(), instance.state().name());
      timingReporter.logTermination(
          instance.correlationId(), instance.id(), instance.timings(), instance.state().name());
      return persistentFileFailure;
    }
  }

  private void unregister(ManagedInstance instance) {
    if (instance.registered()) {
      backendRegistry.unregister(instance.id());
      instance.registered(false);
    }
  }

  private synchronized boolean isActive(ManagedInstance instance) {
    return instances.get(instance.id()) == instance && !closed;
  }

  static String lastOutput(ManagedInstance instance) {
    List<String> lines = instance.logs(1, 3).lines();
    return lines.isEmpty() ? "no managed process output" : String.join(" | ", lines);
  }

  void recordFailedStart(ManagedInstance instance, FailurePhase phase, Throwable failure) {
    instance.recordFailurePhase(phase);
    if (!instance.markFailureDiagnosticsRecorded()) {
      return;
    }
    observeFailure(instance, phase, failure);
    try {
      Path report = failedStartDiagnostics.record(instance, phase, failure);
      logger.warn(
          "Retained failed-start diagnostics for {} as {}", instance.id(), report.getFileName());
    } catch (IOException exception) {
      logger.warn(
          "Unable to retain failed-start diagnostics for {}: {}",
          instance.id(),
          diagnosticRedactor.redact(exception.getMessage()));
    }
  }

  private void observeFailure(ManagedInstance instance, FailurePhase phase, Throwable failure) {
    try {
      failureObserver.accept(
          new InstanceFailureTransition(
              instance.id(),
              instance.blueprint().id(),
              instance.blueprint().type(),
              instance.correlationId(),
              phase,
              failureCategory(phase, failure),
              Instant.now()));
    } catch (RuntimeException ignored) {
      // Observability must never interfere with failure handling or retained diagnostics.
    }
  }

  private static FailureCategory failureCategory(FailurePhase phase, Throwable failure) {
    Throwable cause = rootCause(failure);
    if (cause instanceof CancellationException || cause instanceof InterruptedException) {
      return FailureCategory.CANCELLED;
    }
    if (cause instanceof TimeoutException) {
      return FailureCategory.TIMEOUT;
    }
    if (cause instanceof SoftwareInstallationException || phase == FailurePhase.INSTALLATION) {
      return FailureCategory.SOFTWARE;
    }
    if (cause instanceof ProcessStartException
        || phase == FailurePhase.STARTUP
        || phase == FailurePhase.RUNTIME) {
      return FailureCategory.PROCESS;
    }
    return switch (phase) {
      case CONFIGURATION -> FailureCategory.CONFIGURATION;
      case PREPARATION, CLEANUP -> FailureCategory.STORAGE;
      case READINESS -> FailureCategory.READINESS;
      case REGISTRATION, CONNECTION -> FailureCategory.REGISTRATION;
      case SHUTDOWN -> FailureCategory.SHUTDOWN;
      case INSTALLATION -> FailureCategory.SOFTWARE;
      case STARTUP, RUNTIME -> FailureCategory.PROCESS;
    };
  }

  public enum FailureCategory {
    CANCELLED,
    TIMEOUT,
    SOFTWARE,
    CONFIGURATION,
    STORAGE,
    READINESS,
    REGISTRATION,
    PROCESS,
    SHUTDOWN
  }

  public record InstanceFailureTransition(
      String instanceId,
      String blueprintId,
      String blueprintType,
      String correlationId,
      FailurePhase phase,
      FailureCategory category,
      Instant occurredAt) {

    public InstanceFailureTransition {
      java.util.Objects.requireNonNull(instanceId, "instanceId");
      java.util.Objects.requireNonNull(blueprintId, "blueprintId");
      java.util.Objects.requireNonNull(blueprintType, "blueprintType");
      java.util.Objects.requireNonNull(correlationId, "correlationId");
      java.util.Objects.requireNonNull(phase, "phase");
      java.util.Objects.requireNonNull(category, "category");
      java.util.Objects.requireNonNull(occurredAt, "occurredAt");
    }
  }

  static Throwable rootCause(Throwable throwable) {
    Throwable current = throwable;
    while (current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }

  static String rootMessage(Throwable throwable) {
    Throwable cause = rootCause(throwable);
    return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
  }

  private String safeRootMessage(Throwable throwable) {
    return diagnosticRedactor.redact(rootMessage(throwable));
  }

  ManagedInstance startPersistent(String instanceId) throws InstanceOperationException {
    return startPersistent(instanceId, true);
  }

  private ManagedInstance startPersistentForRecovery(String instanceId)
      throws InstanceOperationException {
    return startPersistent(instanceId, false);
  }

  private ManagedInstance startPersistent(String instanceId, boolean allowPendingOperation)
      throws InstanceOperationException {
    InstanceMetadata metadata = this.metadata.readPersistent(instanceId);
    ResolvedDefinition definition =
        resolveDefinition(
            metadata.blueprintId(),
            "Persistent instance "
                + instanceId
                + " references missing blueprint "
                + metadata.blueprintId());
    Blueprint blueprint = metadata.launchOverrides().applyTo(definition.blueprint());
    SoftwareProfile profile = definition.softwareProfile();
    this.metadata.requireRestartable(metadata, false);
    metadata = requireCompatibleDefinition(metadata, definition);
    InstanceDefinitionIdentity definitionIdentity =
        InstanceDefinitionIdentity.from(blueprint, profile);

    int port;
    ManagedInstance instance;
    synchronized (this) {
      if (closed) {
        throw new InstanceOperationException("Instance manager is shutting down");
      }
      if (instances.containsKey(instanceId)) {
        throw new InstanceOperationException("Instance is already active: " + instanceId);
      }
      if (!allowPendingOperation && persistentOperations.hasPendingOperation(instanceId)) {
        throw new InstanceOperationException(
            "Instance has an administrative lifecycle operation in progress: " + instanceId);
      }
      enforceInstanceLimit(blueprint, instanceId);
      if (!resourceBudget.tryReserve(instanceId, blueprint.memoryLimitMiB())) {
        throw new InstanceOperationException(
            "Insufficient managed memory for " + blueprint.memoryLimitMiB() + " MiB");
      }
      try {
        port = portAllocator.allocate();
      } catch (PortAllocationException exception) {
        resourceBudget.release(instanceId);
        throw new InstanceOperationException(exception.getMessage(), exception);
      }

      InstanceLifecycle lifecycle =
          new InstanceLifecycle(instanceId, this::observeLifecycleTransition);
      instance =
          new ManagedInstance(
              instanceId,
              blueprint,
              definitionIdentity,
              metadata.launchOverrides(),
              port,
              directoryPreparer.root().resolve(instanceId),
              lifecycle,
              metadata.createdAt(),
              consoleTailLines);
      instances.put(instanceId, instance);
      lifecycle.transitionTo(InstanceState.PREPARING);
      try {
        operationExecutor.execute(() -> preparationPipeline.run(instance, profile, true));
      } catch (RuntimeException exception) {
        instances.remove(instanceId);
        portAllocator.release(port);
        resourceBudget.release(instanceId);
        lifecycle.transitionTo(InstanceState.FAILED);
        recordFailedStart(instance, FailurePhase.PREPARATION, exception);
        throw new InstanceOperationException("Instance preparation queue is full", exception);
      }
    }
    return instance;
  }

  InstanceMetadata requireCompatibleDefinition(InstanceMetadata metadata)
      throws InstanceOperationException {
    ResolvedDefinition definition =
        resolveDefinition(
            metadata.blueprintId(),
            "Persistent instance "
                + metadata.instanceId()
                + " references missing blueprint "
                + metadata.blueprintId());
    return requireCompatibleDefinition(metadata, definition);
  }

  private InstanceMetadata requireCompatibleDefinition(
      InstanceMetadata metadata, ResolvedDefinition definition) throws InstanceOperationException {
    Blueprint effective = metadata.launchOverrides().applyTo(definition.blueprint());
    InstanceDefinitionIdentity current =
        InstanceDefinitionIdentity.from(effective, definition.softwareProfile());
    return this.metadata.requireCompatibleDefinition(metadata, current, effective.save());
  }

  private String uniqueInstanceId(String blueprintId) throws InstanceOperationException {
    for (int attempt = 0; attempt < ID_ATTEMPTS; attempt++) {
      String candidate = idGenerator.generate(blueprintId);
      if (!instances.containsKey(candidate)
          && !persistentOperations.hasPendingDelete(candidate)
          && !Files.exists(directoryPreparer.root().resolve(candidate))) {
        return candidate;
      }
    }
    throw new InstanceOperationException("Unable to generate a unique instance ID");
  }

  ResolvedDefinition resolveDefinition(String blueprintId, String missingBlueprintMessage)
      throws InstanceOperationException {
    DefinitionCatalog.Snapshot definitions = blueprints.catalog().snapshot();
    Blueprint blueprint =
        java.util.Optional.ofNullable(definitions.blueprints().get(blueprintId))
            .orElseThrow(() -> new InstanceOperationException(missingBlueprintMessage));
    SoftwareProfile profile =
        blueprints.catalog() == softwareProfiles.catalog()
            ? definitions.softwareProfiles().get(blueprint.software())
            : softwareProfiles.get(blueprint.software()).orElse(null);
    if (profile == null) {
      throw new InstanceOperationException("Missing software profile: " + blueprint.software());
    }
    return new ResolvedDefinition(blueprint, profile);
  }

  private void enforceInstanceLimit(Blueprint blueprint, String restartingId)
      throws InstanceOperationException {
    long active =
        instances.values().stream()
            .filter(instance -> instance.blueprint().id().equals(blueprint.id()))
            .filter(instance -> restartingId == null || !instance.id().equals(restartingId))
            .filter(
                instance ->
                    instance.state() != InstanceState.STOPPING
                        && instance.state() != InstanceState.STOPPED
                        && instance.state() != InstanceState.FAILED)
            .count();
    if (active >= blueprint.maxInstances()) {
      throw new InstanceOperationException(
          "Blueprint "
              + blueprint.type()
              + "/"
              + blueprint.id()
              + " has reached its limit of "
              + blueprint.maxInstances()
              + " active instance(s)");
    }
  }

  private void requireNoInactivePersistentReplacement(
      Blueprint blueprint, List<String> retainedPersistentIds) throws InstanceOperationException {
    String retainedId =
        retainedPersistentIds.stream()
            .filter(
                id -> {
                  ManagedInstance instance = instances.get(id);
                  return instance == null
                      || instance.state() == InstanceState.STOPPING
                      || instance.state() == InstanceState.STOPPED
                      || instance.state() == InstanceState.FAILED;
                })
            .filter(id -> !persistentOperations.hasPendingDelete(id))
            .findFirst()
            .orElse(null);
    if (retainedId == null) {
      return;
    }
    throw new InstanceOperationException(
        "Persistent instance "
            + retainedId
            + " already exists for blueprint "
            + blueprint.type()
            + "/"
            + blueprint.id()
            + "; restart or reset that instance, or delete it before creating a replacement");
  }

  synchronized ManagedInstance findActive(String instanceId) {
    return instances.get(instanceId);
  }

  synchronized boolean isClosed() {
    return closed;
  }

  record ResolvedDefinition(Blueprint blueprint, SoftwareProfile softwareProfile) {}

  private static ThreadFactory threadFactory() {
    return threadFactory("sls-lite-operation-");
  }

  private static ThreadFactory threadFactory(String prefix) {
    AtomicInteger sequence = new AtomicInteger();
    return runnable -> {
      Thread thread = new Thread(runnable, prefix + sequence.incrementAndGet());
      thread.setDaemon(true);
      return thread;
    };
  }

  private static String normalizeConsoleCommand(String command) throws InstanceOperationException {
    if (command == null || command.contains("\n") || command.contains("\r")) {
      throw new InstanceOperationException("Console command must be one line");
    }
    String normalized = command.strip();
    if (normalized.startsWith("/")) {
      normalized = normalized.substring(1).strip();
    }
    if (normalized.isBlank()) {
      throw new InstanceOperationException("Console command must not be blank");
    }
    if (normalized.length() > 4096) {
      throw new InstanceOperationException("Console command exceeds the 4096 character limit");
    }
    return normalized;
  }

  private static String maintenanceMessage(MaintenanceStatus status) {
    return status.reason().isBlank()
        ? "New instance creation is disabled while SLS-LITE is in maintenance mode"
        : "New instance creation is disabled while SLS-LITE is in maintenance mode: "
            + status.reason();
  }

  private void observeLifecycleTransition(InstanceLifecycle.Transition transition) {
    // Process readiness precedes Velocity registration. Publish public READY only after
    // registerReady has made the backend externally usable.
    if (transition.current() == InstanceState.READY) {
      return;
    }
    lifecycleObserver.accept(transition);
  }

  private void publishRegisteredReady(ManagedInstance instance) {
    Instant occurredAt = Instant.now();
    try {
      lifecycleObserver.accept(
          new InstanceLifecycle.Transition(
              instance.id(), InstanceState.STARTING, InstanceState.READY, occurredAt));
    } catch (RuntimeException ignored) {
      // Extension observability cannot invalidate successful Velocity registration.
    }
    try {
      readyObserver.accept(new RegisteredReady(instance, occurredAt));
    } catch (RuntimeException ignored) {
      // Extension actions cannot invalidate successful Velocity registration.
    }
  }

  public record RegisteredReady(ManagedInstance instance, Instant occurredAt) {

    public RegisteredReady {
      instance = java.util.Objects.requireNonNull(instance, "instance");
      occurredAt = java.util.Objects.requireNonNull(occurredAt, "occurredAt");
    }
  }
}
