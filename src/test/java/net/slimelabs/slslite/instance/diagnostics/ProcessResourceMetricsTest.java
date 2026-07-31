package net.slimelabs.slslite.instance.diagnostics;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class ProcessResourceMetricsTest {

  @TempDir Path temporaryDirectory;

  @Test
  void readsLinuxResidentMemoryAndProcessIoCounters() throws Exception {
    Path process = Files.createDirectory(temporaryDirectory.resolve("42"));
    Files.writeString(
        process.resolve("status"),
        """
                Name:	java
                VmRSS:	  123456 kB
                Threads:	20
                """);
    Files.writeString(
        process.resolve("io"),
        """
                rchar: 1000
                wchar: 2000
                syscr: 10
                syscw: 20
                read_bytes: 3000
                write_bytes: 4000
                cancelled_write_bytes: 0
                """);

    var snapshot = ProcessResourceMetrics.inspect(42, temporaryDirectory).orElseThrow();

    assertEquals(123456L * 1024L, snapshot.residentBytes().orElseThrow());
    assertEquals(1000L, snapshot.charactersRead().orElseThrow());
    assertEquals(2000L, snapshot.charactersWritten().orElseThrow());
    assertEquals(3000L, snapshot.storageBytesRead().orElseThrow());
    assertEquals(4000L, snapshot.storageBytesWritten().orElseThrow());
  }

  @Test
  void returnsUnavailableForMissingOrMalformedProcessData() throws Exception {
    assertTrue(ProcessResourceMetrics.inspect(42, temporaryDirectory).isEmpty());

    Path process = Files.createDirectory(temporaryDirectory.resolve("43"));
    Files.writeString(process.resolve("status"), "VmRSS: invalid kB\n");
    Files.writeString(process.resolve("io"), "read_bytes: invalid\n");

    assertTrue(ProcessResourceMetrics.inspect(43, temporaryDirectory).isEmpty());
  }

  @Test
  void rejectsInvalidProcessIdentifiers() {
    assertTrue(ProcessResourceMetrics.inspect(0, temporaryDirectory).isEmpty());
    assertTrue(ProcessResourceMetrics.inspect(-1, temporaryDirectory).isEmpty());
  }
}
