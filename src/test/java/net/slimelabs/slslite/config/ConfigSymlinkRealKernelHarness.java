package net.slimelabs.slslite.config;

import java.nio.file.Files;
import java.nio.file.Path;

/** Opt-in Linux gate proving that config.yml is never loaded through a symbolic link. */
public final class ConfigSymlinkRealKernelHarness {

  private ConfigSymlinkRealKernelHarness() {}

  public static void main(String[] arguments) throws Exception {
    if (arguments.length != 1) {
      throw new IllegalArgumentException("Expected one empty disposable directory");
    }
    Path root = Path.of(arguments[0]).toAbsolutePath().normalize();
    Files.createDirectories(root);
    try (var entries = Files.list(root)) {
      if (entries.findAny().isPresent()) {
        throw new IllegalArgumentException("Disposable directory must be empty: " + root);
      }
    }

    Path target = root.resolve("actual.yml");
    Files.writeString(target, "resources:\n  total_memory_mib: 2048\n");
    Files.createSymbolicLink(root.resolve("config.yml"), target.getFileName());

    try {
      new SLSConfigRepository(root).reload();
      throw new IllegalStateException("Symbolic-link configuration was unexpectedly loaded");
    } catch (ConfigurationException expected) {
      System.out.println("SLS-LITE config symlink rejection PASS");
    }
  }
}
