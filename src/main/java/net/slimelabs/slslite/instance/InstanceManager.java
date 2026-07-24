package net.slimelabs.slslite.instance;

import net.slimelabs.slslite.blueprint.Blueprint;
import net.slimelabs.slslite.blueprint.BlueprintRepository;
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

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

public final class InstanceManager implements ServerController {

    private static final int ID_ATTEMPTS = 20;

    private final BlueprintRepository blueprints;
    private final SoftwareProfileRepository softwareProfiles;
    private final ResourceBudget resourceBudget;
    private final LoopbackPortAllocator portAllocator;
    private final InstanceDirectoryPreparer directoryPreparer;
    private final PaperProcessSpecFactory processSpecFactory;
    private final ProcessSupervisor processSupervisor;
    private final BackendRegistry backendRegistry;
    private final Logger logger;
    private final InstanceIdGenerator idGenerator;
    private final ThreadPoolExecutor operationExecutor;
    private final Map<String, ManagedInstance> instances = new java.util.HashMap<>();

    private boolean closed;

    public InstanceManager(
            BlueprintRepository blueprints,
            SoftwareProfileRepository softwareProfiles,
            ResourceBudget resourceBudget,
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
        this.portAllocator = portAllocator;
        this.directoryPreparer = directoryPreparer;
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
        Blueprint blueprint = blueprints.get(blueprintId).orElseThrow(
                () -> new InstanceOperationException("Unknown blueprint: " + blueprintId)
        );
        SoftwareProfile profile = softwareProfiles.get(blueprint.software()).orElseThrow(
                () -> new InstanceOperationException(
                        "Missing software profile: " + blueprint.software()
                )
        );

        String instanceId;
        int port;
        synchronized (this) {
            if (closed) {
                throw new InstanceOperationException("Instance manager is shutting down");
            }
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
                operationExecutor.execute(() -> prepareAndStart(instance, profile));
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
    public synchronized ManagedInstance get(String instanceId)
            throws InstanceOperationException {
        ManagedInstance instance = instances.get(instanceId);
        if (instance == null) {
            throw new InstanceOperationException("Unknown active instance: " + instanceId);
        }
        return instance;
    }

    @Override
    public CompletableFuture<Integer> stop(String instanceId) throws InstanceOperationException {
        ManagedInstance instance = get(instanceId);
        synchronized (instance) {
            if (instance.state() == InstanceState.PREPARING) {
                throw new InstanceOperationException(
                        "Instance is still preparing and cannot be stopped yet: " + instanceId
                );
            }
            SupervisedProcess process = instance.process();
            if (process == null) {
                throw new InstanceOperationException(
                        "Instance has no active process: " + instanceId
                );
            }
            unregister(instance);
            try {
                process.stop();
                return instance.stoppedFuture();
            } catch (IllegalStateException exception) {
                throw new InstanceOperationException(exception.getMessage(), exception);
            }
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

    private void prepareAndStart(ManagedInstance instance, SoftwareProfile profile) {
        try {
            Path baseDirectory = processSpecFactory.resolveBaseDirectory(
                    profile,
                    instance.blueprint().version()
            );
            Path prepared = directoryPreparer.prepare(instance.id(), baseDirectory);
            if (!prepared.equals(instance.directory())) {
                throw new InstanceOperationException("Prepared instance path changed unexpectedly");
            }
            ServerPropertiesEditor.applyManagedNetworkSettings(prepared, instance.port());
            ProcessSpec spec = processSpecFactory.create(
                    profile,
                    instance.blueprint(),
                    instance.id(),
                    prepared,
                    instance.port()
            );
            SupervisedProcess process = processSupervisor.start(
                    instance.id(),
                    spec,
                    instance.lifecycle(),
                    line -> logger.info("[{}] {}", instance.id(), line)
            );
            instance.attachProcess(process);
            process.readyFuture().whenComplete((ignored, failure) -> {
                if (failure == null) {
                    registerReady(instance);
                } else {
                    instance.readyFuture().completeExceptionally(failure);
                }
            });
            process.exitFuture().whenComplete((exitCode, failure) -> {
                cleanup(instance);
                if (failure == null) {
                    instance.stoppedFuture().complete(exitCode);
                } else {
                    instance.stoppedFuture().completeExceptionally(failure);
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
            } catch (RuntimeException exception) {
                instance.readyFuture().completeExceptionally(exception);
                SupervisedProcess process = instance.process();
                if (process != null) {
                    process.forceStop();
                }
            }
        }
    }

    private void failPreparation(ManagedInstance instance, Exception exception) {
        synchronized (instance) {
            if (instance.state() == InstanceState.PREPARING) {
                instance.lifecycle().transitionTo(InstanceState.FAILED);
            }
            instance.readyFuture().completeExceptionally(exception);
        }
        logger.error("Unable to start managed instance " + instance.id(), exception);
        cleanup(instance);
    }

    private void cleanup(ManagedInstance instance) {
        synchronized (instance) {
            synchronized (this) {
                if (instances.get(instance.id()) != instance) {
                    return;
                }
            }
            unregister(instance);
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

    private String uniqueInstanceId(String blueprintId) throws InstanceOperationException {
        for (int attempt = 0; attempt < ID_ATTEMPTS; attempt++) {
            String candidate = idGenerator.generate(blueprintId);
            if (!instances.containsKey(candidate)) {
                return candidate;
            }
        }
        throw new InstanceOperationException("Unable to generate a unique instance ID");
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
}
