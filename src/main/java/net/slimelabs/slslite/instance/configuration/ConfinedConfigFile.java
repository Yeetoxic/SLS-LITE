package net.slimelabs.slslite.instance.configuration;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import net.slimelabs.slslite.io.BoundedFileReader;

final class ConfinedConfigFile {

  static final int MAX_CONFIG_BYTES = 8 * 1024 * 1024;

  private ConfinedConfigFile() {}

  static Path resolve(Path instanceDirectory, String configuredTarget, String description)
      throws IOException {
    Path root = instanceDirectory.toAbsolutePath().normalize();
    if (Files.isSymbolicLink(root)) {
      throw new IOException("Instance directory is a symbolic link: " + root);
    }
    if (configuredTarget == null || configuredTarget.isBlank()) {
      throw new IOException(description + " target must not be blank");
    }
    Path relative = Path.of(configuredTarget).normalize();
    if (relative.toString().isBlank() || relative.isAbsolute() || relative.startsWith("..")) {
      throw new IOException(description + " target must stay inside the instance");
    }
    Path target = root.resolve(relative).normalize();
    if (!target.startsWith(root)) {
      throw new IOException(description + " target must stay inside the instance");
    }
    rejectSymbolicSegments(root, relative, description);
    Files.createDirectories(target.getParent());
    rejectSymbolicSegments(root, relative, description);
    return target;
  }

  static boolean existsRegular(Path target, String description) throws IOException {
    if (!Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
      return false;
    }
    if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
      throw new IOException(description + " target is not a regular file: " + target);
    }
    return true;
  }

  static InputStream openBounded(Path target) throws IOException {
    return BoundedFileReader.openNoFollow(target, MAX_CONFIG_BYTES);
  }

  static Path createTemporary(Path target) throws IOException {
    return Files.createTempFile(target.getParent(), "." + target.getFileName() + "-", ".tmp");
  }

  static Writer openTemporaryWriter(Path temporary) throws IOException {
    OutputStream output =
        Files.newOutputStream(
            temporary,
            StandardOpenOption.WRITE,
            StandardOpenOption.TRUNCATE_EXISTING,
            LinkOption.NOFOLLOW_LINKS);
    return new OutputStreamWriter(output, StandardCharsets.UTF_8);
  }

  static void requireBoundedOutput(Path temporary, Path target) throws IOException {
    if (Files.size(temporary) > MAX_CONFIG_BYTES) {
      throw new IOException("Patched config exceeds " + MAX_CONFIG_BYTES + " bytes: " + target);
    }
  }

  static void replace(Path temporary, Path target) throws IOException {
    try {
      Files.move(
          temporary, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
    } catch (AtomicMoveNotSupportedException ignored) {
      Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
    }
  }

  private static void rejectSymbolicSegments(Path root, Path relative, String description)
      throws IOException {
    Path current = root;
    for (Path segment : relative) {
      current = current.resolve(segment);
      if (Files.isSymbolicLink(current)) {
        throw new IOException(description + " path contains a symbolic link: " + current);
      }
    }
  }
}
