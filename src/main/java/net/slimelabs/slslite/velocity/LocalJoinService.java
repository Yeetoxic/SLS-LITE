package net.slimelabs.slslite.velocity;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import net.slimelabs.slslite.blueprint.Blueprint;
import net.slimelabs.slslite.blueprint.BlueprintRepository;
import net.slimelabs.slslite.instance.InstanceOperationException;
import net.slimelabs.slslite.instance.InstanceState;
import net.slimelabs.slslite.instance.ManagedInstance;
import net.slimelabs.slslite.instance.ServerController;

import java.util.Comparator;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public final class LocalJoinService {

    private final ProxyServer proxy;
    private final BlueprintRepository blueprints;
    private final ServerController instances;

    public LocalJoinService(
            ProxyServer proxy,
            BlueprintRepository blueprints,
            ServerController instances
    ) {
        this.proxy = proxy;
        this.blueprints = blueprints;
        this.instances = instances;
    }

    public JoinAttempt join(Player player, String registry, String server)
            throws InstanceOperationException {
        Blueprint blueprint = blueprints.get(registry, server).orElseThrow(
                () -> new InstanceOperationException(
                        "Unknown server '" + server + "' in registry '" + registry + "'"
                )
        );

        Optional<ManagedInstance> existing = instances.getAll().stream()
                .filter(instance -> instance.blueprint().id().equals(blueprint.id()))
                .filter(instance -> instance.state() != InstanceState.STOPPING)
                .filter(instance -> instance.state() != InstanceState.STOPPED)
                .filter(instance -> instance.state() != InstanceState.FAILED)
                .sorted(Comparator
                        .comparing((ManagedInstance instance) ->
                                instance.state() == InstanceState.READY ? 0 : 1)
                        .thenComparing(ManagedInstance::createdAt))
                .findFirst();

        boolean created = existing.isEmpty();
        ManagedInstance instance = existing.isPresent()
                ? existing.get()
                : instances.start(blueprint.id());
        CompletableFuture<ConnectionRequestBuilder.Result> connection =
                instance.readyFuture().thenCompose(ready -> connect(player, ready));

        return new JoinAttempt(instance, created, connection);
    }

    public Optional<RegisteredServer> initialServer() {
        return instances.getAll().stream()
                .filter(instance -> instance.state() == InstanceState.READY)
                .sorted(Comparator.comparing(ManagedInstance::id))
                .map(instance -> proxy.getServer(instance.id()))
                .flatMap(Optional::stream)
                .findFirst();
    }

    private CompletableFuture<ConnectionRequestBuilder.Result> connect(
            Player player,
            ManagedInstance instance
    ) {
        RegisteredServer registered = proxy.getServer(instance.id()).orElseThrow(
                () -> new IllegalStateException(
                        "Ready instance is not registered with Velocity: " + instance.id()
                )
        );
        return player.createConnectionRequest(registered).connect();
    }

    public record JoinAttempt(
            ManagedInstance instance,
            boolean created,
            CompletableFuture<ConnectionRequestBuilder.Result> connection
    ) {
    }
}
