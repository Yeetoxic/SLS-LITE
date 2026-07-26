package net.slimelabs.slslite.lobby;

import com.velocitypowered.api.proxy.ProxyServer;
import net.slimelabs.slslite.config.ForwardingConfig;
import net.slimelabs.slslite.config.ForwardingMode;
import net.slimelabs.slslite.config.SLSLimboConfig;
import net.slimelabs.slslite.network.LoopbackPortAllocator;
import net.slimelabs.slslite.process.ProcessSupervisor;
import net.slimelabs.slslite.resource.ResourceBudget;
import net.slimelabs.slslite.velocity.BackendRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.Executors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SLSLimboProviderTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void releasesReservationsWhenRecoveryIsExhaustedBeforeLaunch() throws Exception {
        ResourceBudget budget = new ResourceBudget(256);
        int port = findAvailablePort();
        LoopbackPortAllocator ports = new LoopbackPortAllocator(port, port);
        ProcessSupervisor processes = new ProcessSupervisor(1);
        SLSLimboProvider provider = new SLSLimboProvider(
                proxy(),
                new SLSLimboConfig(true, 96, 5, -1, 0, 1, 1, 1),
                new ForwardingConfig(
                        ForwardingMode.MODERN,
                        true,
                        temporaryDirectory.resolve("missing-forwarding.secret")
                ),
                temporaryDirectory,
                budget,
                ports,
                processes,
                backends(),
                LoggerFactory.getLogger(SLSLimboProviderTest.class),
                Executors.newSingleThreadScheduledExecutor()
        );

        try {
            provider.start();

            assertEquals(LobbyStatus.OFFLINE, provider.status());
            assertEquals(0, budget.reservedMemoryMiB());
            assertTrue(ports.reservations().isEmpty());
            assertTrue(provider.limboDiagnostics().orElseThrow().port().isEmpty());
        } finally {
            provider.close();
            processes.shutdown(Duration.ofSeconds(1));
        }
    }

    private static ProxyServer proxy() {
        return (ProxyServer) Proxy.newProxyInstance(
                ProxyServer.class.getClassLoader(),
                new Class<?>[]{ProxyServer.class},
                (proxy, method, arguments) -> null
        );
    }

    private static BackendRegistry backends() {
        return new BackendRegistry() {
            @Override
            public void register(String name, InetSocketAddress address) {
            }

            @Override
            public void unregister(String name) {
            }
        };
    }

    private static int findAvailablePort() throws Exception {
        try (java.net.ServerSocket socket = new java.net.ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }
}
