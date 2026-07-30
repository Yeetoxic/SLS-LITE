package net.slimelabs.slslite.instance.diagnostics;

import net.slimelabs.slslite.config.ManagedOutputConfig;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Optional;

public final class InstanceOutput {

    public static final String TEMPORARY_RELATIVE_PATH =
            TemporaryInstanceLog.RELATIVE_PATH;

    private final Path instanceDirectory;
    private final InstanceLogBuffer logs = new InstanceLogBuffer();

    private volatile ManagedOutputConfig config =
            new ManagedOutputConfig(false, false, 4096);
    private volatile TemporaryInstanceLog temporaryLog;
    private volatile IOException failure;
    private volatile boolean temporaryOutputDisabled;

    public InstanceOutput(Path instanceDirectory) {
        this.instanceDirectory = instanceDirectory.toAbsolutePath().normalize();
    }

    public void configure(ManagedOutputConfig nextConfig) throws IOException {
        config = java.util.Objects.requireNonNull(nextConfig, "nextConfig");
        if (nextConfig.writeTemporaryFile()) {
            temporaryLog = new TemporaryInstanceLog(
                    instanceDirectory,
                    nextConfig.temporaryFileMaxKiB()
            );
        }
    }

    public void append(String line) {
        logs.append(line);
        TemporaryInstanceLog current = temporaryLog;
        if (current != null && !temporaryOutputDisabled) {
            try {
                current.append(line);
            } catch (IOException exception) {
                temporaryOutputDisabled = true;
                failure = exception;
            }
        }
    }

    public InstanceLogPage page(int page, int linesPerPage) {
        return logs.page(page, linesPerPage);
    }

    public int retainedLines() {
        return logs.size();
    }

    public int retentionCapacity() {
        return InstanceLogBuffer.CAPACITY;
    }

    public boolean mirrorsToProxyConsole() {
        return config.mirrorToProxyConsole();
    }

    public boolean writesTemporaryFile() {
        return config.writeTemporaryFile();
    }

    public Optional<Path> temporaryFilePath() {
        return writesTemporaryFile()
                ? Optional.of(instanceDirectory.resolve(
                        TEMPORARY_RELATIVE_PATH
                ))
                : Optional.empty();
    }

    public Optional<IOException> takeFailure() {
        IOException current = failure;
        failure = null;
        return Optional.ofNullable(current);
    }

    public void close() {
        TemporaryInstanceLog current = temporaryLog;
        temporaryLog = null;
        if (current != null) {
            try {
                current.close();
            } catch (IOException exception) {
                failure = exception;
            }
        }
    }
}
