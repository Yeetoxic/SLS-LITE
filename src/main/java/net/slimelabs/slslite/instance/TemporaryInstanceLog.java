package net.slimelabs.slslite.instance;

import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

final class TemporaryInstanceLog implements AutoCloseable {

    static final String RELATIVE_PATH = "logs/sls-lite-console.log";
    private static final byte[] TRUNCATION_MARKER =
            "[SLS-LITE] Temporary console log reached its configured limit.\n"
                    .getBytes(StandardCharsets.UTF_8);

    private final OutputStream output;
    private final long maximumBytes;
    private long writtenBytes;
    private int unflushedLines;
    private boolean capped;
    private boolean closed;

    TemporaryInstanceLog(Path instanceDirectory, int maximumKiB) throws IOException {
        Path path = instanceDirectory.resolve(RELATIVE_PATH);
        Files.createDirectories(path.getParent());
        output = new BufferedOutputStream(Files.newOutputStream(
                path,
                StandardOpenOption.CREATE,
                StandardOpenOption.TRUNCATE_EXISTING,
                StandardOpenOption.WRITE
        ));
        maximumBytes = maximumKiB * 1024L;
    }

    synchronized void append(String line) throws IOException {
        if (closed || capped) {
            return;
        }
        byte[] encoded = (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8);
        if (writtenBytes + encoded.length > maximumBytes) {
            writeMarkerWhenPossible();
            capped = true;
            output.flush();
            return;
        }

        output.write(encoded);
        writtenBytes += encoded.length;
        unflushedLines++;
        if (unflushedLines >= 32) {
            output.flush();
            unflushedLines = 0;
        }
    }

    synchronized long writtenBytes() {
        return writtenBytes;
    }

    synchronized boolean capped() {
        return capped;
    }

    @Override
    public synchronized void close() throws IOException {
        if (closed) {
            return;
        }
        closed = true;
        output.close();
    }

    private void writeMarkerWhenPossible() throws IOException {
        if (writtenBytes + TRUNCATION_MARKER.length <= maximumBytes) {
            output.write(TRUNCATION_MARKER);
            writtenBytes += TRUNCATION_MARKER.length;
        }
    }
}
