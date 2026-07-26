package net.slimelabs.slslite.instance;

import net.slimelabs.slslite.blueprint.Blueprint;
import net.slimelabs.slslite.blueprint.BlueprintRepository;
import net.slimelabs.slslite.config.DefinitionCatalog;
import net.slimelabs.slslite.config.ManagedOutputConfig;
import net.slimelabs.slslite.config.ForwardingConfig;
import net.slimelabs.slslite.network.LoopbackPortAllocator;
import net.slimelabs.slslite.network.PortAllocationException;
import net.slimelabs.slslite.process.PaperProcessSpecFactory;
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

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.HashSet;
import java.util.Set;

public final class InstanceManager implements ServerController {

    private static final int ID_ATTEMPTS = 20;

    private final BlueprintRepository blueprints;
    private final SoftwareProfileRepository softwareProfiles;
    private final ResourceBudget resourceBudget;
    private final ManagedOutputConfig outputConfig;
    private final ForwardingConfig forwardingConfig;
    private final LoopbackPortAllocator portAllocator;
    private final InstanceDirectoryPreparer directoryPreparer;
    private final InstanceMetadataStore metadataStore;
    private final PaperProcessSpecFactory processSpecFactory;
    private final ProcessSupervisor processSupervisor;
    private final BackendRegistry backendRegistry;
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
            PaperProcessSpecFactory processSpecFactory,
            ProcessSupervisor processSupervisor,
            BackendRegistry backendRegistry,
            Logger logger
    ) {
        this.blueprints = blueprints;
        this.softwareProfiles = softwareProfiles;
        this.resourceBudget = resourceBudget;
        this.outputConfig = outputConfig;
        this.forwardingConfig = forwardingConfig;
        this.portAllocator = portAllocator;
        this.directoryPreparer = directoryPreparer;
        this.metadataStore = new InstanceMetadataStore(directoryPreparer.root());
        this.processSpecFactory = processSpecFactory;
        this.processSupervisor = processSupervisor;
        this.backendRegistry = backendRegistry;
        this.logger = logger;
        this.idGenerator = new InstanceIdGenerator();
        this.operationExecutor = new ThreadPoolExecutor(
                2,
                2,
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(32),
                threadFactory(),
                new ThreadPoolExecutor.AbortPolicy()
        );
    }

    @Override
    public ManagedInstance start(String blueprintId) throws InstanceOperationException {
        ResolvedDefinition definition = resolveDefinition(
                blueprintId,
                "Unknown blueprint: " + blueprintId
        );
        Blueprint blueprint = definition.blueprint();
        SoftwareProfile profile = definition.softwareProfile();

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
                        "Insufficient managed memory for " + blueprint.memoryLimitMiB() + " MiB"
                );
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
            ManagedInstance instance = new ManagedInstance(
                    instanceId,
                    blueprint,
                    port,
                    directory,
                    lifecycle
            );
            instances.put(instanceId, instance);

            try {
                operationExecutor.execute(() -> prepareAndStart(instance, profile, false));
            } catch (RuntimeException exception) {
                instances.remove(instanceId);
                portAllocator.release(port);
                resourceBudget.release(instanceId);
                lifecycle.transitionTo(InstanceState.FAILED);
                throw new InstanceOperationException(
                        "Instance preparation queue is full",
                        exception
                );
            }
            return instance;
        }
    }

    @Override
    public synchronized Collection<ManagedInstance> getAll() {
        return instances.values().stream()
                .sorted(Comparator.comparing(ManagedInstance::id))
                .toList();
    }

    @Override
    public Collection<String> persistentInstanceIds() {
        if (!Files.isDirectory(directoryPreparer.root())) {
            return List.of();
        }
        try (var directories = Files.list(directoryPreparer.root())) {
            return directories
                    .filter(Files::isDirectory)
                    .map(directory -> {
                        try {
                            return metadataStore.read(directory).orElse(null);
                        } catch (IOException | RuntimeException exception) {
                            return null;
                        }
                    })
                    .filter(java.util.Objects::nonNull)
                    .filter(InstanceMetadata::persistent)
                    .map(InstanceMetadata::instanceId)
                    .distinct()
                    .sorted()
                    .toList();
        } catch (IOException exception) {
            logger.warn(
                    "Unable to discover persistent instances: {}",
                    exception.getMessage()
            );
            return List.of();
        }
    }

    @Override
    public synchronized ManagedInstance get(String instanceId)
            throws InstanceOperationException {
        ManagedInstance instance = instances.get(instanceId);
        if (instance == null) {
            throw new InstanceOperationException("Unknown active instance: " + instanceId);
        }
        return instance;
    }

    @Override
    public void sendCommand(String instanceId, String command)
            throws InstanceOperationException {
        String normalized = normalizeConsoleCommand(command);
        ManagedInstance instance = get(instanceId);
        synchronized (instance) {
            SupervisedProcess process = instance.process();
            if (process == null) {
                throw new InstanceOperationException(
                        "Instance has no active process: " + instanceId
                );
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
            if (instance.state() == InstanceState.PREPARING) {
                instance.requestStop();
                instance.lifecycle().transitionTo(InstanceState.STOPPING);
                writeMetadataBestEffort(instance, InstanceState.STOPPING, null);
                instance.readyFuture().completeExceptionally(
                        new CancellationException(
                                "Instance startup was cancelled: " + instanceId
                        )
                );
                return instance.stoppedFuture();
            }
            if (instance.state() == InstanceState.STOPPING) {
                return instance.stoppedFuture();
            }
            SupervisedProcess process = instance.process();
            if (process == null) {
                throw new InstanceOperationException(
                        "Instance has no active process: " + instanceId
                );
            }
            unregister(instance);
            try {
                if (instance.state() == InstanceState.STARTING) {
                    instance.requestStop();
                    writeMetadataBestEffort(instance, InstanceState.STOPPING, process);
                    instance.readyFuture().completeExceptionally(
                            new CancellationException(
                                    "Instance startup was cancelled: " + instanceId
                            )
                    );
                    process.cancelStartup();
                } else {
                    writeMetadataBestEffort(instance, InstanceState.STOPPING, process);
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

    private CompletableFuture<ManagedInstance> cyclePersistent(
            String instanceId,
            boolean reset
    ) throws InstanceOperationException {
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
                        "Instance restart or reset is already in progress: " + instanceId
                );
            }
            active = instances.get(instanceId);
            try {
                metadata = active == null
                        ? readPersistentMetadata(instanceId)
                        : metadataFor(active);
                requireRestartable(metadata, active);
            } catch (InstanceOperationException exception) {
                pendingRestarts.remove(instanceId);
                throw exception;
            }
        }

        CompletableFuture<Integer> stopped;
        try {
            stopped = active == null
                    ? CompletableFuture.completedFuture(0)
                    : stop(instanceId);
        } catch (InstanceOperationException exception) {
            synchronized (this) {
                pendingRestarts.remove(instanceId);
            }
            throw exception;
        }

        return stopped.thenApply(ignored -> {
            try {
                if (reset) {
                    resetPersistent(instanceId);
                }
                return startPersistent(instanceId);
            } catch (InstanceOperationException exception) {
                throw new java.util.concurrent.CompletionException(exception);
            }
        }).whenComplete((ignored, failure) -> {
            synchronized (this) {
                pendingRestarts.remove(instanceId);
            }
        });
    }

    private void resetPersistent(String instanceId) throws InstanceOperationException {
        InstanceMetadata metadata = readPersistentMetadata(instanceId);
        requireRestartable(metadata, null);
        ResolvedDefinition definition = resolveDefinition(
                metadata.blueprintId(),
                "Persistent instance " + instanceId
                        + " references missing blueprint " + metadata.blueprintId()
        );
        Blueprint blueprint = definition.blueprint();
        SoftwareProfile profile = definition.softwareProfile();
        try {
            Path baseDirectory = processSpecFactory.resolveBaseDirectory(
                    profile,
                    blueprint.version()
            );
            InstanceMetadata stopped = metadata.withoutProcess(InstanceState.STOPPED);
            directoryPreparer.replace(
                    instanceId,
                    baseDirectory,
                    directory -> metadataStore.write(directory, stopped)
            );
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

        operationExecutor.shutdownNow();
        processSupervisor.shutdown(timeout);
        for (ManagedInstance instance : snapshot) {
            cleanup(instance);
        }
    }

    private void prepareAndStart(
            ManagedInstance instance,
            SoftwareProfile profile,
            boolean reuseDirectory
    ) {
        try {
            Path prepared;
            if (reuseDirectory) {
                prepared = instance.directory();
                if (!Files.isDirectory(prepared)) {
                    throw new InstancePreparationException(
                            "Persistent instance directory does not exist: " + prepared
                    );
                }
            } else {
                Path baseDirectory = processSpecFactory.resolveBaseDirectory(
                        profile,
                        instance.blueprint().version()
                );
                prepared = directoryPreparer.prepare(instance.id(), baseDirectory);
            }
            if (!prepared.equals(instance.directory())) {
                throw new InstanceOperationException("Prepared instance path changed unexpectedly");
            }
            instance.configureOutput(outputConfig);
            writeMetadata(instance, InstanceState.PREPARING, null);
            ServerPropertiesEditor.applyManagedNetworkSettings(
                    prepared,
                    instance.port(),
                    instance.blueprint().maxPlayers()
            );
            PaperForwardingEditor.apply(prepared, forwardingConfig);
            ProcessSpec spec = processSpecFactory.create(
                    profile,
                    instance.blueprint(),
                    instance.id(),
                    prepared,
                    instance.port()
            );
            SupervisedProcess process;
            synchronized (instance) {
                if (instance.stopRequested()) {
                    finishCancelledPreparation(instance);
                    return;
                }
                writeMetadata(instance, InstanceState.STARTING, null);
                process = processSupervisor.start(
                        instance.id(),
                        spec,
                        instance.lifecycle(),
                        line -> {
                            instance.appendLog(line);
                            if (instance.mirrorsOutputToProxyConsole()) {
                                logger.info("[{}] {}", instance.id(), line);
                            }
                            instance.takeOutputFailure().ifPresent(failure -> logger.warn(
                                    "Temporary console log disabled for {}: {}",
                                    instance.id(),
                                    failure.getMessage()
                            ));
                        }
                );
                instance.attachProcess(process);
            }
            process.exitFuture().whenComplete((exitCode, failure) -> {
                cleanup(instance);
                if (failure == null) {
                    instance.stoppedFuture().complete(exitCode);
                } else {
                    instance.stoppedFuture().completeExceptionally(failure);
                }
            });
            writeMetadata(instance, InstanceState.STARTING, process);
            process.readyFuture().whenComplete((ignored, failure) -> {
                if (failure == null) {
                    registerReady(instance);
                } else {
                    instance.readyFuture().completeExceptionally(failure);
                }
            });
        } catch (Exception exception) {
            failPreparation(instance, exception);
        }
    }

    private void registerReady(ManagedInstance instance) {
        synchronized (instance) {
            if (instance.state() != InstanceState.READY || !isActive(instance)) {
                instance.readyFuture().completeExceptionally(
                        new IllegalStateException("Instance exited before Velocity registration")
                );
                return;
            }
            try {
                writeMetadata(instance, InstanceState.READY, instance.process());
                backendRegistry.register(
                        instance.id(),
                        new InetSocketAddress(InetAddress.getLoopbackAddress(), instance.port())
                );
                instance.registered(true);
                instance.readyFuture().complete(instance);
                logger.info(
                        "Instance {} is ready on loopback port {}",
                        instance.id(),
                        instance.port()
                );
            } catch (InstancePreparationException | RuntimeException exception) {
                instance.readyFuture().completeExceptionally(exception);
                SupervisedProcess process = instance.process();
                if (process != null) {
                    process.forceStop();
                }
            }
        }
    }

    private void failPreparation(ManagedInstance instance, Exception exception) {
        SupervisedProcess process;
        synchronized (instance) {
            if (instance.state() == InstanceState.STOPPING
                    && instance.stopRequested()) {
                finishCancelledPreparation(instance);
                return;
            }
            if (instance.state() == InstanceState.PREPARING) {
                instance.lifecycle().transitionTo(InstanceState.FAILED);
            }
            instance.readyFuture().completeExceptionally(exception);
            process = instance.process();
        }
        logger.error("Unable to start managed instance " + instance.id(), exception);
        if (process != null) {
            process.forceStop();
            return;
        }
        cleanup(instance);
    }

    private void finishCancelledPreparation(ManagedInstance instance) {
        if (instance.state() == InstanceState.STOPPING) {
            instance.lifecycle().transitionTo(InstanceState.STOPPED);
        }
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
            unregister(instance);
            instance.closeOutput();
            instance.takeOutputFailure().ifPresent(failure -> logger.warn(
                    "Unable to close temporary console log for {}: {}",
                    instance.id(),
                    failure.getMessage()
            ));
            portAllocator.release(instance.port());
            resourceBudget.release(instance.id());
            if (!instance.blueprint().save()) {
                try {
                    directoryPreparer.delete(instance.id());
                } catch (InstancePreparationException exception) {
                    logger.error(
                            "Unable to delete ephemeral instance directory " + instance.directory(),
                            exception
                    );
                }
            } else {
                writeMetadataBestEffort(instance, instance.state(), null);
            }
            if (!instance.readyFuture().isDone()) {
                instance.readyFuture().completeExceptionally(
                        new IllegalStateException("Instance stopped before becoming ready")
                );
            }
            synchronized (this) {
                instances.remove(instance.id(), instance);
            }
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

    private ManagedInstance startPersistent(String instanceId)
            throws InstanceOperationException {
        InstanceMetadata metadata = readPersistentMetadata(instanceId);
        ResolvedDefinition definition = resolveDefinition(
                metadata.blueprintId(),
                "Persistent instance " + instanceId
                        + " references missing blueprint " + metadata.blueprintId()
        );
        Blueprint blueprint = definition.blueprint();
        SoftwareProfile profile = definition.softwareProfile();
        requireRestartable(metadata, null);

        int port;
        ManagedInstance instance;
        synchronized (this) {
            if (closed) {
                throw new InstanceOperationException("Instance manager is shutting down");
            }
            if (instances.containsKey(instanceId)) {
                throw new InstanceOperationException(
                        "Instance is already active: " + instanceId
                );
            }
            enforceInstanceLimit(blueprint, instanceId);
            if (!resourceBudget.tryReserve(instanceId, blueprint.memoryLimitMiB())) {
                throw new InstanceOperationException(
                        "Insufficient managed memory for "
                                + blueprint.memoryLimitMiB() + " MiB"
                );
            }
            try {
                port = portAllocator.allocate();
            } catch (PortAllocationException exception) {
                resourceBudget.release(instanceId);
                throw new InstanceOperationException(exception.getMessage(), exception);
            }

            InstanceLifecycle lifecycle = new InstanceLifecycle(instanceId);
            lifecycle.transitionTo(InstanceState.PREPARING);
            instance = new ManagedInstance(
                    instanceId,
                    blueprint,
                    port,
                    directoryPreparer.root().resolve(instanceId),
                    lifecycle,
                    metadata.createdAt()
            );
            instances.put(instanceId, instance);
            try {
                operationExecutor.execute(() -> prepareAndStart(instance, profile, true));
            } catch (RuntimeException exception) {
                instances.remove(instanceId);
                portAllocator.release(port);
                resourceBudget.release(instanceId);
                lifecycle.transitionTo(InstanceState.FAILED);
                throw new InstanceOperationException(
                        "Instance preparation queue is full",
                        exception
                );
            }
        }
        return instance;
    }

    private InstanceMetadata readPersistentMetadata(String instanceId)
            throws InstanceOperationException {
        Path directory = directoryPreparer.root().resolve(instanceId);
        try {
            InstanceMetadata metadata = metadataStore.read(directory).orElseThrow(
                    () -> new InstanceOperationException(
                            "No persistent SLS-LITE instance exists: " + instanceId
                    )
            );
            if (!metadata.instanceId().equals(instanceId)) {
                throw new InstanceOperationException(
                        "Persistent metadata does not match instance ID: " + instanceId
                );
            }
            return metadata;
        } catch (IOException exception) {
            throw new InstanceOperationException(
                    "Unable to read persistent instance metadata: " + instanceId,
                    exception
            );
        }
    }

    private static InstanceMetadata metadataFor(ManagedInstance instance) {
        return new InstanceMetadata(
                instance.id(),
                instance.blueprint().id(),
                instance.blueprint().save(),
                instance.state(),
                instance.createdAt(),
                instance.processId().isPresent() ? instance.processId().getAsLong() : null,
                instance.processStartedAt().orElse(null)
        );
    }

    private static void requireRestartable(
            InstanceMetadata metadata,
            ManagedInstance active
    ) throws InstanceOperationException {
        if (!metadata.persistent()) {
            throw new InstanceOperationException(
                    "Instance is ephemeral and cannot be restarted: " + metadata.instanceId()
            );
        }
        if (active == null && isRecordedProcessRunning(metadata)) {
            throw new InstanceOperationException(
                    "Persistent instance process is still running: " + metadata.instanceId()
            );
        }
    }

    private static boolean isRecordedProcessRunning(InstanceMetadata metadata) {
        if (metadata.processId() == null) {
            return false;
        }
        return ProcessHandle.of(metadata.processId())
                .filter(ProcessHandle::isAlive)
                .filter(handle -> metadata.processStartedAt() == null
                        || handle.info().startInstant().isEmpty()
                        || handle.info().startInstant().orElseThrow()
                                .equals(metadata.processStartedAt()))
                .isPresent();
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

    private ResolvedDefinition resolveDefinition(
            String blueprintId,
            String missingBlueprintMessage
    ) throws InstanceOperationException {
        DefinitionCatalog.Snapshot definitions = blueprints.catalog().snapshot();
        Blueprint blueprint = java.util.Optional.ofNullable(
                definitions.blueprints().get(blueprintId)
        ).orElseThrow(() -> new InstanceOperationException(missingBlueprintMessage));
        SoftwareProfile profile = blueprints.catalog() == softwareProfiles.catalog()
                ? definitions.softwareProfiles().get(blueprint.software())
                : softwareProfiles.get(blueprint.software()).orElse(null);
        if (profile == null) {
            throw new InstanceOperationException(
                    "Missing software profile: " + blueprint.software()
            );
        }
        return new ResolvedDefinition(blueprint, profile);
    }

    private void enforceInstanceLimit(Blueprint blueprint, String restartingId)
            throws InstanceOperationException {
        long active = instances.values().stream()
                .filter(instance -> instance.blueprint().id().equals(blueprint.id()))
                .filter(instance -> restartingId == null
                        || !instance.id().equals(restartingId))
                .filter(instance -> instance.state() != InstanceState.STOPPING
                        && instance.state() != InstanceState.STOPPED
                        && instance.state() != InstanceState.FAILED)
                .count();
        if (active >= blueprint.maxInstances()) {
            throw new InstanceOperationException(
                    "Blueprint " + blueprint.type() + "/" + blueprint.id()
                            + " has reached its limit of "
                            + blueprint.maxInstances() + " active instance(s)"
            );
        }
    }

    private record ResolvedDefinition(
            Blueprint blueprint,
            SoftwareProfile softwareProfile
    ) {
    }

    private void writeMetadata(
            ManagedInstance instance,
            InstanceState state,
            SupervisedProcess process
    ) throws InstancePreparationException {
        Long processId = process == null ? null : process.processId();
        java.time.Instant processStartedAt = process == null
                ? null
                : process.processStartedAt().orElse(null);
        InstanceMetadata metadata = new InstanceMetadata(
                instance.id(),
                instance.blueprint().id(),
                instance.blueprint().save(),
                state,
                instance.createdAt(),
                processId,
                processStartedAt
        );
        try {
            metadataStore.write(instance.directory(), metadata);
        } catch (IOException exception) {
            throw new InstancePreparationException(
                    "Unable to write instance metadata for " + instance.id(),
                    exception
            );
        }
    }

    private void writeMetadataBestEffort(
            ManagedInstance instance,
            InstanceState state,
            SupervisedProcess process
    ) {
        if (!Files.isDirectory(instance.directory())) {
            return;
        }
        try {
            writeMetadata(instance, state, process);
        } catch (InstancePreparationException exception) {
            logger.warn(
                    "Unable to update instance metadata for {}: {}",
                    instance.id(),
                    exception.getMessage()
            );
        }
    }

    private static ThreadFactory threadFactory() {
        AtomicInteger sequence = new AtomicInteger();
        return runnable -> {
            Thread thread = new Thread(
                    runnable,
                    "sls-lite-operation-" + sequence.incrementAndGet()
            );
            thread.setDaemon(true);
            return thread;
        };
    }

    private static String normalizeConsoleCommand(String command)
            throws InstanceOperationException {
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
            throw new InstanceOperationException(
                    "Console command exceeds the 4096 character limit"
            );
        }
        return normalized;
    }
}
