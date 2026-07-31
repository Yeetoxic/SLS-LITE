package net.slimelabs.slslite.command;

import com.velocitypowered.api.command.CommandSource;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import net.kyori.adventure.text.format.NamedTextColor;
import net.slimelabs.slslite.instance.ManagedInstance;
import net.slimelabs.slslite.instance.ServerController;

/**
 * Shared command-side lookup for active and persistent managed instances.
 */
public final class CommandInstanceAccess {

  private final ProxyServer proxy;
  private final ServerController instances;

  public CommandInstanceAccess(ProxyServer proxy, ServerController instances) {
    this.proxy = proxy;
    this.instances = instances;
  }

  public ManagedInstance resolve(CommandSource source, String requestedId) {
    Optional<String> currentServer =
        source instanceof Player player
            ? player.getCurrentServer().map(connection -> connection.getServerInfo().getName())
            : Optional.empty();
    InstanceTargetResolver.Resolution resolution =
        InstanceTargetResolver.resolve(
            requestedId, source instanceof Player, currentServer, id -> find(id) != null);
    if (resolution.error() != null) {
      source.sendMessage(CommandMessages.message(resolution.error(), NamedTextColor.RED));
      return null;
    }
    ManagedInstance instance = find(resolution.instanceId());
    if (instance == null) {
      source.sendMessage(
          CommandMessages.message("No such server " + requestedId, NamedTextColor.RED));
    }
    return instance;
  }

  public ManagedInstance find(String id) {
    return instances.getAll().stream()
        .filter(instance -> instance.id().equals(id))
        .findFirst()
        .orElse(null);
  }

  public List<Player> playersOn(ManagedInstance instance) {
    return proxy
        .getServer(instance.id())
        .map(server -> List.copyOf(server.getPlayersConnected()))
        .orElseGet(List::of);
  }

  public List<String> activeIds() {
    return instances.getAll().stream().map(ManagedInstance::id).sorted().toList();
  }

  public List<String> persistentIds() {
    Set<String> ids = new LinkedHashSet<>(activeIds());
    ids.addAll(instances.persistentInstanceIds());
    return ids.stream().sorted().toList();
  }
}
