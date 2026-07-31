package net.slimelabs.slslite.lobby;

import net.slimelabs.slslite.config.LobbyConfig;
import net.slimelabs.slslite.config.LobbyMode;
import net.slimelabs.slslite.config.SLSLimboConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class LobbyRecoveryPolicyTest {

    @Test
    void appliesBoundedExponentialBackoffFromLobbyConfig() {
        LobbyRecoveryPolicy policy = LobbyRecoveryPolicy.from(new LobbyConfig(
                LobbyMode.MANAGED,
                "vanilla",
                "lobby",
                4,
                3,
                10,
                45
        ));

        assertFalse(policy.exhausted(3));
        assertTrue(policy.exhausted(4));
        assertEquals(4, policy.maxAttempts());
        assertEquals(3, policy.backoffSeconds(1));
        assertEquals(6, policy.backoffSeconds(2));
        assertEquals(10, policy.backoffSeconds(3));
        assertEquals(10, policy.backoffSeconds(4));
        assertEquals(45, policy.stableAfterSeconds());
    }

    @Test
    void appliesTheSamePolicyToLimboConfig() {
        LobbyRecoveryPolicy policy = LobbyRecoveryPolicy.from(
                new SLSLimboConfig(true, 64, 30, -1, 2, 5, 20, 60)
        );

        assertFalse(policy.exhausted(1));
        assertTrue(policy.exhausted(2));
        assertEquals(5, policy.backoffSeconds(1));
        assertEquals(10, policy.backoffSeconds(2));
        assertEquals(20, policy.backoffSeconds(3));
        assertEquals(60, policy.stableAfterSeconds());
    }
}
