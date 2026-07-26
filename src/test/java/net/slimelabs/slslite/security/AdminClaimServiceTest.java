package net.slimelabs.slslite.security;

import com.velocitypowered.api.proxy.Player;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class AdminClaimServiceTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void validCodeGrantsAdministrationOnlyOnce() throws Exception {
        AdministratorStore store = initializedStore();
        MutableClock clock = new MutableClock();
        AdminClaimService claims = service(store, true, false, clock);
        Player player = player(UUID.randomUUID(), "Yeetoxic");

        assertEquals("ABCD-2345", claims.issueCode());
        assertEquals(
                AdminClaimService.ClaimResult.INVALID,
                claims.claim(player, "wrong-code")
        );
        assertEquals(
                AdminClaimService.ClaimResult.CLAIMED,
                claims.claim(player, "abcd-2345")
        );
        assertTrue(store.contains(player.getUniqueId()));
        assertEquals(
                AdminClaimService.ClaimResult.ALREADY_ADMINISTRATOR,
                claims.claim(player, "ABCD-2345")
        );
    }

    @Test
    void expiredCodeIsInvalidated() throws Exception {
        AdministratorStore store = initializedStore();
        MutableClock clock = new MutableClock();
        AdminClaimService claims = service(store, true, false, clock);
        Player player = player(UUID.randomUUID(), "Admin");
        claims.issueCode();

        clock.advance(Duration.ofMinutes(11));

        assertEquals(
                AdminClaimService.ClaimResult.EXPIRED,
                claims.claim(player, "ABCD-2345")
        );
        assertEquals(
                AdminClaimService.ClaimResult.NO_ACTIVE_CODE,
                claims.claim(player, "ABCD-2345")
        );
    }

    @Test
    void offlineModeRequiresExplicitOverride() throws Exception {
        AdministratorStore store = initializedStore();
        MutableClock clock = new MutableClock();
        AdminClaimService blocked = service(store, false, false, clock);

        assertThrows(
                AdminClaimService.InsecureOfflineModeException.class,
                blocked::issueCode
        );

        AdminClaimService allowed = service(store, false, true, clock);
        Player player = player(UUID.randomUUID(), "LocalAdmin");
        allowed.issueCode();
        assertEquals(
                AdminClaimService.ClaimResult.CLAIMED,
                allowed.claim(player, "ABCD-2345")
        );
    }

    private AdministratorStore initializedStore() throws Exception {
        AdministratorStore store = new AdministratorStore(temporaryDirectory);
        store.initialize();
        return store;
    }

    private static AdminClaimService service(
            AdministratorStore store,
            boolean onlineMode,
            boolean allowOffline,
            Clock clock
    ) {
        return new AdminClaimService(
                store,
                onlineMode,
                allowOffline,
                Duration.ofMinutes(10),
                clock,
                () -> "ABCD2345"
        );
    }

    private static Player player(UUID uniqueId, String username) {
        return (Player) Proxy.newProxyInstance(
                Player.class.getClassLoader(),
                new Class<?>[]{Player.class},
                (proxy, method, arguments) -> switch (method.getName()) {
                    case "getUniqueId" -> uniqueId;
                    case "getUsername" -> username;
                    default -> {
                        if (method.getReturnType() == boolean.class) {
                            yield false;
                        }
                        yield null;
                    }
                }
        );
    }

    private static final class MutableClock extends Clock {
        private Instant instant = Instant.parse("2026-07-25T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }
    }
}
