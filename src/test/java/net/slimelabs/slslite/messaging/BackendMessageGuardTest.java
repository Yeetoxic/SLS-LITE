package net.slimelabs.slslite.messaging;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.UUID;
import net.slimelabs.slslite.config.BackendMessagingConfig;
import org.junit.jupiter.api.Test;

class BackendMessageGuardTest {

  @Test
  void boundsRequestsPerSourceAndPlayerWindow() {
    BackendMessageGuard guard =
        new BackendMessageGuard(new BackendMessagingConfig(false, false, 2, 10, List.of()));
    UUID player = UUID.randomUUID();

    assertTrue(guard.allowRate("lobby", player, 1L));
    assertTrue(guard.allowRate("lobby", player, 2L));
    assertFalse(guard.allowRate("lobby", player, 3L));
    assertTrue(guard.allowRate("lobby", player, java.time.Duration.ofSeconds(10).toNanos() + 1L));
  }

  @Test
  void deduplicatesRequestIdsGloballyUntilExpiry() {
    BackendMessageGuard guard =
        new BackendMessageGuard(new BackendMessagingConfig(false, false, 2, 10, List.of()));
    UUID request = UUID.randomUUID();

    assertTrue(guard.firstRequest(request, 1L));
    assertFalse(guard.firstRequest(request, 2L));
    assertTrue(guard.firstRequest(request, java.time.Duration.ofMinutes(2).toNanos() + 1L));
  }
}
