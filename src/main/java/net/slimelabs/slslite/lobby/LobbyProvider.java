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

    default Optional<SLSLimboDiagnostics> limboDiagnostics() {
        return Optional.empty();
    }

    default boolean bothUnavailable() {
        return server().isEmpty();
    }

    CompletableFuture<Void> evacuate(String serverName);

    default CompletableFuture<Void> evacuateForIntentionalStop(String serverName) {
        return evacuate(serverName);
    }

    default boolean beginIntentionalStop(String serverName) {
        return false;
    }

    default void cancelIntentionalStop(String serverName) {
    }

    default boolean prepareIntentionalStop(String serverName) {
        return false;
    }

    default CompletableFuture<RegisteredServer> cyclePrimary(
            String serverName,
            boolean reset
    ) {
        return CompletableFuture.failedFuture(
                new IllegalStateException("Managed lobby cycling is unavailable")
        );
    }

    @Override
    void close();
}
