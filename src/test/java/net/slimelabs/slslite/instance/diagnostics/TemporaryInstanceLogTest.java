package net.slimelabs.slslite.instance.diagnostics;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TemporaryInstanceLogTest {

  @TempDir Path temporaryDirectory;

  @Test
  void writesInsideInstanceAndStopsAtConfiguredLimit() throws Exception {
    Path logPath = temporaryDirectory.resolve(TemporaryInstanceLog.RELATIVE_PATH);
    try (TemporaryInstanceLog log = new TemporaryInstanceLog(temporaryDirectory, 1)) {
      for (int index = 0; index < 100; index++) {
        log.append("line-" + index + "-" + "x".repeat(80));
      }
      assertTrue(log.capped());
      assertTrue(log.writtenBytes() <= 1024);
    }

    assertTrue(Files.isRegularFile(logPath));
    assertTrue(Files.size(logPath) <= 1024);
    assertTrue(Files.readString(logPath).contains("line-0"));
  }
}
