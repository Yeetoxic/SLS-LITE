package net.slimelabs.slslite.lobby;

import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

public interface LobbyProvider extends AutoCloseable {

    void start();

    Optional<RegisteredServer> server();

    CompletableFuture<RegisteredServer> readyFuture();

    LobbyStatus status();

    boolean isLobby(String serverName);

    default Optional<RegisteredServer> fallbackServer(String failedLobbyName) {
        return Optional.empty();
    }

    default boolean isHoldingLobby(String serverName) {
        return false;
    }

    default Optional<RegisteredServer> primaryServer() {
        return server();
    }

    default void addPrimaryReadyListener(Consumer<RegisteredServer> listener) {
        readyFuture().thenAccept(listener);
    }

    default void markPrimaryUnavailable(String serverName) {
    }

    CompletableFuture<Void> evacuate(String serverName);

    @Override
    void close();
}
