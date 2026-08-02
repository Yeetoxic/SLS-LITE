package net.slimelabs.slslite.io;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;

/** Shared no-follow primitives for fixed files owned beneath one managed directory. */
public final class ConfinedFiles {

  private ConfinedFiles() {}

  public static Path ensureDirectory(Path directory) throws IOException {
    Path normalized = directory.toAbsolutePath().normalize();
    if (Files.exists(normalized, LinkOption.NOFOLLOW_LINKS)) {
      if (Files.isSymbolicLink(normalized)
          || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
        throw new IOException("Managed path is not a non-symbolic directory: " + normalized);
      }
      return normalized;
    }
    Files.createDirectories(normalized);
    if (Files.isSymbolicLink(normalized)
        || !Files.isDirectory(normalized, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("Managed path is not a non-symbolic directory: " + normalized);
    }
    return normalized;
  }

  public static void requireRegularFile(Path file) throws IOException {
    if (Files.isSymbolicLink(file) || !Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("Managed path is not a non-symbolic regular file: " + file);
    }
  }

  public static void atomicWrite(Path directory, String fileName, byte[] contents, int maximumBytes)
      throws IOException {
    java.util.Objects.requireNonNull(contents, "contents");
    if (contents.length > maximumBytes) {
      throw new IOException(
          "Managed file exceeds the " + maximumBytes + "-byte output limit: " + fileName);
    }
    Path managedDirectory = ensureDirectory(directory);
    Path destination = directChild(managedDirectory, fileName);
    rejectUnsafeDestination(destination);
    Path temporary = Files.createTempFile(managedDirectory, ".sls-lite-", ".tmp");
    try {
      Files.write(
          temporary,
          contents,
          StandardOpenOption.WRITE,
          StandardOpenOption.TRUNCATE_EXISTING,
          LinkOption.NOFOLLOW_LINKS);
      moveReplacing(temporary, destination);
    } finally {
      Files.deleteIfExists(temporary);
    }
  }

  public static void atomicCopy(
      Path directory, String fileName, InputStream input, int maximumBytes) throws IOException {
    if (maximumBytes <= 0 || maximumBytes == Integer.MAX_VALUE) {
      throw new IllegalArgumentException("maximumBytes must be positive and bounded");
    }
    byte[] contents;
    try (ByteArrayOutputStream output = new ByteArrayOutputStream()) {
      input.transferTo(new BoundedOutputStream(output, maximumBytes));
      contents = output.toByteArray();
    }
    atomicWrite(directory, fileName, contents, maximumBytes);
  }

  public static void atomicWriteProperties(
      Path directory,
      String fileName,
      java.util.Properties values,
      String comment,
      int maximumBytes)
      throws IOException {
    ByteArrayOutputStream output = new ByteArrayOutputStream();
    values.store(output, comment);
    atomicWrite(directory, fileName, output.toByteArray(), maximumBytes);
  }

  private static Path directChild(Path directory, String fileName) throws IOException {
    if (fileName == null || fileName.isBlank()) {
      throw new IOException("Managed file name must not be blank");
    }
    Path relative = Path.of(fileName);
    Path destination = directory.resolve(relative).normalize();
    if (relative.isAbsolute() || !directory.equals(destination.getParent())) {
      throw new IOException("Managed file must be a direct child: " + fileName);
    }
    return destination;
  }

  private static void rejectUnsafeDestination(Path destination) throws IOException {
    if (!Files.exists(destination, LinkOption.NOFOLLOW_LINKS)) {
      return;
    }
    requireRegularFile(destination);
  }

  private static void moveReplacing(Path source, Path destination) throws IOException {
    try {
      Files.move(
          source, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException exception) {
      Files.move(source, destination, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static final class BoundedOutputStream extends java.io.OutputStream {
    private final java.io.OutputStream delegate;
    private final int maximumBytes;
    private int written;

    private BoundedOutputStream(java.io.OutputStream delegate, int maximumBytes) {
      this.delegate = delegate;
      this.maximumBytes = maximumBytes;
    }

    @Override
    public void write(int value) throws IOException {
      requireCapacity(1);
      delegate.write(value);
      written++;
    }

    @Override
    public void write(byte[] values, int offset, int length) throws IOException {
      requireCapacity(length);
      delegate.write(values, offset, length);
      written += length;
    }

    private void requireCapacity(int additional) throws IOException {
      if (additional < 0 || written > maximumBytes - additional) {
        throw new IOException("Managed input exceeds the " + maximumBytes + "-byte limit");
      }
    }
  }
}
