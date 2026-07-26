package net.slimelabs.slslite.velocity;

import com.viaversion.viaversion.api.platform.ProtocolDetectorService;
import com.velocitypowered.api.proxy.server.RegisteredServer;
import com.velocitypowered.api.proxy.server.ServerPing;
import net.kyori.adventure.text.Component;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import java.lang.reflect.Proxy;
import java.util.OptionalInt;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ViaVersionProtocolSynchronizerTest {

    @Test
    void publishesKnownProtocolWithoutPingingBackend() {
        AtomicReference<String> synchronizedName = new AtomicReference<>();
        AtomicInteger synchronizedProtocol = new AtomicInteger();
        AtomicInteger pings = new AtomicInteger();
        ViaVersionProtocolSynchronizer synchronizer = synchronizer(
                synchronizedName,
                synchronizedProtocol
        );

        synchronizer.synchronize(
                "sls-limbo",
                server(770, pings),
                OptionalInt.of(770)
        );

        assertEquals("sls-limbo", synchronizedName.get());
        assertEquals(770, synchronizedProtocol.get());
        assertEquals(0, pings.get());
    }

    @Test
    void probesDynamicBackendBeforePublishingProtocol() {
        AtomicReference<String> synchronizedName = new AtomicReference<>();
        AtomicInteger synchronizedProtocol = new AtomicInteger();
        AtomicInteger pings = new AtomicInteger();
        ViaVersionProtocolSynchronizer synchronizer = synchronizer(
                synchronizedName,
                synchronizedProtocol
        );

        synchronizer.synchronize(
                "lobby.abc123",
                server(775, pings),
                OptionalInt.empty()
        );

        assertEquals("lobby.abc123", synchronizedName.get());
        assertEquals(775, synchronizedProtocol.get());
        assertEquals(1, pings.get());
    }

    @Test
    void removesProtocolWhenBackendIsUnregistered() {
        AtomicReference<String> removedName = new AtomicReference<>();
        ProtocolDetectorService detector = detector(
                new AtomicReference<>(),
                new AtomicInteger(),
                removedName
        );
        ViaVersionProtocolSynchronizer synchronizer =
                new ViaVersionProtocolSynchronizer(
                        detector,
                        LoggerFactory.getLogger(getClass())
                );

        synchronizer.remove("lobby.old");

        assertEquals("lobby.old", removedName.get());
    }

    private ViaVersionProtocolSynchronizer synchronizer(
            AtomicReference<String> synchronizedName,
            AtomicInteger synchronizedProtocol
    ) {
        return new ViaVersionProtocolSynchronizer(
                detector(
                        synchronizedName,
                        synchronizedProtocol,
                        new AtomicReference<>()
                ),
                LoggerFactory.getLogger(getClass())
        );
    }

    private static ProtocolDetectorService detector(
            AtomicReference<String> synchronizedName,
            AtomicInteger synchronizedProtocol,
            AtomicReference<String> removedName
    ) {
        return (ProtocolDetectorService) Proxy.newProxyInstance(
                ProtocolDetectorService.class.getClassLoader(),
                new Class<?>[]{ProtocolDetectorService.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "setProtocolVersion" -> {
                        synchronizedName.set((String) arguments[0]);
                        synchronizedProtocol.set((int) arguments[1]);
                        yield null;
                    }
                    case "uncacheProtocolVersion" -> {
                        removedName.set((String) arguments[0]);
                        yield -1;
                    }
                    default -> defaultValue(method.getReturnType());
                }
        );
    }

    private static RegisteredServer server(
            int protocol,
            AtomicInteger pings
    ) {
        ServerPing ping = new ServerPing(
                new ServerPing.Version(protocol, "test"),
                null,
                Component.empty(),
                null
        );
        return (RegisteredServer) Proxy.newProxyInstance(
                RegisteredServer.class.getClassLoader(),
                new Class<?>[]{RegisteredServer.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "ping" -> {
                        pings.incrementAndGet();
                        yield CompletableFuture.completedFuture(ping);
                    }
                    default -> defaultValue(method.getReturnType());
                }
        );
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
