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
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.slimelabs.slslite.blueprint.Blueprint;
import net.slimelabs.slslite.blueprint.BlueprintRepository;
import net.slimelabs.slslite.config.DefinitionCatalog;
import net.slimelabs.slslite.config.ForwardingConfig;
import net.slimelabs.slslite.config.ManagedOutputConfig;
import net.slimelabs.slslite.install.SoftwareInstallationService;
import net.slimelabs.slslite.instance.configuration.InstanceLaunchConfigurator;
import net.slimelabs.slslite.instance.diagnostics.FailedStartDiagnostics;
import net.slimelabs.slslite.instance.diagnostics.InstanceTimingReporter;
import net.slimelabs.slslite.instance.lifecycle.InstanceLifecycle;
import net.slimelabs.slslite.instance.lifecycle.InstancePhaseTimings;
import net.slimelabs.slslite.instance.metadata.InstanceMetadataService;
import net.slimelabs.slslite.instance.model.InstanceDefinitionIdentity;
import net.slimelabs.slslite.instance.model.InstanceIdGenerator;
import net.slimelabs.slslite.instance.model.InstanceMetadata;
import net.slimelabs.slslite.instance.model.InstanceState;
import net.slimelabs.slslite.instance.storage.InstanceDirectoryPreparer;
import net.slimelabs.slslite.network.LoopbackPortAllocator;
import net.slimelabs.slslite.network.PortAllocationException;
import net.slimelabs.slslite.process.JavaJarProcessSpecFactory;
import net.slimelabs.slslite.process.ProcessSpec;
import net.slimelabs.slslite.process.ProcessSpecificationException;
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
  private final LoopbackPortAllocator portAllocator;
  private final InstanceDirectoryPreparer directoryPreparer;
  private final InstanceMetadataService metadata;
  private final SoftwareBaseDirectoryResolver softwareDirectories;
  private final InstanceLaunchConfigurator launchConfigurator;
  private final ProcessSupervisor processSupervisor;
  private final BackendRegistry backendRegistry;
  private final FailedStartDiagnostics failedStartDiagnostics;
  private final InstanceTimingReporter timingReporter;
  private final Logger logger;
  private final InstanceIdGenerator idGenerator;
  private final ThreadPoolExecutor operationExecutor;
  private final Map<String, ManagedInstance> instances = new java.util.HashMap<>();
  private final Set<String> pendingRestarts = new HashSet<>();

  private boolean closed;

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
    this.blueprints = blueprints;
    this.softwareProfiles = softwareProfiles;
    this.resourceBudget = resourceBudget;
    this.outputConfig = outputConfig;
    this.portAllocator = portAllocator;
    this.directoryPreparer = directoryPreparer;
    this.metadata = new InstanceMetadataService(directoryPreparer.root(), logger);
    this.softwareDirectories =
        new SoftwareBaseDirectoryResolver(processSpecFactory, installationService);
    this.launchConfigurator = new InstanceLaunchConfigurator(forwardingConfig, processSpecFactory);
    this.processSupervisor = processSupervisor;
    this.backendRegistry = backendRegistry;
    this.failedStartDiagnostics =
        new FailedStartDiagnostics(
            directoryPreparer.root().resolveSibling("diagnostics").resolve("failed-starts"));
    this.logger = logger;
    this.timingReporter = new InstanceTimingReporter(logger);
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
  }

  @Override
  public ManagedInstance start(String blueprintId) throws InstanceOperationException {
    ResolvedDefinition definition =
        resolveDefinition(blueprintId, "Unknown blueprint: " + blueprintId);
    Blueprint blueprint = definition.blueprint();
    SoftwareProfile profile = definition.softwareProfile();
    InstanceDefinitionIdentity definitionIdentity =
        InstanceDefinitionIdentity.from(blueprint, profile);

    String instanceId;
    int port;
    synchronized (this) {
      if (closed) {
        throw new InstanceOperationException("Instance manager is shutting down");
      }
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

      InstanceLifecycle lifecycle = new InstanceLifecycle(instanceId);
      lifecycle.transitionTo(InstanceState.PREPARING);
      Path directory = directoryPreparer.root().resolve(instanceId);
      ManagedInstance instance =
          new ManagedInstance(
              instanceId, blueprint, definitionIdentity, port, directory, lifecycle, Instant.now());
      instances.put(instanceId, instance);
      logger.info(
          "Instance start accepted: {} from {}/{} ({} {}, {} MiB, port {})",
          instanceId,
          blueprint.type(),
          blueprint.id(),
          blueprint.software(),
          blueprint.version(),
          blueprint.memoryLimitMiB(),
          port);

      try {
        operationExecutor.execute(() -> prepareAndStart(instance, profile, false));
      } catch (RuntimeException exception) {
        instances.remove(instanceId);
        portAllocator.release(port);
        resourceBudget.release(instanceId);
        lifecycle.transitionTo(InstanceState.FAILED);
        throw new InstanceOperationException("Instance preparation queue is full", exception);
      }
      return instance;
    }
  }

  @Override
  public synchronized Collection<ManagedInstance> getAll() {
    return instances.values().stream().sorted(Comparator.comparing(ManagedInstance::id)).toList();
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
      instance.timings().begin(InstancePhaseTimings.Phase.SHUTDOWN);
      logger.info("Instance stop requested: {} (state {})", instanceId, instance.state());
      if (instance.state() == InstanceState.PREPARING) {
        instance.requestStop();
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
          instance.requestStop();
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
  public CompletableFuture<ManagedInstance> restart(String instanceId)
      throws InstanceOperationException {
    return cyclePersistent(instanceId, false);
  }

  @Override
  public CompletableFuture<ManagedInstance> reset(String instanceId)
      throws InstanceOperationException {
    return cyclePersistent(instanceId, true);
  }

  private CompletableFuture<ManagedInstance> cyclePersistent(String instanceId, boolean reset)
      throws InstanceOperationException {
    ManagedInstance active;
    InstanceMetadata metadata;
    synchronized (this) {
      if (closed) {
        throw new InstanceOperationException("Instance manager is shutting down");
      }
      if (!InstanceIdGenerator.isValid(instanceId)) {
        throw new InstanceOperationException("Invalid instance ID: " + instanceId);
      }
      if (!pendingRestarts.add(instanceId)) {
        throw new InstanceOperationException(
            "Instance restart or reset is already in progress: " + instanceId);
      }
      active = instances.get(instanceId);
      try {
        metadata =
            active == null
                ? this.metadata.readPersistent(instanceId)
                : this.metadata.snapshot(active);
        this.metadata.requireRestartable(metadata, active != null);
        if (!reset) {
          requireCompatibleDefinition(metadata);
        }
      } catch (InstanceOperationException exception) {
        pendingRestarts.remove(instanceId);
        throw exception;
      }
    }

    CompletableFuture<Integer> stopped;
    try {
      stopped = active == null ? CompletableFuture.completedFuture(0) : stop(instanceId);
    } catch (InstanceOperationException exception) {
      synchronized (this) {
        pendingRestarts.remove(instanceId);
      }
      throw exception;
    }

    return stopped
        .thenApply(
            ignored -> {
              try {
                if (reset) {
                  resetPersistent(instanceId);
                }
                return startPersistent(instanceId);
              } catch (InstanceOperationException exception) {
                throw new java.util.concurrent.CompletionException(exception);
              }
            })
        .whenComplete(
            (ignored, failure) -> {
              synchronized (this) {
                pendingRestarts.remove(instanceId);
              }
            });
  }

  private void resetPersistent(String instanceId) throws InstanceOperationException {
    InstanceMetadata metadata = this.metadata.readPersistent(instanceId);
    this.metadata.requireRestartable(metadata, false);
    ResolvedDefinition definition =
        resolveDefinition(
            metadata.blueprintId(),
            "Persistent instance "
                + instanceId
                + " references missing blueprint "
                + metadata.blueprintId());
    Blueprint blueprint = definition.blueprint();
    SoftwareProfile profile = definition.softwareProfile();
    try {
      Path baseDirectory =
          softwareDirectories.resolve(
              profile, blueprint.version(), blueprint.softwarePath(), () -> false);
      InstanceMetadata stopped =
          metadata
              .withDefinitionIdentity(InstanceDefinitionIdentity.from(blueprint, profile))
              .withoutProcess(InstanceState.STOPPED);
      directoryPreparer.replace(
          instanceId,
          baseDirectory,
          blueprint.volumes(),
          blueprint.copies(),
          directory -> this.metadata.write(directory, stopped));
    } catch (ProcessSpecificationException | InstancePreparationException exception) {
      throw new InstanceOperationException(exception.getMessage(), exception);
    }
  }

  @Override
  public void shutdown(Duration timeout) {
    List<ManagedInstance> snapshot;
    synchronized (this) {
      if (closed) {
        return;
      }
      closed = true;
      snapshot = List.copyOf(instances.values());
    }

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
        }
      }
    }
    operationExecutor.shutdownNow();
    processSupervisor.shutdown(timeout);
    for (ManagedInstance instance : snapshot) {
      if (instance.preparationRunning()) {
        logger.info("Waiting for cancelled preparation to release {}", instance.id());
      } else {
        cleanup(instance);
      }
    }
  }

  private void prepareAndStart(
      ManagedInstance instance, SoftwareProfile profile, boolean reuseDirectory) {
    InstancePhaseTimings timings = instance.timings();
    timings.finish(InstancePhaseTimings.Phase.DISPATCH_QUEUE);
    instance.preparationStarted();
    try {
      logger.info(
          "Preparing instance {} with {} volume(s) and {} copy entry(s)",
          instance.id(),
          instance.blueprint().volumes().size(),
          instance.blueprint().copies().size());
      timings.begin(InstancePhaseTimings.Phase.SOFTWARE_RESOLUTION);
      Path baseDirectory =
          softwareDirectories.resolve(
              profile,
              instance.blueprint().version(),
              instance.blueprint().softwarePath(),
              instance::stopRequested);
      timings.finish(InstancePhaseTimings.Phase.SOFTWARE_RESOLUTION);
      logger.info(
          "Instance software ready: {} ({} {})",
          instance.id(),
          profile.id(),
          instance.blueprint().version());
      timings.begin(InstancePhaseTimings.Phase.FILE_PREPARATION);
      Path prepared;
      if (reuseDirectory) {
        prepared = instance.directory();
        if (!Files.isDirectory(prepared)) {
          throw new InstancePreparationException(
              "Persistent instance directory does not exist: " + prepared);
        }
        directoryPreparer.resume(instance.id());
      } else {
        prepared =
            directoryPreparer.prepare(
                instance.id(),
                baseDirectory,
                instance.blueprint().volumes(),
                instance.blueprint().copies(),
                instance::stopRequested);
      }
      timings.finish(InstancePhaseTimings.Phase.FILE_PREPARATION);
      logger.info(
          "Instance files ready: {} ({} volume(s), {} copy entry(s))",
          instance.id(),
          instance.blueprint().volumes().size(),
          instance.blueprint().copies().size());
      if (!prepared.equals(instance.directory())) {
        throw new InstanceOperationException("Prepared instance path changed unexpectedly");
      }
      timings.begin(InstancePhaseTimings.Phase.CONFIGURATION);
      instance.configureOutput(outputConfig);
      metadata.write(instance, InstanceState.PREPARING, null);
      ProcessSpec spec =
          launchConfigurator.configure(
              profile, instance.blueprint(), instance.id(), prepared, instance.port());
      timings.finish(InstancePhaseTimings.Phase.CONFIGURATION);
      SupervisedProcess process;
      synchronized (instance) {
        if (instance.stopRequested()) {
          finishCancelledPreparation(instance);
          return;
        }
        metadata.write(instance, InstanceState.STARTING, null);
        timings.begin(InstancePhaseTimings.Phase.PROCESS_LAUNCH);
        process =
            processSupervisor.start(
                instance.id(),
                spec,
                instance.lifecycle(),
                line -> {
                  instance.appendLog(line);
                  if (instance.mirrorsOutputToProxyConsole()) {
                    logger.info("[{}] {}", instance.id(), line);
                  }
                  instance
                      .takeOutputFailure()
                      .ifPresent(
                          failure ->
                              logger.warn(
                                  "Temporary console log disabled for {}: {}",
                                  instance.id(),
                                  failure.getMessage()));
                });
        instance.attachProcess(process);
        timings.finish(InstancePhaseTimings.Phase.PROCESS_LAUNCH);
        timings.begin(InstancePhaseTimings.Phase.READINESS);
        logger.info(
            "Instance process started: {} (PID {}, readiness timeout " + "{} seconds)",
            instance.id(),
            process.processId(),
            profile.startupTimeoutSeconds());
      }
      process
          .exitFuture()
          .whenComplete(
              (exitCode, failure) -> {
                boolean failedBeforeReadiness = instance.state() == InstanceState.FAILED;
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
                      rootMessage(failure));
                }
                if (failedBeforeReadiness) {
                  Throwable diagnosticFailure =
                      failure == null
                          ? new ProcessStartException(
                              "Managed process exited with code " + exitCode + " before readiness")
                          : failure;
                  recordFailedStart(instance, "process-exit", diagnosticFailure);
                }
                timings.finish(InstancePhaseTimings.Phase.SHUTDOWN);
                cleanup(instance);
                if (failure == null) {
                  instance.stoppedFuture().complete(exitCode);
                } else {
                  instance.stoppedFuture().completeExceptionally(failure);
                }
              });
      metadata.write(instance, InstanceState.STARTING, process);
      process
          .readyFuture()
          .whenComplete(
              (ignored, failure) -> {
                timings.finish(InstancePhaseTimings.Phase.READINESS);
                if (failure == null) {
                  registerReady(instance);
                } else {
                  Throwable cause = rootCause(failure);
                  if (cause instanceof CancellationException) {
                    logger.info("Instance readiness cancelled: {}", instance.id());
                  } else {
                    logger.warn(
                        "Instance readiness failed: {}: {}. Last output: {}",
                        instance.id(),
                        rootMessage(cause),
                        lastOutput(instance));
                    recordFailedStart(instance, "readiness", cause);
                  }
                  timingReporter.logProvisioning(
                      instance.id(), instance.timings(), "readiness-failed");
                  instance.readyFuture().completeExceptionally(failure);
                }
              });
    } catch (Exception exception) {
      failPreparation(instance, exception);
    } finally {
      instance.preparationFinished();
    }
  }

  private void registerReady(ManagedInstance instance) {
    InstancePhaseTimings timings = instance.timings();
    timings.begin(InstancePhaseTimings.Phase.REGISTRATION);
    synchronized (instance) {
      if (instance.state() != InstanceState.READY || !isActive(instance)) {
        timings.finish(InstancePhaseTimings.Phase.REGISTRATION);
        timingReporter.logProvisioning(instance.id(), instance.timings(), "registration-cancelled");
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
        timingReporter.logProvisioning(instance.id(), instance.timings(), "ready");
        instance.readyFuture().complete(instance);
        logger.info("Instance {} is ready on loopback port {}", instance.id(), instance.port());
      } catch (InstancePreparationException | RuntimeException exception) {
        timings.finish(InstancePhaseTimings.Phase.REGISTRATION);
        timingReporter.logProvisioning(instance.id(), instance.timings(), "registration-failed");
        instance.readyFuture().completeExceptionally(exception);
        recordFailedStart(instance, "registration", exception);
        logger.warn(
            "Instance registration failed: {}: {}. Last output: {}",
            instance.id(),
            rootMessage(exception),
            lastOutput(instance));
        SupervisedProcess process = instance.process();
        if (process != null) {
          timings.begin(InstancePhaseTimings.Phase.SHUTDOWN);
          process.forceStop();
        }
      }
    }
  }

  private void failPreparation(ManagedInstance instance, Exception exception) {
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
    recordFailedStart(instance, "preparation", exception);
    timingReporter.logProvisioning(instance.id(), instance.timings(), "preparation-failed");
    logger.error("Unable to start managed instance " + instance.id(), exception);
    if (process != null) {
      instance.timings().begin(InstancePhaseTimings.Phase.SHUTDOWN);
      process.forceStop();
      return;
    }
    cleanup(instance);
  }

  private void finishCancelledPreparation(ManagedInstance instance) {
    if (instance.state() == InstanceState.STOPPING) {
      instance.lifecycle().transitionTo(InstanceState.STOPPED);
    }
    instance.timings().finish(InstancePhaseTimings.Phase.SHUTDOWN);
    timingReporter.logProvisioning(instance.id(), instance.timings(), "cancelled");
    cleanup(instance);
    instance.stoppedFuture().complete(0);
    logger.info("Cancelled instance startup for {}", instance.id());
  }

  private void cleanup(ManagedInstance instance) {
    synchronized (instance) {
      synchronized (this) {
        if (instances.get(instance.id()) != instance) {
          return;
        }
      }
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
                      failure.getMessage()));
      portAllocator.release(instance.port());
      resourceBudget.release(instance.id());
      try {
        directoryPreparer.suspend(instance.id());
      } catch (InstancePreparationException exception) {
        logger.error("Unable to suspend instance storage " + instance.directory(), exception);
      }
      if (!instance.blueprint().save()) {
        try {
          directoryPreparer.delete(instance.id());
        } catch (InstancePreparationException exception) {
          logger.error(
              "Unable to delete ephemeral instance directory " + instance.directory(), exception);
        }
      } else {
        metadata.writeBestEffort(instance, instance.state(), null);
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
      timingReporter.logProvisioning(instance.id(), instance.timings(), instance.state().name());
      timingReporter.logTermination(instance.id(), instance.timings(), instance.state().name());
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

  private static String lastOutput(ManagedInstance instance) {
    List<String> lines = instance.logs(1, 3).lines();
    return lines.isEmpty() ? "no managed process output" : String.join(" | ", lines);
  }

  private void recordFailedStart(ManagedInstance instance, String phase, Throwable failure) {
    if (!instance.markFailedStartDiagnosticsRecorded()) {
      return;
    }
    try {
      Path report = failedStartDiagnostics.record(instance, phase, failure);
      logger.warn("Retained failed-start diagnostics for {} at {}", instance.id(), report);
    } catch (IOException exception) {
      logger.warn(
          "Unable to retain failed-start diagnostics for {}: {}",
          instance.id(),
          exception.getMessage());
    }
  }

  private static Throwable rootCause(Throwable throwable) {
    Throwable current = throwable;
    while (current.getCause() != null) {
      current = current.getCause();
    }
    return current;
  }

  private static String rootMessage(Throwable throwable) {
    Throwable cause = rootCause(throwable);
    return cause.getMessage() == null ? cause.getClass().getSimpleName() : cause.getMessage();
  }

  private ManagedInstance startPersistent(String instanceId) throws InstanceOperationException {
    InstanceMetadata metadata = this.metadata.readPersistent(instanceId);
    ResolvedDefinition definition =
        resolveDefinition(
            metadata.blueprintId(),
            "Persistent instance "
                + instanceId
                + " references missing blueprint "
                + metadata.blueprintId());
    Blueprint blueprint = definition.blueprint();
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

      InstanceLifecycle lifecycle = new InstanceLifecycle(instanceId);
      lifecycle.transitionTo(InstanceState.PREPARING);
      instance =
          new ManagedInstance(
              instanceId,
              blueprint,
              definitionIdentity,
              port,
              directoryPreparer.root().resolve(instanceId),
              lifecycle,
              metadata.createdAt());
      instances.put(instanceId, instance);
      try {
        operationExecutor.execute(() -> prepareAndStart(instance, profile, true));
      } catch (RuntimeException exception) {
        instances.remove(instanceId);
        portAllocator.release(port);
        resourceBudget.release(instanceId);
        lifecycle.transitionTo(InstanceState.FAILED);
        throw new InstanceOperationException("Instance preparation queue is full", exception);
      }
    }
    return instance;
  }

  private InstanceMetadata requireCompatibleDefinition(InstanceMetadata metadata)
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
    InstanceDefinitionIdentity current =
        InstanceDefinitionIdentity.from(definition.blueprint(), definition.softwareProfile());
    return this.metadata.requireCompatibleDefinition(
        metadata, current, definition.blueprint().save());
  }

  private String uniqueInstanceId(String blueprintId) throws InstanceOperationException {
    for (int attempt = 0; attempt < ID_ATTEMPTS; attempt++) {
      String candidate = idGenerator.generate(blueprintId);
      if (!instances.containsKey(candidate)
          && !Files.exists(directoryPreparer.root().resolve(candidate))) {
        return candidate;
      }
    }
    throw new InstanceOperationException("Unable to generate a unique instance ID");
  }

  private ResolvedDefinition resolveDefinition(String blueprintId, String missingBlueprintMessage)
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

  private record ResolvedDefinition(Blueprint blueprint, SoftwareProfile softwareProfile) {}

  private static ThreadFactory threadFactory() {
    AtomicInteger sequence = new AtomicInteger();
    return runnable -> {
      Thread thread = new Thread(runnable, "sls-lite-operation-" + sequence.incrementAndGet());
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
}
