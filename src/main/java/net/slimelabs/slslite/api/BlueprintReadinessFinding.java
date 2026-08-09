package net.slimelabs.slslite.api;

import java.util.Objects;

/** Bounded operator-facing readiness problem contributed by one extension. */
public record BlueprintReadinessFinding(
    String code, BlueprintReadinessStatus status, String message) {

  public BlueprintReadinessFinding {
    code = Objects.requireNonNull(code, "code").strip().toLowerCase(java.util.Locale.ROOT);
    status = Objects.requireNonNull(status, "status");
    message = Objects.requireNonNull(message, "message").strip();
    if (!code.matches("[a-z0-9][a-z0-9._-]{0,63}")) {
      throw new IllegalArgumentException(
          "Blueprint readiness finding code must match [a-z0-9][a-z0-9._-]{0,63}");
    }
    if (message.isEmpty() || message.length() > 512 || message.indexOf('\n') >= 0) {
      throw new IllegalArgumentException(
          "Blueprint readiness finding message must be one line of 1 to 512 characters");
    }
  }
}
