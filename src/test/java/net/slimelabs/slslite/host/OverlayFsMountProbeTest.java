package net.slimelabs.slslite.host;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class OverlayFsMountProbeTest {

  @TempDir Path temporaryDirectory;

  @Test
  void provesIsolationPersistenceUnmountAndCleanup() throws Exception {
    OverlayFsMountProbe probe = new OverlayFsMountProbe(new SimulatedMountOperations(false, false));

    OverlayFsMountProbe.Result result = probe.probe(temporaryDirectory);

    assertTrue(result.supported(), result.detail());
    assertFalse(hasProbeDirectory());
  }

  @Test
  void rejectsAMountThatModifiesTheLowerSource() {
    OverlayFsMountProbe probe = new OverlayFsMountProbe(new SimulatedMountOperations(true, false));

    OverlayFsMountProbe.Result result = probe.probe(temporaryDirectory);

    assertFalse(result.supported());
    assertTrue(result.detail().contains("modified the lower source"));
    assertFalse(hasProbeDirectory());
  }

  @Test
  void preservesProbeDirectoryWhenUnmountFails() {
    OverlayFsMountProbe probe = new OverlayFsMountProbe(new SimulatedMountOperations(false, true));

    OverlayFsMountProbe.Result result = probe.probe(temporaryDirectory);

    assertFalse(result.supported());
    assertTrue(result.detail().contains("preserved for safe operator recovery"));
    assertTrue(hasProbeDirectory());
  }

  private boolean hasProbeDirectory() {
    try (var entries = Files.list(temporaryDirectory)) {
      return entries.anyMatch(
          path -> path.getFileName().toString().startsWith(".sls-overlay-probe-"));
    } catch (IOException exception) {
      throw new AssertionError(exception);
    }
  }

  private static final class SimulatedMountOperations
      implements OverlayFsMountProbe.MountOperations {

    private final boolean mutateLower;
    private final boolean failUnmount;
    private Path upper;
    private Path merged;

    private SimulatedMountOperations(boolean mutateLower, boolean failUnmount) {
      this.mutateLower = mutateLower;
      this.failUnmount = failUnmount;
    }

    @Override
    public void mount(Path lower, Path upper, Path work, Path merged) throws IOException {
      this.upper = upper;
      this.merged = merged;
      Files.copy(lower.resolve("marker"), merged.resolve("marker"));
      if (mutateLower) {
        Files.writeString(lower.resolve("marker"), "mutated");
      }
    }

    @Override
    public void unmount(Path merged) throws IOException {
      if (failUnmount) {
        throw new IOException("simulated unmount failure");
      }
      Files.copy(this.merged.resolve("marker"), upper.resolve("marker"));
      Files.copy(this.merged.resolve("instance-only"), upper.resolve("instance-only"));
      Files.delete(this.merged.resolve("marker"));
      Files.delete(this.merged.resolve("instance-only"));
    }
  }
}
