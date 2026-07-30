package net.slimelabs.slslite.instance.storage;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class PortableFileCopyOperationTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void copiesOrdinaryFileWithoutChangingSource() throws Exception {
        Path source = temporaryDirectory.resolve("source");
        Path target = temporaryDirectory.resolve("target");
        Files.writeString(source, "source");

        new PortableFileCopyOperation().copy(source, target);
        Files.writeString(target, "target");

        assertEquals("source", Files.readString(source));
        assertEquals("target", Files.readString(target));
    }

    @Test
    void preservesLogicalContentAcrossLargeZeroRuns() throws Exception {
        Path source = temporaryDirectory.resolve("source");
        Path target = temporaryDirectory.resolve("target");
        byte[] first = "first".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        byte[] last = "last".getBytes(java.nio.charset.StandardCharsets.UTF_8);
        long size = 16L << 20;
        try (FileChannel channel = FileChannel.open(
                source,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
        )) {
            channel.write(ByteBuffer.wrap(first));
            channel.position(size - last.length);
            channel.write(ByteBuffer.wrap(last));
        }

        new PortableFileCopyOperation().copy(source, target);

        assertEquals(size, Files.size(target));
        assertArrayEquals(first, read(target, 0, first.length));
        assertArrayEquals(last, read(target, size - last.length, last.length));
        assertArrayEquals(new byte[32], read(target, size / 2, 32));
    }

    @Test
    void refusesToOverwriteExistingTarget() throws Exception {
        Path source = temporaryDirectory.resolve("source");
        Path target = temporaryDirectory.resolve("target");
        Files.writeString(source, "source");
        Files.writeString(target, "existing");

        assertThrows(
                java.io.IOException.class,
                () -> new PortableFileCopyOperation().copy(source, target)
        );
        assertEquals("existing", Files.readString(target));
    }

    private static byte[] read(Path file, long offset, int length)
            throws Exception {
        ByteBuffer buffer = ByteBuffer.allocate(length);
        try (FileChannel channel = FileChannel.open(
                file,
                StandardOpenOption.READ
        )) {
            while (buffer.hasRemaining()) {
                int read = channel.read(buffer, offset + buffer.position());
                if (read < 0) {
                    break;
                }
            }
        }
        return buffer.array();
    }
}
