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
}
