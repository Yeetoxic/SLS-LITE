package net.slimelabs.slslite.velocity;

import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;
import net.slimelabs.slslite.blueprint.VSLSBlueprintAnnotations;
import net.slimelabs.slslite.instance.InstanceOperationException;
import net.slimelabs.slslite.instance.ManagedInstance;
import net.slimelabs.slslite.instance.ServerController;
import org.slf4j.Logger;

public final class BlueprintJoinActionService {

  private final ServerController instances;
  private final Logger logger;
  private final Map<UUID, String> currentBackends = new HashMap<>();

  public BlueprintJoinActionService(ServerController instances, Logger logger) {
    this.instances = instances;
    this.logger = logger;
  }

  public void connected(Player player, RegisteredServer server) {
    String backend = server.getServerInfo().getName();
    synchronized (this) {
      if (backend.equals(currentBackends.put(player.getUniqueId(), backend))) {
        return;
      }
    }

    ManagedInstance instance;
    try {
      instance = instances.get(backend);
    } catch (InstanceOperationException ignored) {
      return;
    }

    for (String configured :
        VSLSBlueprintAnnotations.onJoinCommands(instance.blueprint().annotations())) {
      String command =
          configured
              .replace("{PLAYER_NAME}", player.getUsername())
              .replace("{PLAYER_UUID}", player.getUniqueId().toString());
      try {
        instances.sendCommand(instance.id(), command);
      } catch (InstanceOperationException exception) {
        logger.warn(
            "Unable to run on-join action on {} for {}: {}",
            instance.id(),
            player.getUsername(),
            exception.getMessage());
      }
    }
  }

  public synchronized void disconnect(UUID playerId) {
    currentBackends.remove(playerId);
  }
}
