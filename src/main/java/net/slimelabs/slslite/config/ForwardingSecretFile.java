package net.slimelabs.slslite.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import net.slimelabs.slslite.io.BoundedFileReader;

/** Reads a Velocity forwarding secret without following links or accepting unbounded input. */
public final class ForwardingSecretFile {

  public static final int MAXIMUM_CHARACTERS = 4096;
  public static final int MAXIMUM_BYTES = 16 * 1024;

  private ForwardingSecretFile() {}

  public static String read(Path path) throws IOException {
    if (Files.isSymbolicLink(path) || !Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException("must be a regular non-symbolic file");
    }
    String secret;
    try {
      secret =
          BoundedFileReader.readStringNoFollow(path, StandardCharsets.UTF_8, MAXIMUM_BYTES).trim();
    } catch (IOException exception) {
      throw new IOException("cannot be read safely", exception);
    }
    if (secret.isEmpty()) {
      throw new IOException("is empty");
    }
    if (secret.length() > MAXIMUM_CHARACTERS) {
      throw new IOException("exceeds " + MAXIMUM_CHARACTERS + " characters");
    }
    return secret;
  }
}
