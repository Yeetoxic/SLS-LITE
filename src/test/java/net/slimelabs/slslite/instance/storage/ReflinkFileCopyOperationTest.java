package net.slimelabs.slslite.instance.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ReflinkFileCopyOperationTest {

  @TempDir Path temporaryDirectory;

  @Test
  void usesSuccessfulCloneAndKeepsSourceIsolated() throws Exception {
    Path source = writeSource();
    Path target = temporaryDirectory.resolve("target");
    ReflinkFileCopyOperation operation = new ReflinkFileCopyOperation(false, Files::copy);

    operation.copy(source, target);
    Files.writeString(target, "instance");

    assertEquals("source", Files.readString(source));
    assertEquals("instance", Files.readString(target));
  }

  @Test
  void autoFallsBackToPortableCopyAfterCleaningPartialClone() throws Exception {
    Path source = writeSource();
    Path target = temporaryDirectory.resolve("target");
    ReflinkFileCopyOperation operation =
        new ReflinkFileCopyOperation(
            true,
            (ignoredSource, partialTarget) -> {
              Files.writeString(partialTarget, "partial");
              throw new IOException("cross-filesystem clone rejected");
            });

    operation.copy(source, target);

    assertEquals("source", Files.readString(target));
  }

  @Test
  void requiredReflinkFailsAndRemovesPartialClone() throws Exception {
    Path source = writeSource();
    Path target = temporaryDirectory.resolve("target");
    ReflinkFileCopyOperation operation =
        new ReflinkFileCopyOperation(
            false,
            (ignoredSource, partialTarget) -> {
              Files.writeString(partialTarget, "partial");
              throw new IOException("clone rejected");
            });

    IOException exception = assertThrows(IOException.class, () -> operation.copy(source, target));

    assertTrue(exception.getMessage().contains("Required reflink clone failed"));
    assertFalse(Files.exists(target));
  }

  @Test
  void refusesToOverwriteExistingTarget() throws Exception {
    Path source = writeSource();
    Path target = temporaryDirectory.resolve("target");
    Files.writeString(target, "existing");
    ReflinkFileCopyOperation operation = new ReflinkFileCopyOperation(true, Files::copy);

    assertThrows(IOException.class, () -> operation.copy(source, target));
    assertEquals("existing", Files.readString(target));
  }

  private Path writeSource() throws IOException {
    Path source = temporaryDirectory.resolve("source");
    Files.writeString(source, "source");
    return source;
  }
}
