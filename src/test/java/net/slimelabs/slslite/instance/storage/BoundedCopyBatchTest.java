package net.slimelabs.slslite.instance.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class BoundedCopyBatchTest {

  @Test
  void neverExceedsParallelismAndCompletesEveryTask() throws Exception {
    AtomicInteger active = new AtomicInteger();
    AtomicInteger maximum = new AtomicInteger();
    AtomicInteger completed = new AtomicInteger();

    try (BoundedCopyBatch batch = new BoundedCopyBatch(3, 6)) {
      for (int index = 0; index < 24; index++) {
        batch.submit(
            () -> {
              int current = active.incrementAndGet();
              maximum.accumulateAndGet(current, Math::max);
              try {
                Thread.sleep(5);
                completed.incrementAndGet();
              } finally {
                active.decrementAndGet();
              }
            });
      }
      batch.complete();
    }

    assertEquals(24, completed.get());
    assertTrue(maximum.get() <= 3);
  }

  @Test
  void propagatesFirstFailureAndSkipsQueuedWork() throws Exception {
    AtomicInteger executed = new AtomicInteger();

    IOException failure =
        assertThrows(
            IOException.class,
            () -> {
              try (BoundedCopyBatch batch = new BoundedCopyBatch(1, 4)) {
                batch.submit(
                    () -> {
                      throw new IOException("first failure");
                    });
                for (int index = 0; index < 3; index++) {
                  batch.submit(executed::incrementAndGet);
                }
                batch.complete();
              }
            });

    assertEquals("first failure", failure.getMessage());
    assertEquals(0, executed.get());
  }

  @Test
  void abortWaitsForRunningTaskBeforeCleanupCanContinue() throws Exception {
    CountDownLatch running = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicBoolean closeReturned = new AtomicBoolean();
    BoundedCopyBatch batch = new BoundedCopyBatch(1, 2);
    batch.submit(
        () -> {
          running.countDown();
          assertTrue(release.await(5, TimeUnit.SECONDS));
        });
    assertTrue(running.await(5, TimeUnit.SECONDS));

    Thread closer =
        Thread.ofPlatform()
            .start(
                () -> {
                  assertThrows(IOException.class, batch::close);
                  closeReturned.set(true);
                });
    Thread.sleep(50);
    assertFalse(closeReturned.get());

    release.countDown();
    closer.join(5_000);
    assertFalse(closer.isAlive());
    assertTrue(closeReturned.get());
  }

  @Test
  void interruptionStillDrainsRunningTaskAndRestoresInterrupt() throws Exception {
    CountDownLatch running = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicBoolean interruptedAfterClose = new AtomicBoolean();
    BoundedCopyBatch batch = new BoundedCopyBatch(1, 2);
    batch.submit(
        () -> {
          running.countDown();
          release.await(5, TimeUnit.SECONDS);
        });
    assertTrue(running.await(5, TimeUnit.SECONDS));

    Thread closer =
        Thread.ofPlatform()
            .start(
                () -> {
                  Thread.currentThread().interrupt();
                  assertThrows(IOException.class, batch::close);
                  interruptedAfterClose.set(Thread.currentThread().isInterrupted());
                });
    Thread.sleep(50);
    release.countDown();
    closer.join(5_000);

    assertFalse(closer.isAlive());
    assertTrue(interruptedAfterClose.get());
  }
}
