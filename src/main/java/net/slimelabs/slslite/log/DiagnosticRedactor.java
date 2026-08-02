package net.slimelabs.slslite.log;

import java.nio.file.Path;
import java.util.List;
import java.util.regex.Pattern;

public final class DiagnosticRedactor {

  private static final Pattern BEARER =
      Pattern.compile("(?i)(authorization\\s*[:=]\\s*bearer\\s+)[^\\s,;]+");
  private static final Pattern ASSIGNMENT =
      Pattern.compile("(?i)(password|passwd|secret|token|api[-_]?key)(\\s*[:=]\\s*)[^\\s,;]+");
  private static final Pattern SLS_KEY = Pattern.compile("\\bsls_(?:live|test)_[A-Za-z0-9_-]+\\b");
  private static final Pattern WINDOWS_PATH =
      Pattern.compile("(?i)(?<![A-Za-z0-9])(?:[A-Z]:\\\\[^\\s,;]+)");
  private static final Pattern UNIX_PATH =
      Pattern.compile("(?<![A-Za-z0-9._-])/(?:home|root|srv|var|opt|mnt|run|tmp)/[^\\s,;]+");

  private final List<RootReplacement> roots;
  private final boolean redactPaths;

  public DiagnosticRedactor(Path dataDirectory, Path proxyDirectory, boolean redactPaths) {
    this.redactPaths = redactPaths;
    this.roots =
        List.of(replacement(dataDirectory, "<data>"), replacement(proxyDirectory, "<proxy>"));
  }

  public String redact(String value) {
    if (value == null || value.isEmpty()) {
      return "";
    }
    String redacted = SLS_KEY.matcher(value).replaceAll("<redacted-key>");
    redacted = BEARER.matcher(redacted).replaceAll("$1<redacted>");
    redacted = ASSIGNMENT.matcher(redacted).replaceAll("$1$2<redacted>");
    if (!redactPaths) {
      return redacted;
    }
    for (RootReplacement root : roots) {
      if (!root.path().isBlank()) {
        redacted = redacted.replace(root.path(), root.replacement());
        redacted = redacted.replace(root.path().replace('\\', '/'), root.replacement());
      }
    }
    redacted = WINDOWS_PATH.matcher(redacted).replaceAll("<absolute-path>");
    return UNIX_PATH.matcher(redacted).replaceAll("<absolute-path>");
  }

  private static RootReplacement replacement(Path path, String value) {
    return new RootReplacement(
        path == null ? "" : path.toAbsolutePath().normalize().toString(), value);
  }

  private record RootReplacement(String path, String replacement) {}
}
