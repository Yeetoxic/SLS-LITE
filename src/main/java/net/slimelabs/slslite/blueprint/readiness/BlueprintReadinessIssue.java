package net.slimelabs.slslite.blueprint.readiness;

import java.util.Objects;

public record BlueprintReadinessIssue(String code, BlueprintReadinessState state, String message) {
  public BlueprintReadinessIssue {
    Objects.requireNonNull(code, "code");
    Objects.requireNonNull(state, "state");
    Objects.requireNonNull(message, "message");
    if (state == BlueprintReadinessState.READY) {
      throw new IllegalArgumentException("A readiness issue cannot have READY state");
    }
  }
}
