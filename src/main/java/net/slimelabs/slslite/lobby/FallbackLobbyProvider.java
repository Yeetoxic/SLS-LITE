package net.slimelabs.slslite.lobby;

import com.velocitypowered.api.proxy.ConnectionRequestBuilder;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.util.List;
import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;

public final class FallbackLobbyProvider implements LobbyProvider {

    private final ProxyServer proxy;
    private final LobbyProvider primary;
    private final LobbyProvider limbo;
    private final CompletableFuture<RegisteredServer> ready = new CompletableFuture<>();
    private boolean started;
    private boolean closed;

    public FallbackLobbyProvider(
            ProxyServer proxy,
            LobbyProvider primary,
            LobbyProvider limbo
    ) {
        this.proxy = proxy;
        this.primary = primary;
        this.limbo = limbo;
    }

    @Override
    public synchronized void start() {
        if (started || closed) {
            return;
        }
        started = true;
        AtomicInteger failures = new AtomicInteger();
        connectReadiness(primary, failures);
        connectReadiness(limbo, failures);
        limbo.start();
        primary.start();
    }

    @Override
    public Optional<RegisteredServer> server() {
        Optional<RegisteredServer> preferred = primary.server();
        return preferred.isPresent() ? preferred : limbo.server();
    }

    @Override
    public CompletableFuture<RegisteredServer> readyFuture() {
        return ready;
    }

    @Override
    public LobbyStatus status() {
        if (primary.server().isPresent()) {
            return primary.status();
        }
        return limbo.status();
    }

    @Override
    public boolean isLobby(String serverName) {
        return primary.isLobby(serverName) || limbo.isLobby(serverName);
    }

    @Override
    public Optional<RegisteredServer> fallbackServer(String failedLobbyName) {
        if (primary.isLobby(failedLobbyName)) {
            return limbo.server();
        }
        if (limbo.isLobby(failedLobbyName)) {
            return primary.server();
        }
        return Optional.empty();
    }

    @Override
    public CompletableFuture<Void> evacuate(String serverName) {
        if (isLobby(serverName)) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("The active lobby cannot be evacuated")
            );
        }
        RegisteredServer source = proxy.getServer(serverName).orElse(null);
        if (source == null || source.getPlayersConnected().isEmpty()) {
            return CompletableFuture.completedFuture(null);
        }
        RegisteredServer target = server().orElse(null);
        if (target == null) {
            return CompletableFuture.failedFuture(
                    new IllegalStateException("No lobby is ready")
            );
        }
        List<CompletableFuture<Void>> transfers = source.getPlayersConnected().stream()
                .map(player -> transfer(player, target))
                .toList();
        return CompletableFuture.allOf(transfers.toArray(CompletableFuture[]::new));
    }

    @Override
    public synchronized void close() {
        if (closed) {
            return;
        }
        closed = true;
        primary.close();
        limbo.close();
        if (!ready.isDone()) {
            ready.completeExceptionally(
                    new IllegalStateException("Lobby providers are shutting down")
            );
        }
    }

    private void connectReadiness(
            LobbyProvider provider,
            AtomicInteger failures
    ) {
        provider.readyFuture().whenComplete((server, failure) -> {
            if (failure == null) {
                ready.complete(server);
            } else if (failures.incrementAndGet() == 2) {
                ready.completeExceptionally(
                        new IllegalStateException(
                                "The primary lobby and SLS-Limbo both failed",
                                failure
                        )
                );
            }
        });
    }

    private static CompletableFuture<Void> transfer(
            Player player,
            RegisteredServer target
    ) {
        return player.createConnectionRequest(target).connect().thenAccept(result -> {
            if (result.getStatus() != ConnectionRequestBuilder.Status.SUCCESS
                    && result.getStatus()
                    != ConnectionRequestBuilder.Status.ALREADY_CONNECTED) {
                throw new CompletionException(new IllegalStateException(
                        "Unable to move " + player.getUsername()
                                + " to lobby: " + result.getStatus()
                ));
            }
        });
    }
}
