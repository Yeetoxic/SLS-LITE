package net.slimelabs.slslite.instance;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstanceLogBufferTest {

    @Test
    void retainsOnlyTheNewestBoundedLines() {
        InstanceLogBuffer buffer = new InstanceLogBuffer();
        for (int index = 0; index <= InstanceLogBuffer.CAPACITY; index++) {
            buffer.append("line-" + index);
        }

        InstanceLogPage page = buffer.page(1, InstanceLogBuffer.CAPACITY);

        assertEquals(InstanceLogBuffer.CAPACITY, page.totalRetainedLines());
        assertEquals("line-1", page.lines().getFirst());
        assertEquals("line-" + InstanceLogBuffer.CAPACITY, page.lines().getLast());
    }

    @Test
    void pagesFromNewestToOldestWhilePreservingLineOrder() {
        InstanceLogBuffer buffer = new InstanceLogBuffer();
        for (int index = 1; index <= 5; index++) {
            buffer.append("line-" + index);
        }

        assertEquals(
                java.util.List.of("line-4", "line-5"),
                buffer.page(1, 2).lines()
        );
        assertEquals(
                java.util.List.of("line-2", "line-3"),
                buffer.page(2, 2).lines()
        );
        assertEquals(java.util.List.of("line-1"), buffer.page(3, 2).lines());
        assertTrue(buffer.page(4, 2).lines().isEmpty());
    }

    @Test
    void truncatesPathologicalLinesAndRejectsInvalidPagination() {
        InstanceLogBuffer buffer = new InstanceLogBuffer();
        buffer.append("x".repeat(InstanceLogBuffer.MAX_LINE_LENGTH + 1));

        assertTrue(buffer.page(1, 1).lines().getFirst().endsWith("[truncated]"));
        assertTrue(buffer.page(Integer.MAX_VALUE, Integer.MAX_VALUE).lines().isEmpty());
        assertThrows(IllegalArgumentException.class, () -> buffer.page(0, 1));
        assertThrows(IllegalArgumentException.class, () -> buffer.page(1, 0));
    }
}
