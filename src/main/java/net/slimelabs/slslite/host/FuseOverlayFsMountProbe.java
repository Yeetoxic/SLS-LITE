package net.slimelabs.slslite.host;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

final class FuseOverlayFsMountProbe {

    OverlayFsMountProbe.Result probe(Path instancesDirectory) {
        return new OverlayFsMountProbe(
                new CommandMountOperations()
        ).probe(instancesDirectory);
    }

    private static final class CommandMountOperations
            implements OverlayFsMountProbe.MountOperations {

        private static final long START_TIMEOUT_MILLIS = 5_000;
        private Process process;

        @Override
        public void mount(
                Path lower,
                Path upper,
                Path work,
                Path merged
        ) throws IOException {
            String options = "lowerdir=" + optionPath(lower)
                    + ",upperdir=" + optionPath(upper)
                    + ",workdir=" + optionPath(work);
            process = new ProcessBuilder(
                    "fuse-overlayfs",
                    "-f",
                    "-o",
                    options,
                    merged.toAbsolutePath().normalize().toString()
            )
                    .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            try {
                long deadline = System.nanoTime()
                        + TimeUnit.MILLISECONDS.toNanos(
                                START_TIMEOUT_MILLIS
                        );
                while (System.nanoTime() < deadline) {
                    if (!process.isAlive()) {
                        throw new IOException(
                                "fuse-overlayfs exited before mounting with "
                                        + "code " + process.exitValue()
                        );
                    }
                    Optional<String> type = filesystemTypeAt(merged);
                    if (type.isPresent()) {
                        if (!isFuseOverlay(type.orElseThrow())) {
                            throw new IOException(
                                    "an unexpected filesystem appeared at "
                                            + merged
                            );
                        }
                        return;
                    }
                    Thread.sleep(50);
                }
                throw new IOException(
                        "fuse-overlayfs contained mount timed out"
                );
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException(
                        "fuse-overlayfs contained mount was interrupted",
                        exception
                );
            } catch (IOException | RuntimeException exception) {
                if (process.isAlive()) {
                    process.destroyForcibly();
                }
                throw exception;
            }
        }

        @Override
        public void unmount(Path merged) throws IOException {
            Optional<String> type = filesystemTypeAt(merged);
            if (type.isPresent()
                    && !isFuseOverlay(type.orElseThrow())) {
                throw new IOException(
                        "refusing to unmount an unexpected filesystem at "
                                + merged
                );
            }
            run(unmounter(), "-u", merged.toString());
            if (filesystemTypeAt(merged).isPresent()) {
                throw new IOException(
                        "fuse-overlayfs target remains mounted: " + merged
                );
            }
            if (process != null && process.isAlive()) {
                try {
                    if (!process.waitFor(2, TimeUnit.SECONDS)) {
                        process.destroyForcibly();
                    }
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                    throw new IOException(
                            "interrupted while stopping fuse-overlayfs probe",
                            exception
                    );
                }
            }
        }

        private static Optional<String> filesystemTypeAt(Path target)
                throws IOException {
            Path normalized = target.toAbsolutePath().normalize();
            for (String line : Files.readAllLines(
                    Path.of("/proc/self/mountinfo")
            )) {
                String[] fields = line.split(" ");
                if (fields.length <= 4
                        || !decodeMountInfoPath(fields[4]).equals(
                                normalized
                        )) {
                    continue;
                }
                for (int index = 5; index + 1 < fields.length; index++) {
                    if ("-".equals(fields[index])) {
                        return Optional.of(fields[index + 1]);
                    }
                }
                throw new IOException(
                        "malformed mountinfo entry for " + normalized
                );
            }
            return Optional.empty();
        }

        private static Path decodeMountInfoPath(String value) {
            StringBuilder decoded = new StringBuilder(value.length());
            for (int index = 0; index < value.length(); index++) {
                char current = value.charAt(index);
                if (current == '\\' && index + 3 < value.length()) {
                    String octal = value.substring(index + 1, index + 4);
                    if (octal.chars().allMatch(character ->
                            character >= '0' && character <= '7')) {
                        decoded.append(
                                (char) Integer.parseInt(octal, 8)
                        );
                        index += 3;
                        continue;
                    }
                }
                decoded.append(current);
            }
            return Path.of(decoded.toString())
                    .toAbsolutePath()
                    .normalize();
        }

        private static boolean isFuseOverlay(String type) {
            return type.equals("fuse.fuse-overlayfs")
                    || type.equals("fuse-overlayfs");
        }

        private static String optionPath(Path path) throws IOException {
            String value = path.toAbsolutePath().normalize().toString();
            if (value.indexOf(',') >= 0
                    || value.indexOf(':') >= 0
                    || value.indexOf('\\') >= 0) {
                throw new IOException(
                        "fuse-overlayfs option path is unsafe: " + value
                );
            }
            return value;
        }

        private static void run(String... command) throws IOException {
            Process child = null;
            try {
                child = new ProcessBuilder(command)
                        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
                        .redirectError(ProcessBuilder.Redirect.DISCARD)
                        .start();
                if (!child.waitFor(10, TimeUnit.SECONDS)) {
                    child.destroyForcibly();
                    throw new IOException(command[0] + " timed out");
                }
                if (child.exitValue() != 0) {
                    throw new IOException(
                            command[0] + " exited with code "
                                    + child.exitValue()
                    );
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new IOException(
                        command[0] + " was interrupted",
                        exception
                );
            } finally {
                if (child != null && child.isAlive()) {
                    child.destroyForcibly();
                }
            }
        }

        private static String unmounter() throws IOException {
            String pathValue = System.getenv("PATH");
            if (pathValue != null) {
                for (String name : java.util.List.of(
                        "fusermount3",
                        "fusermount"
                )) {
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
    }
}
