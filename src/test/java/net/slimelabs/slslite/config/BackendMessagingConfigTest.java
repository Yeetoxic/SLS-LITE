package net.slimelabs.slslite.config;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class BackendMessagingConfigTest {

  @Test
  void defaultsAreDisabledAndTrustNoSources() {
    BackendMessagingConfig config = BackendMessagingConfig.defaults();

    assertFalse(config.enabled());
    assertFalse(config.commandRelayEnabled());
    assertEquals(10, config.requestsPerWindow());
    assertEquals(10, config.windowSeconds());
    assertEquals(List.of(), config.sources());
  }

  @Test
  void sourceRequiresExactlyOneSelectorAndAnAction() {
    assertThrows(
        IllegalArgumentException.class,
        () -> source("source", "", "", Set.of(BackendMessageAction.MATCHMAKE), List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            source(
                "source",
                "lobby",
                "network/lobby",
                Set.of(BackendMessageAction.MATCHMAKE),
                List.of()));
    assertThrows(
        IllegalArgumentException.class, () -> source("source", "lobby", "", Set.of(), List.of()));
  }

  @Test
  void commandRelayRequiresBothGlobalAndPerSourceOptIn() {
    BackendMessageSourceConfig source =
        source("lobby", "lobby", "", Set.of(BackendMessageAction.COMMAND), List.of("/SLS   JOIN"));

    assertEquals(List.of("sls join"), source.commandRoots());
    assertThrows(
        IllegalArgumentException.class,
        () -> new BackendMessagingConfig(true, false, 10, 10, List.of(source)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            source("lobby", "lobby", "", Set.of(BackendMessageAction.COMMAND), List.of("server")));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            source(
                "lobby", "lobby", "", Set.of(BackendMessageAction.COMMAND), List.of("sls\nstop")));
  }

  @Test
  void enabledConfigurationRequiresSourcesAndRejectsDuplicateTrustSelectors() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new BackendMessagingConfig(true, false, 10, 10, List.of()));

    BackendMessageSourceConfig first =
        source("first", "lobby", "", Set.of(BackendMessageAction.MATCHMAKE), List.of());
    BackendMessageSourceConfig second =
        source("second", "lobby", "", Set.of(BackendMessageAction.MATCHMAKE), List.of());
    assertThrows(
        IllegalArgumentException.class,
        () -> new BackendMessagingConfig(true, false, 10, 10, List.of(first, second)));
  }

  @Test
  void configurationEnforcesRateAndSourceBounds() {
    assertThrows(
        IllegalArgumentException.class,
        () -> new BackendMessagingConfig(false, false, 0, 10, List.of()));
    assertThrows(
        IllegalArgumentException.class,
        () -> new BackendMessagingConfig(false, false, 10, 61, List.of()));

    BackendMessageSourceConfig source =
        source("source", "lobby", "", Set.of(BackendMessageAction.MATCHMAKE), List.of());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BackendMessagingConfig(
                false,
                false,
                10,
                10,
                Collections.nCopies(BackendMessagingConfig.MAX_SOURCES + 1, source)));
  }

  private static BackendMessageSourceConfig source(
      String id,
      String server,
      String blueprint,
      Set<BackendMessageAction> actions,
      List<String> commandRoots) {
    return new BackendMessageSourceConfig(id, server, blueprint, actions, commandRoots);
  }
}
