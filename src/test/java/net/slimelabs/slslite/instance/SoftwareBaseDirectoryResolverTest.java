package net.slimelabs.slslite.instance;

import net.slimelabs.slslite.process.JavaJarProcessSpecFactory;
import net.slimelabs.slslite.process.ProcessSpecificationException;
import net.slimelabs.slslite.software.SoftwareConfigurator;
import net.slimelabs.slslite.software.SoftwareProfile;
import net.slimelabs.slslite.software.SoftwareReleaseChannel;
import net.slimelabs.slslite.software.SoftwareRuntime;
import net.slimelabs.slslite.software.SoftwareSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SoftwareBaseDirectoryResolverTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void resolvesManagedDirectoryWithoutInstallationService()
            throws Exception {
        SoftwareBaseDirectoryResolver resolver =
                new SoftwareBaseDirectoryResolver(
                        paths(),
                        (SoftwareBaseDirectoryResolver.InstallationRequest) null
                );

        Path resolved = resolver.resolve(
                profile(),
                "1.21.11",
                null,
                () -> false
        );

        assertEquals(
                temporaryDirectory.resolve("software/paper/1.21.11")
                        .toAbsolutePath()
                        .normalize(),
                resolved
        );
    }

    @Test
    void overridePathBypassesInstallation() throws Exception {
        AtomicInteger installationRequests = new AtomicInteger();
        SoftwareBaseDirectoryResolver resolver =
                new SoftwareBaseDirectoryResolver(
                        paths(),
                        (profile, version) -> {
                            installationRequests.incrementAndGet();
                            return CompletableFuture.completedFuture(
                                    temporaryDirectory.resolve("unexpected")
                            );
                        }
                );

        Path resolved = resolver.resolve(
                profile(),
                "1.21.11",
                "custom/server",
                () -> false
        );

        assertEquals(
                temporaryDirectory.resolve("software/custom/server")
                        .toAbsolutePath()
                        .normalize(),
                resolved
        );
        assertEquals(0, installationRequests.get());
    }

    @Test
    void cancelledOverrideResolutionFailsBeforePathLookup() {
        SoftwareBaseDirectoryResolver resolver =
                new SoftwareBaseDirectoryResolver(
                        paths(),
                        (SoftwareBaseDirectoryResolver.InstallationRequest) null
                );

        ProcessSpecificationException failure = assertThrows(
                ProcessSpecificationException.class,
                () -> resolver.resolve(
                        profile(),
                        "1.21.11",
                        "custom/server",
                        () -> true
                )
        );

        assertTrue(failure.getMessage().contains("was cancelled"));
    }

    @Test
    void cancellingWaitDoesNotCancelSharedInstallation() {
        CompletableFuture<Path> sharedInstallation = new CompletableFuture<>();
        AtomicInteger cancellationChecks = new AtomicInteger();
        SoftwareBaseDirectoryResolver resolver =
                new SoftwareBaseDirectoryResolver(
                        paths(),
                        (profile, version) -> sharedInstallation
                );

        ProcessSpecificationException failure = assertThrows(
                ProcessSpecificationException.class,
                () -> resolver.resolve(
                        profile(),
                        "1.21.11",
                        null,
                        () -> cancellationChecks.incrementAndGet() > 1
                )
        );

        assertTrue(failure.getMessage().contains("wait was cancelled"));
        assertFalse(sharedInstallation.isDone());
    }

    @Test
    void returnsCompletedInstallationDirectory() throws Exception {
        Path installed = temporaryDirectory.resolve("installed");
        SoftwareBaseDirectoryResolver resolver =
                new SoftwareBaseDirectoryResolver(
                        paths(),
                        (profile, version) ->
                                CompletableFuture.completedFuture(installed)
                );

        assertEquals(
                installed,
                resolver.resolve(
                        profile(),
                        "1.21.11",
                        null,
                        () -> false
                )
        );
    }

    @Test
    void translatesInstallationFailureWithoutLosingCause() {
        IllegalStateException cause =
                new IllegalStateException("download failed");
        SoftwareBaseDirectoryResolver resolver =
                new SoftwareBaseDirectoryResolver(
                        paths(),
                        (profile, version) ->
                                CompletableFuture.failedFuture(cause)
                );

        ProcessSpecificationException failure = assertThrows(
                ProcessSpecificationException.class,
                () -> resolver.resolve(
                        profile(),
                        "1.21.11",
                        null,
                        () -> false
                )
        );

        assertEquals("download failed", failure.getMessage());
        assertSame(cause, failure.getCause());
    }

    @Test
    void interruptionIsRestoredAndTranslated() {
        CompletableFuture<Path> installation = new CompletableFuture<>();
        SoftwareBaseDirectoryResolver resolver =
                new SoftwareBaseDirectoryResolver(
                        paths(),
                        (profile, version) -> installation
                );

        Thread.currentThread().interrupt();
        try {
            ProcessSpecificationException failure = assertThrows(
                    ProcessSpecificationException.class,
                    () -> resolver.resolve(
                            profile(),
                            "1.21.11",
                            null,
                            () -> false
                    )
            );

            assertTrue(failure.getMessage().contains("Interrupted"));
            assertTrue(Thread.currentThread().isInterrupted());
            assertFalse(installation.isCancelled());
        } finally {
            Thread.interrupted();
        }
    }

    private JavaJarProcessSpecFactory paths() {
        return new JavaJarProcessSpecFactory(temporaryDirectory);
    }

    private static SoftwareProfile profile() {
        return new SoftwareProfile(
                "paper",
                SoftwareRuntime.JAVA_JAR,
                SoftwareConfigurator.PAPER,
                SoftwareSource.PAPER,
                SoftwareReleaseChannel.STABLE,
                true,
                "java",
                Map.of(),
                "software/paper/{version}",
                "paper.jar",
                List.of(),
                List.of(),
                "Done",
                180,
                "stop",
                30
        );
    }
}
