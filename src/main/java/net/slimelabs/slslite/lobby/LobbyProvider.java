package net.slimelabs.slslite.lobby;

import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface LobbyProvider extends AutoCloseable {

    void start();

    Optional<RegisteredServer> server();

    CompletableFuture<RegisteredServer> readyFuture();

    LobbyStatus status();

    boolean isLobby(String serverName);

    CompletableFuture<Void> evacuate(String serverName);

    @Override
    void close();
}
