package net.slimelabs.slslite.io;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

public final class BoundedFileReader {

  private BoundedFileReader() {}

  public static InputStream open(Path path, int maximumBytes) throws IOException {
    return new ByteArrayInputStream(read(path, maximumBytes));
  }

  public static String readString(Path path, Charset charset, int maximumBytes) throws IOException {
    return new String(read(path, maximumBytes), charset);
  }

  public static InputStream openNoFollow(Path path, int maximumBytes) throws IOException {
    return new ByteArrayInputStream(readNoFollow(path, maximumBytes));
  }

  public static String readStringNoFollow(Path path, Charset charset, int maximumBytes)
      throws IOException {
    return new String(readNoFollow(path, maximumBytes), charset);
  }

  private static byte[] read(Path path, int maximumBytes) throws IOException {
    validateLimit(maximumBytes);
    long declaredSize = Files.size(path);
    if (declaredSize > maximumBytes) {
      throw tooLarge(path, maximumBytes);
    }
    try (InputStream input = Files.newInputStream(path)) {
      return readBounded(path, input, maximumBytes);
    }
  }

  private static byte[] readNoFollow(Path path, int maximumBytes) throws IOException {
    validateLimit(maximumBytes);
    try (SeekableByteChannel channel =
            Files.newByteChannel(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        InputStream input = Channels.newInputStream(channel)) {
      if (channel.size() > maximumBytes) {
        throw tooLarge(path, maximumBytes);
      }
      return readBounded(path, input, maximumBytes);
    }
  }

  private static byte[] readBounded(Path path, InputStream input, int maximumBytes)
      throws IOException {
    byte[] contents = input.readNBytes(maximumBytes + 1);
    if (contents.length > maximumBytes) {
      throw tooLarge(path, maximumBytes);
    }
    return contents;
  }

  private static void validateLimit(int maximumBytes) {
    if (maximumBytes <= 0 || maximumBytes == Integer.MAX_VALUE) {
      throw new IllegalArgumentException(
          "maximumBytes must be positive and smaller than Integer.MAX_VALUE");
    }
  }

  private static IOException tooLarge(Path path, int maximumBytes) {
    return new IOException("File exceeds the " + maximumBytes + "-byte input limit: " + path);
  }
}
