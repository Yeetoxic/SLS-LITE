package net.slimelabs.slslite.instance.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;

final class FuseOverlayFsMountAdapter
        implements OverlayFsLayerManager.MountAdapter {

    private static final long START_TIMEOUT_MILLIS = 5_000;
    private static final long COMMAND_TIMEOUT_SECONDS = 10;

    private final ConcurrentHashMap<Path, Process> processes =
            new ConcurrentHashMap<>();

    @Override
    public String storageType() {
        return "fuse-overlay";
    }

    @Override
    public void mount(
            List<Path> lowerDirectories,
            Path upperDirectory,
            Path workDirectory,
            Path target
    ) throws IOException {
        Path normalizedTarget = normalized(target);
        if (lowerDirectories.isEmpty()) {
            throw new IOException(
                    "fuse-overlayfs requires at least one lower directory"
            );
        }
        if (mountAt(normalizedTarget).isPresent()) {
            throw new IOException(
                    "fuse-overlayfs target is already mounted: "
                            + normalizedTarget
            );
        }
        String options = options(
                lowerDirectories,
                upperDirectory,
                workDirectory
        );
        Process process = new ProcessBuilder(
                "fuse-overlayfs",
                "-f",
                "-o",
                options,
                normalizedTarget.toString()
        )
                .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                .redirectError(ProcessBuilder.Redirect.DISCARD)
                .start();
        processes.put(normalizedTarget, process);
        boolean mounted = false;
        try {
            long deadline = System.nanoTime()
                    + TimeUnit.MILLISECONDS.toNanos(START_TIMEOUT_MILLIS);
            while (System.nanoTime() < deadline) {
                if (!process.isAlive()) {
                    throw new IOException(
                            "fuse-overlayfs exited before mounting "
                                    + normalizedTarget + " with code "
                                    + process.exitValue()
                    );
                }
                Optional<MountInfo> info = mountAt(normalizedTarget);
                if (info.isPresent()) {
                    if (!info.orElseThrow().fuseOverlay()) {
                        throw new IOException(
                                "An unexpected filesystem appeared at "
                                        + normalizedTarget
                        );
                    }
                    mounted = true;
                    break;
                }
                Thread.sleep(50);
            }
            if (!mounted) {
                throw new IOException(
                        "fuse-overlayfs mount timed out: " + normalizedTarget
                );
            }
            if (!hasExpectedDaemon(normalizedTarget, options)) {
                throw new IOException(
                        "Unable to verify fuse-overlayfs daemon ownership for "
                                + normalizedTarget
                );
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException(
                    "Interrupted while mounting fuse-overlayfs",
                    exception
            );
        } catch (IOException | RuntimeException exception) {
            if (mounted) {
                try {
                    unmount(
                            normalizedTarget,
                            upperDirectory,
                            workDirectory
                    );
                } catch (IOException cleanupFailure) {
                    exception.addSuppressed(cleanupFailure);
                }
            }
            throw exception;
        } finally {
            if (!mounted) {
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
                processes.remove(normalizedTarget, process);
            }
        }
    }

    @Override
    public void unmount(
            Path target,
            Path upperDirectory,
            Path workDirectory
    ) throws IOException {
        Path normalizedTarget = normalized(target);
        Optional<MountInfo> mounted = mountAt(normalizedTarget);
        if (mounted.isEmpty()) {
            stopTrackedProcess(normalizedTarget);
            return;
        }
        if (!mounted.orElseThrow().fuseOverlay()) {
            throw new IOException(
                    "Refusing to unmount an unexpected filesystem at "
                            + normalizedTarget
            );
        }
        String expectedOptions = options(
                List.of(),
                upperDirectory,
                workDirectory
        );
        if (!hasExpectedDaemonSuffix(
                normalizedTarget,
                expectedOptions
        )) {
            throw new IOException(
                    "Refusing to unmount fuse-overlayfs without verified "
                            + "upper/work ownership at " + normalizedTarget
            );
        }
        run(unmounter(), "-u", normalizedTarget.toString());
        if (mountAt(normalizedTarget).isPresent()) {
            throw new IOException(
                    "fusermount3 exited successfully but target remains "
                            + "mounted: " + normalizedTarget
            );
        }
        stopTrackedProcess(normalizedTarget);
    }

    @Override
    public boolean isMounted(Path target) throws IOException {
        return mountAt(normalized(target)).isPresent();
    }

    @Override
    public List<Path> mountPointsBeneath(Path root) throws IOException {
        return new OverlayFsMountOperations().mountPointsBeneath(root);
    }

    private boolean hasExpectedDaemon(Path target, String expectedOptions)
            throws IOException {
        return daemonArguments().stream().anyMatch(arguments ->
                matches(arguments, target, expectedOptions, false)
        );
    }

    private boolean hasExpectedDaemonSuffix(
            Path target,
            String expectedSuffix
    ) throws IOException {
        return daemonArguments().stream().anyMatch(arguments ->
                matches(arguments, target, expectedSuffix, true)
        );
    }

    private static boolean matches(
            List<String> arguments,
            Path target,
            String expectedOptions,
            boolean suffixOnly
    ) {
        if (arguments.isEmpty()
                || !Path.of(arguments.get(0))
                        .getFileName()
                        .toString()
                        .equals("fuse-overlayfs")) {
            return false;
        }
        int optionsIndex = arguments.indexOf("-o");
        if (optionsIndex < 0 || optionsIndex + 1 >= arguments.size()
                || !arguments.contains("-f")
                || !arguments.get(arguments.size() - 1).equals(
                        target.toString()
                )) {
            return false;
        }
        String actualOptions = arguments.get(optionsIndex + 1);
        return suffixOnly
                ? actualOptions.endsWith(expectedOptions)
                : actualOptions.equals(expectedOptions);
    }

    private static List<List<String>> daemonArguments() throws IOException {
        Path proc = Path.of("/proc");
        if (!Files.isDirectory(proc)) {
            throw new IOException(
                    "Process table is unavailable for FUSE ownership checks"
            );
        }
        List<List<String>> processes = new ArrayList<>();
        try (var entries = Files.list(proc)) {
            for (Path entry : entries.toList()) {
                if (!entry.getFileName().toString().chars()
                        .allMatch(Character::isDigit)) {
                    continue;
                }
                Path commandLine = entry.resolve("cmdline");
                try {
                    byte[] bytes = Files.readAllBytes(commandLine);
                    if (bytes.length == 0 || bytes.length > 1_048_576) {
                        continue;
                    }
                    processes.add(Arrays.stream(
                                    new String(
                                            bytes,
                                            StandardCharsets.UTF_8
                                    ).split("\\x00")
                            )
                            .filter(value -> !value.isEmpty())
                            .toList());
                } catch (IOException | RuntimeException ignored) {
                    // Processes can exit or be inaccessible while scanning.
                }
            }
        }
        return List.copyOf(processes);
    }

    private static String options(
            List<Path> lowerDirectories,
            Path upperDirectory,
            Path workDirectory
    ) throws IOException {
        String suffix = "upperdir=" + optionPath(upperDirectory)
                + ",workdir=" + optionPath(workDirectory);
        if (lowerDirectories.isEmpty()) {
            return "," + suffix;
        }
        List<String> lowers = new ArrayList<>();
        for (Path lower : lowerDirectories) {
            lowers.add(optionPath(lower));
        }
        return "lowerdir=" + String.join(":", lowers) + "," + suffix;
    }

    private static String optionPath(Path path) throws IOException {
        String value = normalized(path).toString();
        if (value.indexOf(',') >= 0
                || value.indexOf(':') >= 0
                || value.indexOf('\\') >= 0) {
            throw new IOException(
                    "fuse-overlayfs option paths may not contain ',', ':', "
                            + "or '\\': " + value
            );
        }
        return value;
    }

    private void stopTrackedProcess(Path target) throws IOException {
        Process process = processes.remove(target);
        if (process == null || !process.isAlive()) {
            return;
        }
        try {
            if (!process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroy();
            }
            if (process.isAlive()
                    && !process.waitFor(2, TimeUnit.SECONDS)) {
                process.destroyForcibly();
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException(
                    "Interrupted while stopping fuse-overlayfs",
                    exception
            );
        }
    }

    private static Optional<MountInfo> mountAt(Path target)
            throws IOException {
        for (String line : Files.readAllLines(Path.of("/proc/self/mountinfo"))) {
            String[] fields = line.split(" ");
            if (fields.length <= 4
                    || !OverlayFsMountOperations.decodeMountInfoPath(
                            fields[4]
                    ).equals(target)) {
                continue;
            }
            int separator = -1;
            for (int index = 5; index < fields.length; index++) {
                if ("-".equals(fields[index])) {
                    separator = index;
                    break;
                }
            }
            if (separator < 0 || separator + 1 >= fields.length) {
                throw new IOException(
                        "Malformed mountinfo entry for " + target
                );
            }
            return Optional.of(new MountInfo(fields[separator + 1]));
        }
        return Optional.empty();
    }

    private static void run(String... command) throws IOException {
        Process process = null;
        try {
            process = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(
                    COMMAND_TIMEOUT_SECONDS,
                    TimeUnit.SECONDS
            )) {
                process.destroyForcibly();
                throw new IOException(command[0] + " timed out");
            }
            if (process.exitValue() != 0) {
                throw new IOException(
                        command[0] + " exited with code "
                                + process.exitValue()
                );
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException(
                    command[0] + " was interrupted",
                    exception
            );
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static String unmounter() throws IOException {
        String pathValue = System.getenv("PATH");
        if (pathValue != null) {
            for (String name : List.of("fusermount3", "fusermount")) {
                for (String directory : pathValue.split(
                        java.io.File.pathSeparator
                )) {
                    if (directory.isBlank()) {
                        continue;
                    }
                    Path candidate = Path.of(directory).resolve(name);
                    if (Files.isRegularFile(candidate)
                            && Files.isExecutable(candidate)) {
                        return candidate.toString();
                    }
                }
            }
        }
        throw new IOException(
                "Neither fusermount3 nor fusermount is executable"
        );
    }

    private static Path normalized(Path path) {
        return path.toAbsolutePath().normalize();
    }

    private record MountInfo(String filesystemType) {

        private boolean fuseOverlay() {
            return filesystemType.equals("fuse.fuse-overlayfs")
                    || filesystemType.equals("fuse-overlayfs");
        }
    }
}
