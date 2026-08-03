package net.slimelabs.slslite.api;

/** Stable API failure with a machine-readable category and safe operator message. */
public final class SLSLiteApiException extends RuntimeException {

  private final Code code;

  public SLSLiteApiException(Code code, String message) {
    super(message);
    this.code = java.util.Objects.requireNonNull(code, "code");
  }

  public Code code() {
    return code;
  }

  public enum Code {
    NOT_READY,
    CLOSED,
    NOT_FOUND,
    CONFLICT,
    REJECTED,
    PLAYER_OFFLINE,
    INTERNAL
  }
}
