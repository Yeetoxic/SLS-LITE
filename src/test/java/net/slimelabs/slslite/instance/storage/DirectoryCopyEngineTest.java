package net.slimelabs.slslite.instance.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class DirectoryCopyEngineTest {

  @TempDir Path temporaryDirectory;

  @Test
  void retriesTransientFileCopyWithConfiguredBackoff() throws Exception {
    Path source = temporaryDirectory.resolve("source.txt");
    Path target = temporaryDirectory.resolve("target.txt");
    Files.writeString(source, "value");
    AtomicInteger attempts = new AtomicInteger();
    AtomicLong sleptMillis = new AtomicLong();
    DirectoryCopyEngine engine =
        new DirectoryCopyEngine(
            (file, destination) -> {
              if (attempts.incrementAndGet() < 3) {
                throw new FileSystemException(
                    file.toString(), destination.toString(), "Input/output error");
              }
              Files.copy(file, destination);
            },
            sleptMillis::addAndGet,
            1);

    engine.copyFile(source, target, () -> false);

    assertEquals(3, attempts.get());
    assertEquals(1_000, sleptMillis.get());
    assertEquals("value", Files.readString(target));
  }

  @Test
  void cancellationInterruptsRetryBackoff() throws Exception {
    Path source = temporaryDirectory.resolve("source.txt");
    Path target = temporaryDirectory.resolve("target.txt");
    Files.writeString(source, "value");
    AtomicBoolean cancelled = new AtomicBoolean();
    AtomicInteger attempts = new AtomicInteger();
    AtomicLong sleptMillis = new AtomicLong();
    DirectoryCopyEngine engine =
        new DirectoryCopyEngine(
            (file, destination) -> {
              attempts.incrementAndGet();
              throw new FileSystemException(
                  file.toString(), destination.toString(), "Input/output error");
            },
            milliseconds -> {
              sleptMillis.addAndGet(milliseconds);
              cancelled.set(true);
            },
            1);

    IOException failure =
        assertThrows(IOException.class, () -> engine.copyFile(source, target, cancelled::get));

    assertTrue(failure.getMessage().contains("cancelled"));
    assertEquals(1, attempts.get());
    assertEquals(100, sleptMillis.get());
  }

  @Test
  void mergeDirectoryKeepsExistingEntriesAndAddsMissingOnes() throws Exception {
    Path source = temporaryDirectory.resolve("source");
    Path destination = temporaryDirectory.resolve("destination");
    Files.createDirectories(source.resolve("nested"));
    Files.createDirectories(destination.resolve("nested"));
    Files.writeString(source.resolve("nested/existing.txt"), "source");
    Files.writeString(source.resolve("nested/new.txt"), "new");
    Files.writeString(destination.resolve("nested/existing.txt"), "destination");
    DirectoryCopyEngine engine = new DirectoryCopyEngine(Files::copy, Thread::sleep, 1);

    engine.mergeDirectoryFirstWins(source, destination, () -> false);

    assertEquals("destination", Files.readString(destination.resolve("nested/existing.txt")));
    assertEquals("new", Files.readString(destination.resolve("nested/new.txt")));
  }
}
