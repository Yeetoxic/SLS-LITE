package net.slimelabs.slslite.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import net.slimelabs.slslite.config.DetailLogLevel;
import net.slimelabs.slslite.config.DetailedLoggingConfig;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

final class SLSDetailLogTest {

  @TempDir Path temporaryDirectory;

  @Test
  void flushesRedactedCorrelatedRecordsOnClose() throws Exception {
    Path data = Files.createDirectories(temporaryDirectory.resolve("data"));
    try (SLSDetailLog log =
        new SLSDetailLog(
            data,
            temporaryDirectory,
            new DetailedLoggingConfig(DetailLogLevel.DETAILED, false, 64, 2, 128, true),
            LoggerFactory.getLogger(getClass()))) {
      log.normal(
          "inst-abc",
          "lifecycle",
          "path={} raw={} authorization=Bearer {}",
          data.resolve("instances/one"),
          "sls_live_supersecret",
          "credential");
    }

    String contents = Files.readString(data.resolve(SLSDetailLog.RELATIVE_PATH));
    assertTrue(contents.contains("correlation=inst-abc"));
    assertTrue(contents.contains("path=<data>"));
    assertTrue(contents.contains("<redacted-key>"));
    assertTrue(contents.contains("authorization=Bearer <redacted>"));
    assertFalse(contents.contains("supersecret"));
    assertFalse(contents.contains("credential"));
  }

  @Test
  void rotatesWithinConfiguredRetentionAndBoundsIndividualRecords() throws Exception {
    Path data = Files.createDirectories(temporaryDirectory.resolve("data"));
    try (SLSDetailLog log =
        new SLSDetailLog(
            data,
            temporaryDirectory,
            new DetailedLoggingConfig(DetailLogLevel.DETAILED, false, 64, 3, 512, false),
            LoggerFactory.getLogger(getClass()))) {
      String payload = "x".repeat(20_000);
      for (int index = 0; index < 20; index++) {
        log.detailed("inst-rotate", "test", "{}-{}", index, payload);
      }
    }

    Path active = data.resolve(SLSDetailLog.RELATIVE_PATH);
    assertTrue(Files.isRegularFile(active));
    assertTrue(Files.isRegularFile(active.resolveSibling(active.getFileName() + ".1")));
    assertTrue(Files.isRegularFile(active.resolveSibling(active.getFileName() + ".2")));
    assertFalse(Files.exists(active.resolveSibling(active.getFileName() + ".3")));
    try (var files = Files.list(active.getParent())) {
      assertEquals(
          3,
          files
              .filter(path -> path.getFileName().toString().startsWith("sls-lite-detail.log"))
              .count());
    }
    assertTrue(Files.size(active) <= 64L * 1024L);
  }

  @Test
  void refusesSymlinkedLogDirectory() throws Exception {
    Path data = Files.createDirectories(temporaryDirectory.resolve("data"));
    Path outside = Files.createDirectories(temporaryDirectory.resolve("outside"));
    try {
      Files.createSymbolicLink(data.resolve("logs"), outside);
    } catch (UnsupportedOperationException | java.io.IOException exception) {
      return;
    }

    assertThrows(
        java.io.IOException.class,
        () ->
            new SLSDetailLog(
                data,
                temporaryDirectory,
                DetailedLoggingConfig.defaults(),
                LoggerFactory.getLogger(getClass())));
    try (var files = Files.list(outside)) {
      assertEquals(0, files.count());
    }
  }
}
