package net.slimelabs.slslite.host;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.TimeUnit;

class BtrfsSubvolumeOperations {

  private static final long COMMAND_TIMEOUT_SECONDS = 30;

  boolean available() {
    try {
      run(List.of("btrfs", "--version"));
      return true;
    } catch (IOException exception) {
      return false;
    }
  }

  boolean isSubvolume(Path path) throws IOException {
    if (!Files.isDirectory(path)) {
      return false;
    }
    if (!"btrfs".equalsIgnoreCase(Files.getFileStore(path).type())) {
      return false;
    }
    try {
      Object inode = Files.getAttribute(path, "unix:ino");
      return inode instanceof Number number && number.longValue() == 256L;
    } catch (UnsupportedOperationException exception) {
      return false;
    }
  }

  void create(Path path) throws IOException {
    run(List.of("btrfs", "subvolume", "create", path.toString()));
  }

  void snapshot(Path source, Path target) throws IOException {
    run(List.of("btrfs", "subvolume", "snapshot", source.toString(), target.toString()));
  }

  void delete(Path path) throws IOException {
    run(List.of("btrfs", "subvolume", "delete", path.toString()));
  }

  private static void run(List<String> command) throws IOException {
    int result = exitCode(command);
    if (result != 0) {
      throw new IOException(
          String.join(" ", command.subList(0, 3)) + " exited with code " + result);
    }
  }

  private static int exitCode(List<String> command) throws IOException {
    Process process = null;
    try {
      process =
          new ProcessBuilder(command)
              .redirectOutput(ProcessBuilder.Redirect.DISCARD)
              .redirectError(ProcessBuilder.Redirect.DISCARD)
              .start();
      if (!process.waitFor(COMMAND_TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
        process.destroyForcibly();
        throw new IOException(
            String.join(" ", command.subList(0, Math.min(3, command.size()))) + " timed out");
      }
      return process.exitValue();
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new IOException("Btrfs command was interrupted", exception);
    } finally {
      if (process != null && process.isAlive()) {
        process.destroyForcibly();
      }
    }
  }
}
