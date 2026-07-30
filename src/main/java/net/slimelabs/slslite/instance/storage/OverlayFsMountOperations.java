package net.slimelabs.slslite.instance.storage;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

final class OverlayFsMountOperations {

    private static final long COMMAND_TIMEOUT_SECONDS = 10;
    private static final int MAX_ERROR_BYTES = 8_192;

    void mount(
            List<Path> lowerDirectories,
            Path upperDirectory,
            Path workDirectory,
            Path target
    ) throws IOException {
        if (lowerDirectories.isEmpty()) {
            throw new IOException("OverlayFS requires at least one lower directory");
        }
        if (isMounted(target)) {
            throw new IOException("OverlayFS target is already mounted: " + target);
        }
        String lowers = lowerDirectories.stream()
                .map(OverlayFsMountOperations::optionPath)
                .reduce((left, right) -> left + ":" + right)
                .orElseThrow();
        String options = "lowerdir=" + lowers
                + ",upperdir=" + optionPath(upperDirectory)
                + ",workdir=" + optionPath(workDirectory);
        run(
                "mount",
                "-t",
                "overlay",
                "overlay",
                "-o",
                options,
                target.toString()
        );
        if (!isExpectedMount(target, upperDirectory, workDirectory)) {
            throw new IOException(
                    "mount exited successfully but the expected OverlayFS "
                            + "mount was not found at " + target
            );
        }
    }

    void unmount(
            Path target,
            Path upperDirectory,
            Path workDirectory
    ) throws IOException {
        Optional<MountInfo> mounted = mountAt(target);
        if (mounted.isEmpty()) {
            return;
        }
        if (!mounted.orElseThrow().matches(upperDirectory, workDirectory)) {
            throw new IOException(
                    "Refusing to unmount an unexpected filesystem at " + target
            );
        }
        run("umount", target.toString());
        if (isMounted(target)) {
            throw new IOException(
                    "umount exited successfully but target remains mounted: " + target
            );
        }
    }

    boolean isMounted(Path target) throws IOException {
        return mountAt(target).isPresent();
    }

    List<Path> mountPointsBeneath(Path root) throws IOException {
        Path normalized = root.toAbsolutePath().normalize();
        Path mountInfo = Path.of("/proc/self/mountinfo");
        if (!Files.isRegularFile(mountInfo)) {
            if (System.getProperty("os.name", "")
                    .toLowerCase(java.util.Locale.ROOT)
                    .contains("linux")) {
                throw new IOException(
                        "Linux mount table is unavailable: " + mountInfo
                );
            }
            return List.of();
        }
        List<Path> mounts = new ArrayList<>();
        for (String line : Files.readAllLines(mountInfo)) {
            String[] fields = line.split(" ");
            if (fields.length <= 4) {
                continue;
            }
            Path mountPoint = decodeMountInfoPath(fields[4]);
            if (mountPoint.equals(normalized)
                    || mountPoint.startsWith(normalized)) {
                mounts.add(mountPoint);
            }
        }
        return List.copyOf(mounts);
    }

    private boolean isExpectedMount(
            Path target,
            Path upperDirectory,
            Path workDirectory
    ) throws IOException {
        return mountAt(target)
                .filter(info -> info.matches(upperDirectory, workDirectory))
                .isPresent();
    }

    private Optional<MountInfo> mountAt(Path target) throws IOException {
        Path normalized = target.toAbsolutePath().normalize();
        for (String line : Files.readAllLines(Path.of("/proc/self/mountinfo"))) {
            String[] fields = line.split(" ");
            if (fields.length <= 4
                    || !decodeMountInfoPath(fields[4]).equals(normalized)) {
                continue;
            }
            int separator = -1;
            for (int index = 5; index < fields.length; index++) {
                if ("-".equals(fields[index])) {
                    separator = index;
                    break;
                }
            }
            if (separator < 0 || separator + 3 >= fields.length) {
                throw new IOException(
                        "Malformed mountinfo entry for " + normalized
                );
            }
            return Optional.of(new MountInfo(
                    fields[separator + 1],
                    option(fields[separator + 3], "upperdir"),
                    option(fields[separator + 3], "workdir")
            ));
        }
        return Optional.empty();
    }

    static Path decodeMountInfoPath(String value) {
        StringBuilder decoded = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char current = value.charAt(index);
            if (current == '\\' && index + 3 < value.length()) {
                String octal = value.substring(index + 1, index + 4);
                if (octal.chars().allMatch(character ->
                        character >= '0' && character <= '7')) {
                    decoded.append((char) Integer.parseInt(octal, 8));
                    index += 3;
                    continue;
                }
            }
            decoded.append(current);
        }
        return Path.of(decoded.toString()).toAbsolutePath().normalize();
    }

    private static String optionPath(Path path) {
        String value = path.toAbsolutePath().normalize().toString();
        if (value.indexOf(',') >= 0
                || value.indexOf(':') >= 0
                || value.indexOf('\\') >= 0) {
            throw new IllegalArgumentException(
                    "OverlayFS option paths may not contain ',', ':', or '\\': "
                            + value
            );
        }
        return value;
    }

    private static Optional<Path> option(String options, String name) {
        String prefix = name + "=";
        for (String option : options.split(",")) {
            if (option.startsWith(prefix)) {
                return Optional.of(decodeMountInfoPath(
                        option.substring(prefix.length())
                ));
            }
        }
        return Optional.empty();
    }

    private static void run(String... command) throws IOException {
        Process process = null;
        Thread errorReader = null;
        AtomicReference<String> errorOutput = new AtomicReference<>("");
        try {
            process = new ProcessBuilder(command)
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .start();
            Process started = process;
            errorReader = Thread.ofVirtual().start(() ->
                    errorOutput.set(readError(started.getErrorStream()))
            );
            if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                throw new IOException(command[0] + " timed out");
            }
            errorReader.join(1_000);
            if (process.exitValue() != 0) {
                String detail = sanitized(errorOutput.get());
                throw new IOException(
                        command[0] + " exited with code " + process.exitValue()
                                + (detail.isBlank() ? "" : ": " + detail)
                );
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IOException(command[0] + " was interrupted", exception);
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            if (errorReader != null && errorReader.isAlive()) {
                errorReader.interrupt();
            }
        }
    }

    private static String readError(InputStream input) {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        byte[] buffer = new byte[1_024];
        int retained = 0;
        boolean truncated = false;
        try (input) {
            int read;
            while ((read = input.read(buffer)) >= 0) {
                int keep = Math.min(read, MAX_ERROR_BYTES - retained);
                if (keep > 0) {
                    captured.write(buffer, 0, keep);
                    retained += keep;
                }
                truncated |= keep < read;
            }
        } catch (IOException ignored) {
            // The process exit status remains the primary diagnostic.
        }
        String value = captured.toString(StandardCharsets.UTF_8);
        return truncated ? value + "..." : value;
    }

    private static String sanitized(String value) {
        return value == null
                ? ""
                : value.replaceAll("\\s+", " ").strip();
    }

    private record MountInfo(
            String filesystemType,
            Optional<Path> upperDirectory,
            Optional<Path> workDirectory
    ) {

        private boolean matches(Path expectedUpper, Path expectedWork) {
            return "overlay".equals(filesystemType)
                    && upperDirectory.map(path -> path.equals(normalized(
                            expectedUpper
                    ))).orElse(false)
                    && workDirectory.map(path -> path.equals(normalized(
                            expectedWork
                    ))).orElse(false);
        }

        private static Path normalized(Path path) {
            return path.toAbsolutePath().normalize();
        }
    }
}
