package net.slimelabs.slslite.host;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CgroupMemoryDetectorTest {

  @TempDir Path temporaryDirectory;

  @Test
  void detectsFiniteV2LimitAndUsage() throws Exception {
    Path proc = write("proc", "0::/fixture\n");
    Path group = Files.createDirectories(temporaryDirectory.resolve("cgroup/fixture"));
    Files.writeString(group.resolve("memory.max"), "1073741824\n");
    Files.writeString(group.resolve("memory.current"), "268435456\n");

    var result =
        new CgroupMemoryDetector(proc, temporaryDirectory.resolve("cgroup")).detect().orElseThrow();

    assertEquals("cgroup v2", result.source());
    assertEquals(1073741824L, result.limitBytes());
    assertEquals(268435456L, result.currentBytes());
    assertEquals(805306368L, result.availableBytes());
  }

  @Test
  void processV2MembershipWinsOverUnboundedRoot() throws Exception {
    Path proc = write("proc", "0::/fixture\n");
    Path root = Files.createDirectories(temporaryDirectory.resolve("cgroup"));
    Files.writeString(root.resolve("memory.max"), "max\n");
    Path group = Files.createDirectories(root.resolve("fixture"));
    Files.writeString(group.resolve("memory.max"), "1073741824\n");
    Files.writeString(group.resolve("memory.current"), "268435456\n");

    var result = new CgroupMemoryDetector(proc, root).detect().orElseThrow();

    assertEquals(1073741824L, result.limitBytes());
    assertEquals(268435456L, result.currentBytes());
  }

  @Test
  void detectsFiniteV1Limit() throws Exception {
    Path proc = write("proc", "5:cpu,memory:/panel/server\n");
    Path group = Files.createDirectories(temporaryDirectory.resolve("cgroup/memory/panel/server"));
    Files.writeString(group.resolve("memory.limit_in_bytes"), "2147483648\n");
    Files.writeString(group.resolve("memory.usage_in_bytes"), "536870912\n");

    var result =
        new CgroupMemoryDetector(proc, temporaryDirectory.resolve("cgroup")).detect().orElseThrow();

    assertEquals("cgroup v1", result.source());
    assertEquals(1610612736L, result.availableBytes());
  }

  @Test
  void rejectsUnboundedAndMalformedLimits() throws Exception {
    Path proc = write("proc", "0::/\n");
    Path root = Files.createDirectories(temporaryDirectory.resolve("cgroup"));
    Files.writeString(root.resolve("memory.max"), "max\n");
    assertTrue(new CgroupMemoryDetector(proc, root).detect().isEmpty());

    Files.writeString(root.resolve("memory.max"), "not-a-number\n");
    assertTrue(new CgroupMemoryDetector(proc, root).detect().isEmpty());
  }

  private Path write(String name, String value) throws Exception {
    Path path = temporaryDirectory.resolve(name);
    Files.writeString(path, value);
    return path;
  }
}
