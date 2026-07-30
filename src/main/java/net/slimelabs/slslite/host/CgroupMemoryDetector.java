package net.slimelabs.slslite.host;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;

final class CgroupMemoryDetector {

    private static final long UNBOUNDED_V1_THRESHOLD = 1L << 60;
    private final Path procSelfCgroup;
    private final Path cgroupRoot;

    CgroupMemoryDetector() {
        this(Path.of("/proc/self/cgroup"), Path.of("/sys/fs/cgroup"));
    }

    CgroupMemoryDetector(Path procSelfCgroup, Path cgroupRoot) {
        this.procSelfCgroup = procSelfCgroup;
        this.cgroupRoot = cgroupRoot;
    }

    Optional<MemoryLimit> detect() {
        if (!System.getProperty("os.name", "").toLowerCase().contains("linux")
                && procSelfCgroup.equals(Path.of("/proc/self/cgroup"))) {
            return Optional.empty();
        }
        try {
            List<String> membership = Files.exists(procSelfCgroup)
                    ? Files.readAllLines(procSelfCgroup)
                    : List.of();
            Optional<MemoryLimit> v2 = detectV2(membership);
            return v2.isPresent() ? v2 : detectV1(membership);
        } catch (IOException | RuntimeException ignored) {
            return Optional.empty();
        }
    }

    private Optional<MemoryLimit> detectV2(List<String> membership)
            throws IOException {
        Set<Path> candidates = new LinkedHashSet<>();
        for (String line : membership) {
            String[] fields = line.split(":", 3);
            if (fields.length == 3 && fields[0].equals("0")
                    && fields[1].isEmpty()) {
                candidates.add(resolveMembership(cgroupRoot, fields[2]));
            }
        }
        candidates.add(cgroupRoot);
        for (Path directory : candidates) {
            Path maximum = directory.resolve("memory.max");
            if (!Files.isRegularFile(maximum)) {
                continue;
            }
            String rawMaximum = Files.readString(maximum).trim();
            if (rawMaximum.equals("max")) {
                continue;
            }
            long limit = parsePositive(rawMaximum);
            long current = readNonNegative(directory.resolve("memory.current"));
            return Optional.of(new MemoryLimit("cgroup v2", limit, current));
        }
        return Optional.empty();
    }

    private Optional<MemoryLimit> detectV1(List<String> membership)
            throws IOException {
        List<Path> candidates = new ArrayList<>();
        for (String line : membership) {
            String[] fields = line.split(":", 3);
            if (fields.length != 3
                    || !List.of(fields[1].split(",")).contains("memory")) {
                continue;
            }
            candidates.add(resolveMembership(
                    cgroupRoot.resolve("memory"),
                    fields[2]
            ));
            candidates.add(resolveMembership(cgroupRoot, fields[2]));
        }
        candidates.add(cgroupRoot.resolve("memory"));
        candidates.add(cgroupRoot);
        for (Path directory : new LinkedHashSet<>(candidates)) {
            Path maximum = directory.resolve("memory.limit_in_bytes");
            if (!Files.isRegularFile(maximum)) {
                continue;
            }
            long limit = parsePositive(Files.readString(maximum).trim());
            if (limit >= UNBOUNDED_V1_THRESHOLD) {
                continue;
            }
            long current = readNonNegative(
                    directory.resolve("memory.usage_in_bytes")
            );
            return Optional.of(new MemoryLimit("cgroup v1", limit, current));
        }
        return Optional.empty();
    }

    private static Path resolveMembership(Path root, String membership) {
        String relative = membership.startsWith("/")
                ? membership.substring(1)
                : membership;
        Path normalizedRoot = root.toAbsolutePath().normalize();
        Path resolved = normalizedRoot.resolve(relative).normalize();
        return resolved.startsWith(normalizedRoot) ? resolved : normalizedRoot;
    }

    private static long parsePositive(String value) throws IOException {
        try {
            long parsed = Long.parseLong(value);
            if (parsed <= 0) {
                throw new IOException("cgroup memory limit is not positive");
            }
            return parsed;
        } catch (NumberFormatException exception) {
            throw new IOException("invalid cgroup memory limit", exception);
        }
    }

    private static long readNonNegative(Path path) {
        try {
            long parsed = Long.parseLong(Files.readString(path).trim());
            return Math.max(0, parsed);
        } catch (IOException | NumberFormatException ignored) {
            return -1;
        }
    }

    record MemoryLimit(String source, long limitBytes, long currentBytes) {

        MemoryLimit {
            if (limitBytes <= 0 || currentBytes < -1) {
                throw new IllegalArgumentException("invalid memory measurement");
            }
        }

        long availableBytes() {
            return currentBytes < 0
                    ? -1
                    : Math.max(0, limitBytes - currentBytes);
        }
    }
}
