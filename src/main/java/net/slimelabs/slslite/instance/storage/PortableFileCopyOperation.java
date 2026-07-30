package net.slimelabs.slslite.instance.storage;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/**
 * Portable copy with conservative sparse-file preservation.
 */
final class PortableFileCopyOperation
        implements InstanceDirectoryPreparer.FileCopyOperation {

    private static final long MINIMUM_SPARSE_CANDIDATE_BYTES = 1L << 20;
    private static final int SAMPLE_COUNT = 8;
    private static final int SAMPLE_BYTES = 4 * 1_024;
    private static final int COPY_BUFFER_BYTES = 1 << 20;

    @Override
    public void copy(Path source, Path target) throws IOException {
        if (Files.exists(target)) {
            throw new FileAlreadyExistsException(target.toString());
        }
        long size = Files.size(source);
        if (isWindows()
                || size < MINIMUM_SPARSE_CANDIDATE_BYTES
                || !containsSampledZeroRun(source, size)) {
            Files.copy(source, target);
            return;
        }
        try {
            copySparse(source, target, size);
        } catch (IOException | RuntimeException exception) {
            try {
                Files.deleteIfExists(target);
            } catch (IOException cleanupFailure) {
                exception.addSuppressed(cleanupFailure);
            }
            throw exception;
        }
    }

    private static boolean containsSampledZeroRun(Path source, long size)
            throws IOException {
        int sampleLength = (int) Math.min(SAMPLE_BYTES, size);
        long finalOffset = size - sampleLength;
        ByteBuffer sample = ByteBuffer.allocate(sampleLength);
        try (FileChannel input = FileChannel.open(
                source,
                StandardOpenOption.READ
        )) {
            for (int sampleIndex = 0;
                    sampleIndex < SAMPLE_COUNT;
                    sampleIndex++) {
                long offset = SAMPLE_COUNT == 1
                        ? 0
                        : finalOffset * sampleIndex / (SAMPLE_COUNT - 1);
                sample.clear();
                int read = readAtMost(input, sample, offset);
                if (read == sampleLength
                        && allZero(sample.array(), read)) {
                    return true;
                }
            }
        }
        return false;
    }

    private static void copySparse(Path source, Path target, long sourceSize)
            throws IOException {
        byte[] bytes = new byte[COPY_BUFFER_BYTES];
        ByteBuffer buffer = ByteBuffer.wrap(bytes);
        try (FileChannel input = FileChannel.open(
                    source,
                    StandardOpenOption.READ
                );
                FileChannel output = FileChannel.open(
                    target,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
                )) {
            while (true) {
                buffer.clear();
                int read = input.read(buffer);
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    continue;
                }
                if (allZero(bytes, read)) {
                    output.position(output.position() + read);
                    continue;
                }
                buffer.flip();
                while (buffer.hasRemaining()) {
                    output.write(buffer);
                }
            }
            if (input.size() != sourceSize) {
                throw new IOException(
                        "Source size changed during sparse copy: " + source
                );
            }
            if (sourceSize > 0 && output.size() < sourceSize) {
                output.position(sourceSize - 1);
                output.write(ByteBuffer.wrap(new byte[] {0}));
            }
        }
    }

    private static int readAtMost(
            FileChannel input,
            ByteBuffer target,
            long offset
    ) throws IOException {
        int total = 0;
        while (target.hasRemaining()) {
            int read = input.read(target, offset + total);
            if (read < 0) {
                break;
            }
            if (read == 0) {
                break;
            }
            total += read;
        }
        return total;
    }

    private static boolean allZero(byte[] bytes, int length) {
        for (int index = 0; index < length; index++) {
            if (bytes[index] != 0) {
                return false;
            }
        }
        return true;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name", "")
                .toLowerCase(java.util.Locale.ROOT)
                .contains("windows");
    }
}
