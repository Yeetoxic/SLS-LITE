package net.slimelabs.slslite.io;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.nio.file.Path;

public final class BoundedFileReader {

  private BoundedFileReader() {}

  public static InputStream open(Path path, int maximumBytes) throws IOException {
    return new ByteArrayInputStream(read(path, maximumBytes));
  }

  public static String readString(Path path, Charset charset, int maximumBytes) throws IOException {
    return new String(read(path, maximumBytes), charset);
  }

  private static byte[] read(Path path, int maximumBytes) throws IOException {
    if (maximumBytes <= 0 || maximumBytes == Integer.MAX_VALUE) {
      throw new IllegalArgumentException(
          "maximumBytes must be positive and smaller than Integer.MAX_VALUE");
    }
    long declaredSize = Files.size(path);
    if (declaredSize > maximumBytes) {
      throw tooLarge(path, maximumBytes);
    }
    try (InputStream input = Files.newInputStream(path)) {
      byte[] contents = input.readNBytes(maximumBytes + 1);
      if (contents.length > maximumBytes) {
        throw tooLarge(path, maximumBytes);
      }
      return contents;
    }
  }

  private static IOException tooLarge(Path path, int maximumBytes) {
    return new IOException("File exceeds the " + maximumBytes + "-byte input limit: " + path);
  }
}
