package net.slimelabs.slslite.config;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

public record BackendMessagingConfig(
    boolean enabled,
    boolean commandRelayEnabled,
    int requestsPerWindow,
    int windowSeconds,
    List<BackendMessageSourceConfig> sources) {

  public static final int MAX_SOURCES = 64;

  public BackendMessagingConfig {
    if (requestsPerWindow < 1 || requestsPerWindow > 100) {
      throw new IllegalArgumentException(
          "backend messaging requests_per_window must be between 1 and 100");
    }
    if (windowSeconds < 1 || windowSeconds > 60) {
      throw new IllegalArgumentException(
          "backend messaging window_seconds must be between 1 and 60");
    }
    sources = List.copyOf(sources == null ? List.of() : sources);
    if (sources.size() > MAX_SOURCES) {
      throw new IllegalArgumentException(
          "backend messaging may define at most " + MAX_SOURCES + " sources");
    }
    if (enabled && sources.isEmpty()) {
      throw new IllegalArgumentException(
          "backend messaging requires at least one source when enabled");
    }
    Set<String> ids = new HashSet<>();
    Set<String> selectors = new HashSet<>();
    for (BackendMessageSourceConfig source : sources) {
      if (!ids.add(source.id())) {
        throw new IllegalArgumentException(
            "duplicate backend messaging source id '" + source.id() + "'");
      }
      String selector =
          source.exactServer() ? "server:" + source.server() : "blueprint:" + source.blueprint();
      if (!selectors.add(selector)) {
        throw new IllegalArgumentException(
            "duplicate backend messaging source selector '" + selector + "'");
      }
      if (source.actions().contains(BackendMessageAction.COMMAND) && !commandRelayEnabled) {
        throw new IllegalArgumentException(
            "backend messaging source '"
                + source.id()
                + "' permits command but command_relay_enabled is false");
      }
    }
  }

  public static BackendMessagingConfig defaults() {
    return new BackendMessagingConfig(false, false, 10, 10, List.of());
  }
}
