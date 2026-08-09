package net.slimelabs.slslite.api;

/** One bounded operator-facing status finding contributed by an extension. */
public record ExtensionDiagnosticFinding(
    String code, ExtensionDiagnosticSeverity severity, String message) {

  public ExtensionDiagnosticFinding {
    code =
        java.util.Objects.requireNonNull(code, "code").strip().toLowerCase(java.util.Locale.ROOT);
    severity = java.util.Objects.requireNonNull(severity, "severity");
    message = java.util.Objects.requireNonNull(message, "message").strip();
    if (!code.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
      throw new IllegalArgumentException("diagnostic code must match [a-z0-9][a-z0-9._-]{0,63}");
    }
    if (message.isEmpty()
        || message.length() > 512
        || message.indexOf('\n') >= 0
        || message.indexOf('\r') >= 0) {
      throw new IllegalArgumentException(
          "diagnostic message must be one line of 1 to 512 characters");
    }
  }
}
