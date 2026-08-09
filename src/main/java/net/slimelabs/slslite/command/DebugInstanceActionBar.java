package net.slimelabs.slslite.command;

import com.velocitypowered.api.proxy.Player;
import java.time.Duration;
import java.util.Optional;
import java.util.OptionalDouble;
import java.util.OptionalLong;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.slimelabs.slslite.instance.ManagedInstance;
import net.slimelabs.slslite.velocity.LocalJoinService;

/** Builds the bounded, player-local runtime summary used by debug mode. */
final class DebugInstanceActionBar implements DebugPlayerRegistry.ActionBarFeed {

  private static final double BYTES_PER_MIB = 1024.0 * 1024.0;
  private static final TextColor SLS_BLUE = TextColor.color(46, 112, 255);

  private final CommandInstanceAccess instances;
  private final LocalJoinService joins;
  private final ConcurrentMap<UUID, CpuSample> cpuSamples = new ConcurrentHashMap<>();

  DebugInstanceActionBar(CommandInstanceAccess instances, LocalJoinService joins) {
    this.instances = java.util.Objects.requireNonNull(instances, "instances");
    this.joins = joins;
  }

  @Override
  public Optional<Component> render(Player player) {
    UUID playerId = player.getUniqueId();
    if (joins != null
        && (joins.queued(playerId).isPresent() || joins.isPresentingActionBar(playerId))) {
      cpuSamples.remove(playerId);
      return Optional.empty();
    }
    String currentServer =
        player
            .getCurrentServer()
            .map(connection -> connection.getServerInfo().getName())
            .orElse(null);
    ManagedInstance instance = currentServer == null ? null : instances.find(currentServer);
    if (instance == null) {
      cpuSamples.remove(playerId);
      return Optional.empty();
    }

    OptionalLong residentBytes =
        instance
            .processResources()
            .flatMap(
                snapshot ->
                    snapshot.residentBytes().isPresent()
                        ? Optional.of(snapshot.residentBytes())
                        : Optional.empty())
            .orElseGet(OptionalLong::empty);
    OptionalDouble cpuPercent = cpuPercent(playerId, instance);
    int players = instances.playersOn(instance).size();
    int playerLimit = instance.blueprint().maxPlayers();

    Component summary =
        Component.text("SLS Debug", SLS_BLUE)
            .append(separator())
            .append(Component.text(instance.id(), NamedTextColor.GOLD))
            .append(separator())
            .append(Component.text("RSS ", NamedTextColor.GRAY))
            .append(memory(instance.blueprint().memoryLimitMiB(), residentBytes))
            .append(separator())
            .append(Component.text("CPU ", NamedTextColor.GRAY))
            .append(
                Component.text(
                    cpuPercent.isPresent()
                        ? String.format(java.util.Locale.ROOT, "%.0f%%", cpuPercent.getAsDouble())
                        : "n/a",
                    NamedTextColor.AQUA))
            .append(separator())
            .append(Component.text("Players ", NamedTextColor.GRAY))
            .append(
                Component.text(
                    players + "/" + playerLimit,
                    players >= playerLimit ? NamedTextColor.YELLOW : NamedTextColor.GREEN))
            .append(separator())
            .append(
                Component.text(
                    CommandMessages.statusName(instance.state()),
                    CommandMessages.statusColor(instance.state())));
    return Optional.of(summary);
  }

  @Override
  public void remove(UUID playerId) {
    cpuSamples.remove(playerId);
  }

  @Override
  public void clear() {
    cpuSamples.clear();
  }

  private OptionalDouble cpuPercent(UUID playerId, ManagedInstance instance) {
    Optional<Duration> cpuTime = instance.processCpuTime();
    if (cpuTime.isEmpty()) {
      cpuSamples.remove(playerId);
      return OptionalDouble.empty();
    }
    long sampledAt = System.nanoTime();
    CpuSample current = new CpuSample(instance.id(), cpuTime.orElseThrow().toNanos(), sampledAt);
    CpuSample previous = cpuSamples.put(playerId, current);
    if (previous == null || !previous.instanceId().equals(instance.id())) {
      return OptionalDouble.empty();
    }
    long elapsed = sampledAt - previous.sampledAtNanos();
    long consumed = current.cpuNanos() - previous.cpuNanos();
    if (elapsed <= 0L || consumed < 0L) {
      return OptionalDouble.empty();
    }
    return calculateCpuPercent(consumed, elapsed);
  }

  static OptionalDouble calculateCpuPercent(long consumedCpuNanos, long elapsedNanos) {
    if (consumedCpuNanos < 0L || elapsedNanos <= 0L) {
      return OptionalDouble.empty();
    }
    return OptionalDouble.of(consumedCpuNanos * 100.0 / elapsedNanos);
  }

  static Component memory(int budget, OptionalLong residentBytes) {
    if (residentBytes.isEmpty()) {
      return Component.text("n/a / " + budget + " MiB", NamedTextColor.AQUA);
    }
    double used = residentBytes.getAsLong() / BYTES_PER_MIB;
    double ratio = used / budget;
    NamedTextColor color =
        ratio >= 1.0
            ? NamedTextColor.RED
            : ratio >= 0.85 ? NamedTextColor.YELLOW : NamedTextColor.GREEN;
    return Component.text(
        String.format(java.util.Locale.ROOT, "%.0f / %d MiB (%.0f%%)", used, budget, ratio * 100.0),
        color);
  }

  private static Component separator() {
    return Component.text("  •  ", NamedTextColor.DARK_GRAY);
  }

  private record CpuSample(String instanceId, long cpuNanos, long sampledAtNanos) {}
}
