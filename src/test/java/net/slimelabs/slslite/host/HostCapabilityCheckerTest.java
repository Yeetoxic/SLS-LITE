package net.slimelabs.slslite.host;

import net.slimelabs.slslite.network.LoopbackPortAllocator;
import net.slimelabs.slslite.process.JavaJarProcessSpecFactory;
import net.slimelabs.slslite.software.SoftwareProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HostCapabilityCheckerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void verifiesWritableStorageLoopbackAndChildJava() {
        LoopbackPortAllocator ports = new LoopbackPortAllocator(31070, 31170);

        HostCapabilityReport report = new HostCapabilityChecker().check(
                temporaryDirectory.resolve("instances"),
                ports,
                List.of(profile(javaExecutable())),
                new JavaJarProcessSpecFactory(temporaryDirectory),
                1024
        );

        assertFalse(report.hasFailures(), report.failureSummary());
        assertTrue(ports.reservations().isEmpty());
        assertTrue(report.capabilities().stream().anyMatch(capability ->
                capability.name().equals("Child Java process")
                        && capability.status() == HostCapabilityStatus.PASS));
    }

    @Test
    void reportsAnUnlaunchableJavaRuntime() {
        HostCapabilityReport report = new HostCapabilityChecker().check(
                temporaryDirectory.resolve("instances"),
                new LoopbackPortAllocator(31171, 31270),
                List.of(profile("definitely-not-a-java-runtime")),
                new JavaJarProcessSpecFactory(temporaryDirectory),
                1024
        );

        assertTrue(report.hasFailures());
        assertTrue(report.failureSummary().contains("Child Java process"));
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
                10
        );
    }

    private static String javaExecutable() {
        String executable = System.getProperty("os.name").toLowerCase().contains("win")
                ? "java.exe"
                : "java";
        return Path.of(
                System.getProperty("java.home"),
                "bin",
                executable
        ).toString();
    }
}
