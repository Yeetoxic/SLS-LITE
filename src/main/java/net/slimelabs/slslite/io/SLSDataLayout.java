package net.slimelabs.slslite.io;

import java.io.IOException;
import java.nio.file.Path;
import java.util.List;

/** Creates the stable operator-facing directories below the SLS-LITE data directory. */
public final class SLSDataLayout {

  private static final List<Path> OPERATOR_DIRECTORIES =
      List.of(Path.of("volumes"), Path.of("volumes", "worlds"), Path.of("volumes", "plugins"));

  private SLSDataLayout() {}

  /**
   * Creates missing operator directories without modifying their contents.
   *
   * <p>Each path component is checked independently so an existing symbolic link cannot redirect
   * managed directory creation outside the plugin data directory.
   */
  public static void initialize(Path dataDirectory) throws IOException {
    Path root = ConfinedFiles.ensureDirectory(dataDirectory);
    for (Path relativeDirectory : OPERATOR_DIRECTORIES) {
      Path current = root;
      for (Path component : relativeDirectory) {
        current = ConfinedFiles.ensureDirectory(current.resolve(component));
      }
    }
  }
}
