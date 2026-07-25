package net.slimelabs.slslite.command;

import org.junit.jupiter.api.Test;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

class InstanceTargetResolverTest {

    @Test
    void leavesExplicitInstanceIdsUnchanged() {
        InstanceTargetResolver.Resolution result = InstanceTargetResolver.resolve(
                "smoke.abc123",
                false,
                Optional.empty(),
                ignored -> false
        );

        assertEquals("smoke.abc123", result.instanceId());
        assertNull(result.error());
    }

    @Test
    void rejectsThisFromConsole() {
        InstanceTargetResolver.Resolution result = InstanceTargetResolver.resolve(
                "this",
                false,
                Optional.empty(),
                ignored -> false
        );

        assertNull(result.instanceId());
        assertEquals("Console must specify a server id.", result.error());
    }

    @Test
    void rejectsThisWhenPlayerIsNotOnAManagedServer() {
        InstanceTargetResolver.Resolution result = InstanceTargetResolver.resolve(
                "this",
                true,
                Optional.of("external-lobby"),
                ignored -> false
        );

        assertNull(result.instanceId());
        assertEquals("Server external-lobby is not an SLS server", result.error());
    }

    @Test
    void resolvesThisToPlayersManagedBackend() {
        InstanceTargetResolver.Resolution result = InstanceTargetResolver.resolve(
                "THIS",
                true,
                Optional.of("smoke.abc123"),
                id -> id.equals("smoke.abc123")
        );

        assertEquals("smoke.abc123", result.instanceId());
        assertNull(result.error());
    }
}
