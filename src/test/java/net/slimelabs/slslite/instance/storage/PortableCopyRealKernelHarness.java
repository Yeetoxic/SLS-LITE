package net.slimelabs.slslite.instance.storage;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Opt-in sparse-copy allocation benchmark for disposable Linux storage.
 */
public final class PortableCopyRealKernelHarness {

    private PortableCopyRealKernelHarness() {
    }

    public static void main(String[] arguments) throws Exception {
        if (arguments.length != 1) {
            throw new IllegalArgumentException(
                    "Expected one disposable test-root argument"
            );
        }
        Path root = Path.of(arguments[0]).toAbsolutePath().normalize();
        Path source = root.resolve("sparse-source");
        Path ordinaryTarget = root.resolve("ordinary-copy");
        Path sparseTarget = root.resolve("sparse-aware-copy");
        long logicalBytes = 64L << 20;
        try (FileChannel channel = FileChannel.open(
                source,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
        )) {
            channel.write(ByteBuffer.wrap("first".getBytes()));
            channel.position(logicalBytes - 4);
            channel.write(ByteBuffer.wrap("last".getBytes()));
        }

        long ordinaryStart = System.nanoTime();
        Files.copy(source, ordinaryTarget);
        long ordinaryMillis = elapsedMillis(ordinaryStart);
        long sparseStart = System.nanoTime();
        new PortableFileCopyOperation().copy(source, sparseTarget);
        long sparseMillis = elapsedMillis(sparseStart);

        long sourceBytes = allocatedBytes(source);
        long ordinaryBytes = allocatedBytes(ordinaryTarget);
        long sparseBytes = allocatedBytes(sparseTarget);
        if (Files.mismatch(source, sparseTarget) != -1) {
            throw new IllegalStateException("Sparse-aware copy content differs");
        }
        if (sparseBytes >= logicalBytes / 4) {
            throw new IllegalStateException(
                    "Sparse-aware target allocated too much: " + sparseBytes
            );
        }
        Files.delete(source);
        Files.delete(ordinaryTarget);
        Files.delete(sparseTarget);

        Path denseSource = root.resolve("dense-source");
        Path denseOrdinary = root.resolve("dense-ordinary");
        Path densePortable = root.resolve("dense-portable");
        byte[] denseBlock = new byte[1 << 20];
        java.util.Arrays.fill(denseBlock, (byte) 0x5a);
        try (FileChannel channel = FileChannel.open(
                denseSource,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
        )) {
            for (int index = 0; index < 32; index++) {
                channel.write(ByteBuffer.wrap(denseBlock));
            }
        }
        long denseOrdinaryStart = System.nanoTime();
        Files.copy(denseSource, denseOrdinary);
        long denseOrdinaryMillis = elapsedMillis(denseOrdinaryStart);
        long densePortableStart = System.nanoTime();
        new PortableFileCopyOperation().copy(denseSource, densePortable);
        long densePortableMillis = elapsedMillis(densePortableStart);
        if (Files.mismatch(denseSource, densePortable) != -1) {
            throw new IllegalStateException("Dense portable copy content differs");
        }
        System.out.printf(
                "portable-copy sparse PASS logical=%d source=%d ordinary=%d "
                        + "sparse-aware=%d ordinary-ms=%d sparse-ms=%d "
                        + "dense-ordinary-ms=%d dense-portable-ms=%d%n",
                logicalBytes,
                sourceBytes,
                ordinaryBytes,
                sparseBytes,
                ordinaryMillis,
                sparseMillis,
                denseOrdinaryMillis,
                densePortableMillis
        );
    }

    private static long allocatedBytes(Path path) throws Exception {
        Process process = new ProcessBuilder(
                "stat",
                "-c",
                "%b",
                path.toString()
        )
                .redirectError(ProcessBuilder.Redirect.INHERIT)
                .start();
        String output = new String(
                process.getInputStream().readAllBytes(),
                java.nio.charset.StandardCharsets.UTF_8
        ).strip();
        if (process.waitFor() != 0) {
            throw new IllegalStateException("stat failed for " + path);
        }
        return Long.parseLong(output) * 512L;
    }

    private static long elapsedMillis(long startedNanos) {
        return java.util.concurrent.TimeUnit.NANOSECONDS.toMillis(
                System.nanoTime() - startedNanos
        );
    }
}
