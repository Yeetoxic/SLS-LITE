package net.slimelabs.slslite.instance.storage;

import net.slimelabs.slslite.config.StorageStrategy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Opt-in bounded-parallel-copy benchmark for disposable storage.
 */
public final class PortableCopyParallelBenchmarkHarness {

    private PortableCopyParallelBenchmarkHarness() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) {
            throw new IllegalArgumentException(
                    "Expected one disposable test-root argument"
            );
        }
        Path root = Path.of(arguments[0]).toAbsolutePath().normalize();
        byte[] small = filled(16 * 1_024);
        Path smallSource = createWorkload(root.resolve("small-source"), 1_000, small);
        Result smallResult = benchmark(root, "small", smallSource);
        deleteTree(smallSource);

        byte[] large = filled(8 * 1_024 * 1_024);
        Path largeSource = createWorkload(root.resolve("large-source"), 8, large);
        Result largeResult = benchmark(root, "large", largeSource);
        deleteTree(largeSource);

        System.out.printf(
                "portable-copy parallel benchmark threads=4 "
                        + "small-files=1000 small-sequential-ms=%d "
                        + "small-parallel-ms=%d large-files=8 "
                        + "large-sequential-ms=%d large-parallel-ms=%d%n",
                smallResult.sequentialMillis(),
                smallResult.parallelMillis(),
                largeResult.sequentialMillis(),
                largeResult.parallelMillis()
        );
    }

    private static Result benchmark(
            Path root,
            String name,
            Path source
    ) throws Exception {
        List<Path> files;
        try (var entries = Files.list(source)) {
            files = entries.sorted().toList();
        }
        Path sequentialRoot = root.resolve(name + "-sequential");
        InstanceDirectoryPreparer sequential =
                new InstanceDirectoryPreparer(
                        sequentialRoot,
                        root,
                        new PortableFileCopyOperation(),
                        Thread::sleep,
                        StorageStrategy.COPY,
                        new OverlayFsLayerManager(sequentialRoot, root),
                        1
                );
        InstanceDirectoryPreparer parallel =
                new InstanceDirectoryPreparer(
                        root.resolve(name + "-parallel"),
                        root
                );
        String instanceId = "bench.x82odk";
        long sequentialStart = System.nanoTime();
        Path sequentialTarget = sequential.prepare(instanceId, source);
        long sequentialMillis = elapsedMillis(sequentialStart);
        verify(files, sequentialTarget);
        sequential.delete(instanceId);

        long parallelStart = System.nanoTime();
        Path parallelTarget = parallel.prepare(instanceId, source);
        long parallelMillis = elapsedMillis(parallelStart);
        verify(files, parallelTarget);
        parallel.delete(instanceId);
        return new Result(sequentialMillis, parallelMillis);
    }

    private static void verify(List<Path> sources, Path target)
            throws Exception {
        for (Path source : sources) {
            if (Files.mismatch(
                    source,
                    target.resolve(source.getFileName())
            ) != -1) {
                throw new IllegalStateException(
                        "Copied content differs for " + source
                );
            }
        }
    }

    private static Path createWorkload(
            Path directory,
            int fileCount,
            byte[] content
    ) throws Exception {
        Files.createDirectory(directory);
        for (int index = 0; index < fileCount; index++) {
            Files.write(
                    directory.resolve(String.format("%05d.bin", index)),
                    content
            );
        }
        return directory;
    }

    private static byte[] filled(int size) {
        byte[] content = new byte[size];
        java.util.Arrays.fill(content, (byte) 0x5a);
        return content;
    }

    private static void deleteTree(Path directory) throws Exception {
        try (var paths = Files.walk(directory)) {
            for (Path path : paths
                    .sorted(Comparator.reverseOrder())
                    .toList()) {
                Files.delete(path);
            }
        }
    }

    private static long elapsedMillis(long startedNanos) {
        return TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - startedNanos
        );
    }

    private record Result(long sequentialMillis, long parallelMillis) {
    }
}
