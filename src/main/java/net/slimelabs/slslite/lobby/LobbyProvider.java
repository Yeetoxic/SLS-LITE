package net.slimelabs.slslite.lobby;

import com.velocitypowered.api.proxy.server.RegisteredServer;

import java.util.Optional;
import java.util.concurrent.CompletableFuture;

public interface LobbyProvider {

    void start();

    Optional<RegisteredServer> server();

    CompletableFuture<RegisteredServer> readyFuture();

    boolean isLobby(String serverName);

    CompletableFuture<Void> evacuate(String serverName);
}
