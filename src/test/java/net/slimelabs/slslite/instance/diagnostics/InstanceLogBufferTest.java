package net.slimelabs.slslite.instance.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.Test;

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

    assertEquals(java.util.List.of("line-4", "line-5"), buffer.page(1, 2).lines());
    assertEquals(java.util.List.of("line-2", "line-3"), buffer.page(2, 2).lines());
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

  @Test
  void cursorCaptureReturnsOnlyNewOutputInOrder() {
    InstanceLogBuffer buffer = new InstanceLogBuffer();
    buffer.append("old");
    long cursor = buffer.cursor();
    buffer.append("first");
    buffer.append("second");

    InstanceOutputBatch batch = buffer.awaitAfter(cursor, 8, Duration.ZERO, Duration.ofMillis(100));

    assertEquals(List.of("first", "second"), batch.lines());
    assertEquals(0, batch.droppedLines());
    assertEquals(buffer.cursor(), batch.cursor());
  }

  @Test
  void cursorCaptureWaitsOffThreadForNewOutput() throws Exception {
    InstanceLogBuffer buffer = new InstanceLogBuffer();
    long cursor = buffer.cursor();
    Thread writer =
        Thread.ofVirtual()
            .start(
                () -> {
                  try {
                    Thread.sleep(20);
                  } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                  }
                  buffer.append("response");
                });

    InstanceOutputBatch batch =
        buffer.awaitAfter(cursor, 8, Duration.ofMillis(10), Duration.ofSeconds(1));
    writer.join();

    assertEquals(List.of("response"), batch.lines());
  }

  @Test
  void cursorCaptureReportsRetentionLossAndCapsEachBatch() {
    InstanceLogBuffer buffer = new InstanceLogBuffer();
    for (int index = 0; index < InstanceLogBuffer.CAPACITY + 5; index++) {
      buffer.append("line-" + index);
    }

    InstanceOutputBatch batch = buffer.awaitAfter(0, 3, Duration.ZERO, Duration.ofMillis(100));

    assertEquals(5, batch.droppedLines());
    assertEquals(List.of("line-5", "line-6", "line-7"), batch.lines());
    assertTrue(batch.cursor() < buffer.cursor());
  }
}
