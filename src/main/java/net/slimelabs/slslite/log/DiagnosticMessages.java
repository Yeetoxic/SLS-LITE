package net.slimelabs.slslite.log;

/** Bounded mandatory redaction for messages crossing a console/chat diagnostic boundary. */
public final class DiagnosticMessages {

  private static final int MAX_CHARACTERS = 512;
  private static final DiagnosticRedactor REDACTOR = new DiagnosticRedactor(null, null, true);

  private DiagnosticMessages() {}

  public static String safe(String message) {
    String value = REDACTOR.redact(message).replace('\r', ' ').replace('\n', ' ').strip();
    if (value.isEmpty()) {
      return "operation failed without further detail";
    }
    return value.length() <= MAX_CHARACTERS
        ? value
        : value.substring(0, MAX_CHARACTERS) + "...[truncated]";
  }

  public static String rootCause(Throwable failure) {
    Throwable current = java.util.Objects.requireNonNull(failure, "failure");
    while (current.getCause() != null) {
      current = current.getCause();
    }
    return safe(
        current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage());
  }
}
