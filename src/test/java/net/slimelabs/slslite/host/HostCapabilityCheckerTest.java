package net.slimelabs.slslite.host;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import net.slimelabs.slslite.blueprint.Blueprint;
import net.slimelabs.slslite.config.StorageStrategy;
import net.slimelabs.slslite.network.LoopbackPortAllocator;
import net.slimelabs.slslite.process.JavaJarProcessSpecFactory;
import net.slimelabs.slslite.software.SoftwareProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class HostCapabilityCheckerTest {

  @TempDir Path temporaryDirectory;

  @Test
  void verifiesWritableStorageLoopbackAndChildJava() {
    LoopbackPortAllocator ports = new LoopbackPortAllocator(31070, 31170);

    HostCapabilityReport report =
        new HostCapabilityChecker()
            .check(
                temporaryDirectory.resolve("instances"),
                ports,
                List.of(blueprint("1.21.1")),
                List.of(profile(javaExecutable())),
                new JavaJarProcessSpecFactory(temporaryDirectory),
                1024);

    assertFalse(report.hasFailures(), report.failureSummary());
    assertTrue(
        java.util.Set.of(
                StorageStrategy.COPY,
                StorageStrategy.REFLINK,
                StorageStrategy.BTRFS,
                StorageStrategy.OVERLAY,
                StorageStrategy.FUSE_OVERLAY)
            .contains(report.selectedStorageStrategy().orElseThrow()));
    assertTrue(ports.reservations().isEmpty());
    assertTrue(
        report.capabilities().stream()
            .anyMatch(
                capability ->
                    capability.name().equals("Child Java process")
                        && capability.status() == HostCapabilityStatus.PASS));
    assertTrue(
        report.capabilities().stream()
            .anyMatch(
                capability ->
                    capability.name().equals("Selected COW strategy")
                        && capability.detail().startsWith("requested=auto, selected=")));
    assertTrue(
        report.capabilities().stream()
            .anyMatch(capability -> capability.name().equals("Process identity")));
    assertTrue(
        report.capabilities().stream()
            .anyMatch(capability -> capability.name().equals("Container memory")));
  }

  @Test
  void reportsAnUnlaunchableJavaRuntime() {
    HostCapabilityReport report =
        new HostCapabilityChecker()
            .check(
                temporaryDirectory.resolve("instances"),
                new LoopbackPortAllocator(31171, 31270),
                List.of(blueprint("1.21.1")),
                List.of(profile("definitely-not-a-java-runtime")),
                new JavaJarProcessSpecFactory(temporaryDirectory),
                1024);

    assertTrue(report.hasFailures());
    assertTrue(report.failureSummary().contains("Child Java process"));
  }

  @Test
  void warnsForUnavailableOptionalVersionSpecificRuntime() {
    SoftwareProfile base = profile(javaExecutable());
    SoftwareProfile profile =
        new SoftwareProfile(
            base.id(),
            base.name(),
            base.runtime(),
            base.configurator(),
            base.source(),
            base.channel(),
            base.acceptEula(),
            base.javaExecutable(),
            Map.of(21, "definitely-not-java-21"),
            base.baseDirectory(),
            base.serverJar(),
            base.jvmArguments(),
            base.serverArguments(),
            base.serverProperties(),
            base.readinessPattern(),
            base.startupTimeoutSeconds(),
            base.stopCommand(),
            base.stopTimeoutSeconds());

    HostCapabilityReport report =
        new HostCapabilityChecker()
            .check(
                temporaryDirectory.resolve("instances"),
                new LoopbackPortAllocator(31271, 31370),
                List.of(blueprint("1.18.2")),
                List.of(profile),
                new JavaJarProcessSpecFactory(temporaryDirectory),
                1024);

    assertFalse(report.hasFailures(), report.failureSummary());
    assertTrue(
        report.capabilities().stream()
            .anyMatch(
                capability ->
                    capability.status() == HostCapabilityStatus.INFO
                        && capability.name().equals("Optional Java runtimes")
                        && capability.detail().contains("definitely-not-java-21")
                        && !capability.detail().contains("Cannot run program")));
  }

  @Test
  void missingUnusedDefaultDoesNotFailWhenSelectedRuntimeIsAvailable() {
    SoftwareProfile profile =
        withJavaVersions(profile("definitely-not-default-java"), Map.of(17, javaExecutable()));

    HostCapabilityReport report =
        new HostCapabilityChecker()
            .check(
                temporaryDirectory.resolve("instances"),
                new LoopbackPortAllocator(31371, 31470),
                List.of(blueprint("1.18.2")),
                List.of(profile),
                new JavaJarProcessSpecFactory(temporaryDirectory),
                1024);

    assertFalse(report.hasFailures(), report.failureSummary());
    assertTrue(
        report.capabilities().stream()
            .anyMatch(
                capability ->
                    capability.status() == HostCapabilityStatus.INFO
                        && capability.name().equals("Optional Java runtimes")
                        && capability.detail().contains("definitely-not-default-java")));
  }

  @Test
  void missingSelectedRuntimeFailsEvenWhenDefaultIsAvailable() {
    SoftwareProfile profile =
        withJavaVersions(profile(javaExecutable()), Map.of(17, "definitely-not-java-17"));

    HostCapabilityReport report =
        new HostCapabilityChecker()
            .check(
                temporaryDirectory.resolve("instances"),
                new LoopbackPortAllocator(31471, 31570),
                List.of(blueprint("1.18.2")),
                List.of(profile),
                new JavaJarProcessSpecFactory(temporaryDirectory),
                1024);

    assertTrue(report.hasFailures());
    assertTrue(report.failureSummary().contains("definitely-not-java-17"));
  }

  @Test
  void fullyAvailableSelectedRuntimePassesWithoutWarnings() {
    SoftwareProfile profile =
        withJavaVersions(profile(javaExecutable()), Map.of(17, javaExecutable()));

    HostCapabilityReport report =
        new HostCapabilityChecker()
            .check(
                temporaryDirectory.resolve("instances"),
                new LoopbackPortAllocator(31571, 31670),
                List.of(blueprint("1.18.2")),
                List.of(profile),
                new JavaJarProcessSpecFactory(temporaryDirectory),
                1024);

    assertFalse(report.hasFailures(), report.failureSummary());
    assertFalse(
        report.capabilities().stream()
            .anyMatch(
                capability ->
                    capability.status() == HostCapabilityStatus.INFO
                        && capability.name().equals("Optional Java runtimes")));
  }

  private static SoftwareProfile withJavaVersions(
      SoftwareProfile base, Map<Integer, String> javaVersions) {
    return new SoftwareProfile(
        base.id(),
        base.name(),
        base.runtime(),
        base.configurator(),
        base.source(),
        base.channel(),
        base.acceptEula(),
        base.javaExecutable(),
        javaVersions,
        base.baseDirectory(),
        base.serverJar(),
        base.jvmArguments(),
        base.serverArguments(),
        base.serverProperties(),
        base.readinessPattern(),
        base.startupTimeoutSeconds(),
        base.stopCommand(),
        base.stopTimeoutSeconds());
  }

  private static Blueprint blueprint(String version) {
    return new Blueprint("fixture", "Fixture", "test", "paper", version, 1024, false, Map.of());
  }

  private static SoftwareProfile profile(String javaExecutable) {
    return new SoftwareProfile(
        "paper",
        javaExecutable,
        "software/paper/{version}",
        "paper.jar",
        List.of(),
        List.of(),
        "Done",
        30,
        "stop",
        10);
  }

  private static String javaExecutable() {
    String executable =
        System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
    return Path.of(System.getProperty("java.home"), "bin", executable).toString();
  }
}
