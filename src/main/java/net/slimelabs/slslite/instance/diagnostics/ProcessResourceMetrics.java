package net.slimelabs.slslite.instance.diagnostics;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;

public final class ProcessResourceMetrics {

    private static final Path PROC_ROOT = Path.of("/proc");

    private ProcessResourceMetrics() {
    }

    public static Optional<Snapshot> inspect(long processId) {
        return inspect(processId, PROC_ROOT);
    }

    static Optional<Snapshot> inspect(long processId, Path procRoot) {
        if (processId <= 0) {
            return Optional.empty();
        }
        Path process = procRoot.resolve(Long.toString(processId));
        if (!Files.isDirectory(process)) {
            return Optional.empty();
        }
        OptionalLong residentBytes = residentBytes(process.resolve("status"));
        Map<String, Long> io = ioCounters(process.resolve("io"));
        if (residentBytes.isEmpty() && io.isEmpty()) {
            return Optional.empty();
        }
        return Optional.of(new Snapshot(
                residentBytes,
                value(io, "rchar"),
                value(io, "wchar"),
                value(io, "read_bytes"),
                value(io, "write_bytes")
        ));
    }

    private static OptionalLong residentBytes(Path status) {
        try {
            for (String line : Files.readAllLines(status)) {
                if (!line.startsWith("VmRSS:")) {
                    continue;
                }
                String[] fields = line.substring("VmRSS:".length())
                        .strip()
                        .split("\\s+");
                if (fields.length < 1) {
                    return OptionalLong.empty();
                }
                long kibibytes = Long.parseLong(fields[0]);
                if (kibibytes < 0) {
                    return OptionalLong.empty();
                }
                return OptionalLong.of(Math.multiplyExact(kibibytes, 1024L));
            }
        } catch (IOException | ArithmeticException | NumberFormatException ignored) {
            // Report unavailable rather than guessing.
        }
        return OptionalLong.empty();
    }

    private static Map<String, Long> ioCounters(Path ioPath) {
        Map<String, Long> values = new HashMap<>();
        try {
            for (String line : Files.readAllLines(ioPath)) {
                String[] fields = line.split(":", 2);
                if (fields.length != 2) {
                    continue;
                }
                long value = Long.parseLong(fields[1].strip());
                if (value >= 0) {
                    values.put(fields[0].strip(), value);
                }
            }
        } catch (IOException | NumberFormatException ignored) {
            return Map.of();
        }
        return Map.copyOf(values);
    }

    private static OptionalLong value(Map<String, Long> values, String key) {
        Long value = values.get(key);
        return value == null
                ? OptionalLong.empty()
                : OptionalLong.of(value);
    }

    public record Snapshot(
            OptionalLong residentBytes,
            OptionalLong charactersRead,
            OptionalLong charactersWritten,
            OptionalLong storageBytesRead,
            OptionalLong storageBytesWritten
    ) {
    }
}
