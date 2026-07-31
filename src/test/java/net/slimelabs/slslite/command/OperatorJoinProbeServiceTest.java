package net.slimelabs.slslite.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import java.lang.reflect.Proxy;
import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;

final class OperatorJoinProbeServiceTest {

  @Test
  void successfulProbeReportsBackendVersionProtocolAndLatency() {
    OperatorJoinProbeService probes = new OperatorJoinProbeService(Duration.ofSeconds(1), 2);
    ServerPing ping =
        new ServerPing(new ServerPing.Version(758, "1.18.2"), null, Component.empty(), null);

    OperatorJoinProbeService.Result result =
        probes.probe("lobby.abcdef", server(CompletableFuture.completedFuture(ping))).join();

    assertEquals(OperatorJoinProbeService.Status.SUCCESS, result.status());
    assertEquals("1.18.2", result.versionName());
    assertEquals(758, result.protocol());
    assertTrue(result.elapsedMillis() >= 0);
    assertEquals(0, probes.activeCount());
    probes.close();
  }

  @Test
  void probeTimeoutIsBoundedAndDoesNotLeaveAnActiveReservation() {
    OperatorJoinProbeService probes = new OperatorJoinProbeService(Duration.ofMillis(20), 1);

    OperatorJoinProbeService.Result result =
        probes.probe("server.timeout", server(new CompletableFuture<>())).join();

    assertEquals(OperatorJoinProbeService.Status.FAILED, result.status());
    assertTrue(result.detail().contains("Timeout"));
    assertEquals(0, probes.activeCount());
    probes.close();
  }

  @Test
  void duplicateAndGlobalConcurrencyAreRejected() {
    OperatorJoinProbeService probes = new OperatorJoinProbeService(Duration.ofSeconds(1), 2);
    CompletableFuture<ServerPing> firstPending = new CompletableFuture<>();
    CompletableFuture<ServerPing> secondPending = new CompletableFuture<>();
    CompletableFuture<OperatorJoinProbeService.Result> first =
        probes.probe("server.first", server(firstPending));

    OperatorJoinProbeService.Result duplicate =
        probes.probe("server.first", server(new CompletableFuture<>())).join();
    CompletableFuture<OperatorJoinProbeService.Result> second =
        probes.probe("server.second", server(secondPending));
    OperatorJoinProbeService.Result saturated =
        probes.probe("server.third", server(new CompletableFuture<>())).join();

    assertEquals(OperatorJoinProbeService.Status.REJECTED, duplicate.status());
    assertTrue(duplicate.detail().contains("already running"));
    assertEquals(OperatorJoinProbeService.Status.REJECTED, saturated.status());
    assertTrue(saturated.detail().contains("Too many"));
    firstPending.complete(
        new ServerPing(new ServerPing.Version(770, "1.21.5"), null, Component.empty(), null));
    secondPending.complete(
        new ServerPing(new ServerPing.Version(770, "1.21.5"), null, Component.empty(), null));
    assertEquals(OperatorJoinProbeService.Status.SUCCESS, first.join().status());
    assertEquals(OperatorJoinProbeService.Status.SUCCESS, second.join().status());
    probes.close();
  }

  @Test
  void closeRejectsNewProbesAndCancelsOutstandingWork() throws Exception {
    OperatorJoinProbeService probes = new OperatorJoinProbeService(Duration.ofSeconds(1), 1);
    CompletableFuture<OperatorJoinProbeService.Result> active =
        probes.probe("server.first", server(new CompletableFuture<>()));

    probes.close();

    assertTrue(active.isCancelled());
    assertEquals(
        OperatorJoinProbeService.Status.REJECTED,
        probes
            .probe("server.second", server(new CompletableFuture<>()))
            .get(1, TimeUnit.SECONDS)
            .status());
  }

  private static RegisteredServer server(CompletableFuture<ServerPing> ping) {
    return (RegisteredServer)
        Proxy.newProxyInstance(
            RegisteredServer.class.getClassLoader(),
            new Class<?>[] {RegisteredServer.class},
            (proxy, method, arguments) ->
                "ping".equals(method.getName()) ? ping : defaultValue(method.getReturnType()));
  }

  private static Object defaultValue(Class<?> type) {
    if (!type.isPrimitive()) {
      return null;
    }
    if (type == boolean.class) {
      return false;
    }
    if (type == int.class || type == short.class || type == byte.class) {
      return 0;
    }
    if (type == long.class) {
      return 0L;
    }
    return null;
  }
}
