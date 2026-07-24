package net.slimelabs.slslite.resource;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ResourceBudgetTest {

    @Test
    void reservesAndReleasesMemory() {
        ResourceBudget budget = new ResourceBudget(4096);

        assertTrue(budget.tryReserve("lobby.123456", 1024));
        assertTrue(budget.tryReserve("game.654321", 2048));
        assertEquals(3072, budget.reservedMemoryMiB());
        assertEquals(1024, budget.availableMemoryMiB());

        assertEquals(1024, budget.release("lobby.123456"));
        assertEquals(2048, budget.availableMemoryMiB());
    }

    @Test
    void rejectsOvercommitWithoutChangingState() {
        ResourceBudget budget = new ResourceBudget(2048);

        assertFalse(budget.tryReserve("game.123456", 3072));
        assertEquals(0, budget.reservedMemoryMiB());
    }

    @Test
    void rejectsDuplicateReservations() {
        ResourceBudget budget = new ResourceBudget(4096);
        budget.tryReserve("game.123456", 1024);

        assertThrows(
                IllegalStateException.class,
                () -> budget.tryReserve("game.123456", 1024)
        );
    }
}
