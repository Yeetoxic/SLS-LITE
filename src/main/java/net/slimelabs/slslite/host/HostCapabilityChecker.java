package net.slimelabs.slslite.host;

import net.slimelabs.slslite.blueprint.Blueprint;
import net.slimelabs.slslite.network.LoopbackPortAllocator;
import net.slimelabs.slslite.network.PortAllocationException;
import net.slimelabs.slslite.process.JavaJarProcessSpecFactory;
import net.slimelabs.slslite.process.ProcessSpecificationException;
import net.slimelabs.slslite.software.SoftwareProfile;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;

public final class HostCapabilityChecker {

    private static final long PROCESS_TIMEOUT_SECONDS = 5;

    public HostCapabilityReport check(
            Path instancesDirectory,
            LoopbackPortAllocator portAllocator,
            Collection<Blueprint> blueprints,
            Collection<SoftwareProfile> profiles,
            JavaJarProcessSpecFactory processSpecFactory,
            int managedMemoryMiB
    ) {
        List<HostCapability> results = new ArrayList<>();
        results.add(checkWritableStorage(instancesDirectory));
        results.add(checkLoopbackPort(portAllocator));
        results.addAll(checkJavaProcesses(
                blueprints,
                profiles,
                processSpecFactory
        ));
        results.add(new HostCapability(
                "Managed memory",
                HostCapabilityStatus.WARNING,
                managedMemoryMiB + " MiB is an SLS-LITE admission budget, not "
                        + "a measurement of panel or container memory"
        ));
        return new HostCapabilityReport(results);
    }

    private static HostCapability checkWritableStorage(Path instancesDirectory) {
        Path probeDirectory = null;
        try {
            Files.createDirectories(instancesDirectory);
            probeDirectory = Files.createTempDirectory(instancesDirectory, ".sls-probe-");
            Path file = probeDirectory.resolve("write-test");
            Files.writeString(file, "ok");
            Files.delete(file);
            Files.delete(probeDirectory);
            return pass("Writable storage", instancesDirectory.toString());
        } catch (IOException | RuntimeException exception) {
            deleteProbeBestEffort(probeDirectory);
            return failure("Writable storage", message(exception));
        }
    }

    private static HostCapability checkLoopbackPort(LoopbackPortAllocator portAllocator) {
        Integer port = null;
        try {
            port = portAllocator.allocate();
            return pass("Loopback ports", "Successfully bound 127.0.0.1:" + port);
        } catch (PortAllocationException | RuntimeException exception) {
            return failure("Loopback ports", message(exception));
        } finally {
            if (port != null) {
                portAllocator.release(port);
            }
        }
    }

    private static List<HostCapability> checkJavaProcesses(
            Collection<Blueprint> blueprints,
            Collection<SoftwareProfile> profiles,
            JavaJarProcessSpecFactory processSpecFactory
    ) {
        Set<String> requiredExecutables = new LinkedHashSet<>();
        Set<String> optionalExecutables = new LinkedHashSet<>();
        List<HostCapability> results = new ArrayList<>();
        Map<String, SoftwareProfile> profilesById = new LinkedHashMap<>();
        for (SoftwareProfile profile : profiles) {
            profilesById.put(profile.id(), profile);
            try {
                optionalExecutables.addAll(
                        processSpecFactory.configuredJavaExecutables(profile)
                );
            } catch (ProcessSpecificationException exception) {
                results.add(new HostCapability(
                        "Java runtime " + profile.id(),
                        HostCapabilityStatus.WARNING,
                        message(exception) + " (configured but unused runtime)"
                ));
            }
        }
        for (Blueprint blueprint : blueprints) {
            SoftwareProfile profile = profilesById.get(blueprint.software());
            if (profile == null) {
                results.add(failure(
                        "Java runtime " + blueprint.id(),
                        "Software profile '" + blueprint.software()
                                + "' is not loaded"
                ));
                continue;
            }
            try {
                requiredExecutables.add(processSpecFactory.resolveJavaExecutable(
                        profile,
                        blueprint.version(),
                        blueprint.image()
                ));
            } catch (ProcessSpecificationException exception) {
                results.add(failure(
                        "Java runtime " + blueprint.id(),
                        message(exception)
                ));
            }
        }
        optionalExecutables.removeAll(requiredExecutables);
        if (requiredExecutables.isEmpty() && optionalExecutables.isEmpty()
                && results.isEmpty()) {
            return List.of(new HostCapability(
                    "Child processes",
                    HostCapabilityStatus.WARNING,
                    "No software profiles are loaded, so no Java runtime was tested"
            ));
        }
        for (String executable : requiredExecutables) {
            results.add(checkJavaProcess(executable, true));
        }
        for (String executable : optionalExecutables) {
            results.add(checkJavaProcess(executable, false));
        }
        return results;
    }

    private static HostCapability checkJavaProcess(
            String executable,
            boolean required
    ) {
        Process process = null;
        try {
            process = new ProcessBuilder(executable, "-version")
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(PROCESS_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return unavailableJava(
                        required,
                        "Child Java process",
                        executable + " did not exit within "
                                + PROCESS_TIMEOUT_SECONDS + " seconds"
                );
            }
            if (process.exitValue() != 0) {
                return unavailableJava(
                        required,
                        "Child Java process",
                        executable + " exited with code " + process.exitValue()
                );
            }
            return pass("Child Java process", executable);
        } catch (IOException exception) {
            return unavailableJava(
                    required,
                    "Child Java process",
                    executable + ": " + message(exception)
            );
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return failure("Child Java process", "probe was interrupted");
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static HostCapability unavailableJava(
            boolean required,
            String name,
            String detail
    ) {
        return required
                ? failure(name, detail)
                : new HostCapability(
                        name,
                        HostCapabilityStatus.WARNING,
                        detail + " (configured but unused runtime)"
                );
    }

    private static HostCapability pass(String name, String detail) {
        return new HostCapability(name, HostCapabilityStatus.PASS, detail);
    }

    private static HostCapability failure(String name, String detail) {
        return new HostCapability(name, HostCapabilityStatus.FAILURE, detail);
    }

    private static String message(Throwable throwable) {
        return throwable.getMessage() == null
                ? throwable.getClass().getSimpleName()
                : throwable.getMessage();
    }

    private static void deleteProbeBestEffort(Path probeDirectory) {
        if (probeDirectory == null) {
            return;
        }
        try {
            Files.deleteIfExists(probeDirectory.resolve("write-test"));
            Files.deleteIfExists(probeDirectory);
        } catch (IOException ignored) {
            // The original capability failure is the actionable result.
        }
    }
}
