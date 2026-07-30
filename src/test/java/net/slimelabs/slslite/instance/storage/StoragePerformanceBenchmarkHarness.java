package net.slimelabs.slslite.instance.storage;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.OptionalLong;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Opt-in repeated production-copy sampler for disposable storage.
 */
public final class StoragePerformanceBenchmarkHarness {

    private static final int MAX_REPEATS = 20;

    private StoragePerformanceBenchmarkHarness() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length < 3
                || arguments.length > 6
                || arguments.length == 5) {
            throw new IllegalArgumentException(
                    "Expected source, empty benchmark root, profile label, "
                            + "optional repeat count, and optional preparation "
                            + "and cleanup p95 limits in milliseconds"
            );
        }
        Path source = Path.of(arguments[0]).toAbsolutePath().normalize();
        Path root = Path.of(arguments[1]).toAbsolutePath().normalize();
        String profile = normalizedLabel(arguments[2]);
        int repeats = arguments.length == 4
                ? Integer.parseInt(arguments[3])
                : 3;
        if (arguments.length == 6) {
            repeats = Integer.parseInt(arguments[3]);
        }
        Thresholds thresholds = arguments.length == 6
                ? new Thresholds(
                        positiveMillis(arguments[4], "preparation p95"),
                        positiveMillis(arguments[5], "cleanup p95")
                )
                : null;
        if (repeats < 1 || repeats > MAX_REPEATS) {
            throw new IllegalArgumentException(
                    "Repeat count must be between 1 and " + MAX_REPEATS
            );
        }
        if (!Files.isDirectory(source, LinkOption.NOFOLLOW_LINKS)) {
            throw new IllegalArgumentException(
                    "Source must be an existing directory: " + source
            );
        }
        if (!Files.isDirectory(root, LinkOption.NOFOLLOW_LINKS)
                || !isEmpty(root)) {
            throw new IllegalArgumentException(
                    "Benchmark root must be an existing empty directory: " + root
            );
        }
        if (root.startsWith(source) || source.startsWith(root)) {
            throw new IllegalArgumentException(
                    "Source and benchmark root must not contain one another"
            );
        }

        TreeSize sourceSize = treeSize(source);
        OptionalLong sourceAllocated = allocatedBytes(source);
        List<Sample> samples = new ArrayList<>();
        Path instances = root.resolve("instances");
        InstanceDirectoryPreparer preparer =
                new InstanceDirectoryPreparer(instances, source.getParent());
        for (int run = 1; run <= repeats; run++) {
            String instanceId = "benchmark." + String.format(
                    Locale.ROOT,
                    "%06d",
                    run
            );
            IoSnapshot before = IoSnapshot.read();
            try (RssSampler rss = new RssSampler()) {
                long preparationStarted = System.nanoTime();
                Path target = preparer.prepare(instanceId, source);
                long preparationNanos = elapsed(preparationStarted);
                IoSnapshot prepared = IoSnapshot.read();
                TreeSize targetSize = treeSize(target);
                OptionalLong targetAllocated = allocatedBytes(target);
                requireEquivalent(sourceSize, targetSize);

                long cleanupStarted = System.nanoTime();
                preparer.delete(instanceId);
                long cleanupNanos = elapsed(cleanupStarted);
                if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
                    throw new IllegalStateException(
                            "Cleanup left benchmark target present: " + target
                    );
                }
                samples.add(new Sample(
                        run,
                        preparationNanos,
                        cleanupNanos,
                        targetAllocated,
                        prepared.minus(before),
                        rss.peakBytes()
                ));
            }
        }

        printHeader(
                profile,
                source,
                repeats,
                sourceSize,
                sourceAllocated
        );
        samples.forEach(sample -> printSample(profile, sample));
        Summary summary = printSummary(profile, samples);
        if (thresholds != null) {
            enforceThresholds(profile, summary, thresholds);
        }
    }

    private static void printHeader(
            String profile,
            Path source,
            int repeats,
            TreeSize size,
            OptionalLong allocated
    ) {
        System.out.printf(
                Locale.ROOT,
                "benchmark profile=%s source=%s filesystem=%s repeats=%d "
                        + "files=%d logical-bytes=%d allocated-bytes=%s%n",
                profile,
                source,
                fileSystemType(source),
                repeats,
                size.files(),
                size.logicalBytes(),
                value(allocated)
        );
    }

    private static void printSample(String profile, Sample sample) {
        System.out.printf(
                Locale.ROOT,
                "sample profile=%s run=%d prepare-ms=%.3f cleanup-ms=%.3f "
                        + "target-allocated-bytes=%s peak-rss-bytes=%s "
                        + "rchar=%s wchar=%s read-bytes=%s write-bytes=%s%n",
                profile,
                sample.run(),
                millis(sample.preparationNanos()),
                millis(sample.cleanupNanos()),
                value(sample.targetAllocatedBytes()),
                value(sample.peakRssBytes()),
                value(sample.io().rchar()),
                value(sample.io().wchar()),
                value(sample.io().readBytes()),
                value(sample.io().writeBytes())
        );
    }

    private static Summary printSummary(String profile, List<Sample> samples) {
        List<Long> preparation = samples.stream()
                .map(Sample::preparationNanos)
                .sorted()
                .toList();
        List<Long> cleanup = samples.stream()
                .map(Sample::cleanupNanos)
                .sorted()
                .toList();
        Summary summary = new Summary(
                preparation.getFirst(),
                percentile(preparation, 0.50d),
                percentile(preparation, 0.95d),
                cleanup.getFirst(),
                percentile(cleanup, 0.50d),
                percentile(cleanup, 0.95d)
        );
        System.out.printf(
                Locale.ROOT,
                "summary profile=%s prepare-min-ms=%.3f "
                        + "prepare-median-ms=%.3f prepare-p95-ms=%.3f "
                        + "cleanup-min-ms=%.3f cleanup-median-ms=%.3f "
                        + "cleanup-p95-ms=%.3f%n",
                profile,
                millis(summary.preparationMinimumNanos()),
                millis(summary.preparationMedianNanos()),
                millis(summary.preparationP95Nanos()),
                millis(summary.cleanupMinimumNanos()),
                millis(summary.cleanupMedianNanos()),
                millis(summary.cleanupP95Nanos())
        );
        return summary;
    }

    private static void enforceThresholds(
            String profile,
            Summary summary,
            Thresholds thresholds
    ) {
        double preparationP95 = millis(summary.preparationP95Nanos());
        double cleanupP95 = millis(summary.cleanupP95Nanos());
        System.out.printf(
                Locale.ROOT,
                "threshold profile=%s prepare-p95-limit-ms=%.3f "
                        + "cleanup-p95-limit-ms=%.3f outcome=%s%n",
                profile,
                thresholds.preparationP95Millis(),
                thresholds.cleanupP95Millis(),
                preparationP95 <= thresholds.preparationP95Millis()
                                && cleanupP95 <= thresholds.cleanupP95Millis()
                        ? "pass"
                        : "fail"
        );
        if (preparationP95 > thresholds.preparationP95Millis()
                || cleanupP95 > thresholds.cleanupP95Millis()) {
            throw new IllegalStateException(String.format(
                    Locale.ROOT,
                    "Benchmark threshold exceeded for %s: prepare p95 %.3f/%.3f "
                            + "ms, cleanup p95 %.3f/%.3f ms",
                    profile,
                    preparationP95,
                    thresholds.preparationP95Millis(),
                    cleanupP95,
                    thresholds.cleanupP95Millis()
            ));
        }
    }

    private static long percentile(List<Long> sorted, double percentile) {
        int index = (int) Math.ceil(percentile * sorted.size()) - 1;
        return sorted.get(Math.max(0, Math.min(sorted.size() - 1, index)));
    }

    private static TreeSize treeSize(Path root) throws IOException {
        AtomicLong files = new AtomicLong();
        AtomicLong bytes = new AtomicLong();
        Files.walkFileTree(root, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(
                    Path file,
                    BasicFileAttributes attributes
            ) throws IOException {
                if (!attributes.isRegularFile()) {
                    throw new IOException(
                            "Benchmark source contains a non-regular entry: " + file
                    );
                }
                files.incrementAndGet();
                bytes.addAndGet(attributes.size());
                return FileVisitResult.CONTINUE;
            }
        });
        return new TreeSize(files.get(), bytes.get());
    }

    private static void requireEquivalent(TreeSize source, TreeSize target) {
        if (!source.equals(target)) {
            throw new IllegalStateException(
                    "Copied tree differs: source=" + source + ", target=" + target
            );
        }
    }

    private static OptionalLong allocatedBytes(Path path) {
        Process process = null;
        try {
            process = new ProcessBuilder(
                    "du",
                    "-s",
                    "-B1",
                    "--",
                    path.toString()
            )
                    .redirectError(ProcessBuilder.Redirect.DISCARD)
                    .start();
            if (!process.waitFor(30, TimeUnit.SECONDS)) {
                process.destroyForcibly();
                return OptionalLong.empty();
            }
            if (process.exitValue() != 0) {
                return OptionalLong.empty();
            }
            byte[] output = process.getInputStream().readNBytes(4096);
            String value = new String(output, StandardCharsets.UTF_8)
                    .strip()
                    .split("\\s+", 2)[0];
            return OptionalLong.of(Long.parseLong(value));
        } catch (IOException | InterruptedException | RuntimeException exception) {
            if (exception instanceof InterruptedException) {
                Thread.currentThread().interrupt();
            }
            return OptionalLong.empty();
        } finally {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
        }
    }

    private static boolean isEmpty(Path directory) throws IOException {
        try (var entries = Files.list(directory)) {
            return entries.findAny().isEmpty();
        }
    }

    private static String fileSystemType(Path path) {
        try {
            return normalizedLabel(Files.getFileStore(path).type());
        } catch (IOException | RuntimeException exception) {
            return "unavailable";
        }
    }

    private static String normalizedLabel(String value) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Profile label must not be blank");
        }
        return value.strip().replaceAll("[^A-Za-z0-9._-]", "-");
    }

    private static double positiveMillis(String value, String label) {
        double parsed = Double.parseDouble(value);
        if (!Double.isFinite(parsed) || parsed <= 0.0d) {
            throw new IllegalArgumentException(label + " limit must be positive");
        }
        return parsed;
    }

    private static long elapsed(long startedAt) {
        return Math.max(0L, System.nanoTime() - startedAt);
    }

    private static double millis(long nanos) {
        return nanos / 1_000_000.0d;
    }

    private static String value(OptionalLong value) {
        return value.isPresent()
                ? Long.toString(value.getAsLong())
                : "unavailable";
    }

    private record TreeSize(long files, long logicalBytes) {
    }

    private record Summary(
            long preparationMinimumNanos,
            long preparationMedianNanos,
            long preparationP95Nanos,
            long cleanupMinimumNanos,
            long cleanupMedianNanos,
            long cleanupP95Nanos
    ) {
    }

    private record Thresholds(
            double preparationP95Millis,
            double cleanupP95Millis
    ) {
    }

    private record Sample(
            int run,
            long preparationNanos,
            long cleanupNanos,
            OptionalLong targetAllocatedBytes,
            IoSnapshot io,
            OptionalLong peakRssBytes
    ) {
    }

    private record IoSnapshot(
            OptionalLong rchar,
            OptionalLong wchar,
            OptionalLong readBytes,
            OptionalLong writeBytes
    ) {

        private static IoSnapshot read() {
            Path path = Path.of("/proc/self/io");
            if (!Files.isRegularFile(path)) {
                return unavailable();
            }
            try {
                Map<String, Long> values = Files.readAllLines(path).stream()
                        .map(line -> line.split(":", 2))
                        .filter(fields -> fields.length == 2)
                        .collect(java.util.stream.Collectors.toMap(
                                fields -> fields[0].strip(),
                                fields -> Long.parseLong(fields[1].strip())
                        ));
                return new IoSnapshot(
                        optional(values.get("rchar")),
                        optional(values.get("wchar")),
                        optional(values.get("read_bytes")),
                        optional(values.get("write_bytes"))
                );
            } catch (IOException | RuntimeException exception) {
                return unavailable();
            }
        }

        private IoSnapshot minus(IoSnapshot earlier) {
            return new IoSnapshot(
                    difference(rchar, earlier.rchar),
                    difference(wchar, earlier.wchar),
                    difference(readBytes, earlier.readBytes),
                    difference(writeBytes, earlier.writeBytes)
            );
        }

        private static IoSnapshot unavailable() {
            return new IoSnapshot(
                    OptionalLong.empty(),
                    OptionalLong.empty(),
                    OptionalLong.empty(),
                    OptionalLong.empty()
            );
        }

        private static OptionalLong optional(Long value) {
            return value == null
                    ? OptionalLong.empty()
                    : OptionalLong.of(value);
        }

        private static OptionalLong difference(
                OptionalLong later,
                OptionalLong earlier
        ) {
            if (later.isEmpty() || earlier.isEmpty()) {
                return OptionalLong.empty();
            }
            return OptionalLong.of(Math.max(
                    0L,
                    later.getAsLong() - earlier.getAsLong()
            ));
        }
    }

    private static final class RssSampler implements AutoCloseable {

        private final AtomicBoolean running = new AtomicBoolean(true);
        private final AtomicLong peakBytes = new AtomicLong(-1L);
        private final Thread thread;

        private RssSampler() {
            sample();
            thread = Thread.ofPlatform()
                    .daemon(true)
                    .name("sls-benchmark-rss")
                    .start(() -> {
                        while (running.get()) {
                            sample();
                            try {
                                Thread.sleep(10);
                            } catch (InterruptedException exception) {
                                Thread.currentThread().interrupt();
                                return;
                            }
                        }
                    });
        }

        private void sample() {
            currentRssBytes().ifPresent(value ->
                    peakBytes.accumulateAndGet(value, Math::max));
        }

        private OptionalLong peakBytes() {
            long value = peakBytes.get();
            return value < 0
                    ? OptionalLong.empty()
                    : OptionalLong.of(value);
        }

        @Override
        public void close() throws InterruptedException {
            running.set(false);
            thread.interrupt();
            thread.join(1_000);
            sample();
        }

        private static OptionalLong currentRssBytes() {
            Path status = Path.of("/proc/self/status");
            if (!Files.isRegularFile(status)) {
                return OptionalLong.empty();
            }
            try {
                for (String line : Files.readAllLines(status)) {
                    if (line.startsWith("VmRSS:")) {
                        String kibibytes = line.substring("VmRSS:".length())
                                .strip()
                                .split("\\s+", 2)[0];
                        return OptionalLong.of(
                                Long.parseLong(kibibytes) * 1024L
                        );
                    }
                }
            } catch (IOException | RuntimeException ignored) {
                // Report the metric as unavailable.
            }
            return OptionalLong.empty();
        }
    }
}
