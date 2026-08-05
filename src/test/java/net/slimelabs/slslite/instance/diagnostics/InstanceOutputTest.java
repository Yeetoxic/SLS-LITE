package net.slimelabs.slslite.instance.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class InstanceOutputTest {

  @TempDir Path temporaryDirectory;

  @Test
  void failureCanBeConsumedByOnlyOneConcurrentCaller() throws Exception {
    InstanceOutput output = new InstanceOutput(temporaryDirectory);
    output.recordFailure(new IOException("fixture"));
    CountDownLatch start = new CountDownLatch(1);
    AtomicInteger consumers = new AtomicInteger();
    List<Thread> threads = new ArrayList<>();
    for (int index = 0; index < 16; index++) {
      Thread thread =
          new Thread(
              () -> {
                try {
                  start.await();
                  if (output.takeFailure().isPresent()) {
                    consumers.incrementAndGet();
                  }
                } catch (InterruptedException exception) {
                  Thread.currentThread().interrupt();
                }
              });
      threads.add(thread);
      thread.start();
    }

    start.countDown();
    for (Thread thread : threads) {
      thread.join();
    }

    assertEquals(1, consumers.get());
  }

  @Test
  void zeroConsoleTailRetentionStillAdvancesOutputCursor() {
    InstanceOutput output = new InstanceOutput(temporaryDirectory, 0);

    output.append("not retained");

    assertEquals(0, output.retainedLines());
    assertEquals(0, output.retentionCapacity());
    assertEquals(1, output.cursor());
  }

  @Test
  void customConsoleTailRetentionEvictsOldestLines() {
    InstanceOutput output = new InstanceOutput(temporaryDirectory, 2);
    output.append("one");
    output.append("two");
    output.append("three");

    assertEquals(List.of("two", "three"), output.page(1, 10).lines());
    assertEquals(2, output.retentionCapacity());
  }
}
