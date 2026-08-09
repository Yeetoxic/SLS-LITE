package net.slimelabs.slslite.blueprint.readiness;

import java.util.List;
import java.util.Objects;

public record BlueprintReadinessReport(
    String blueprintId, BlueprintReadinessState state, List<BlueprintReadinessIssue> issues) {
  public BlueprintReadinessReport {
    Objects.requireNonNull(blueprintId, "blueprintId");
    Objects.requireNonNull(state, "state");
    issues = List.copyOf(issues);
    if ((state == BlueprintReadinessState.READY) != issues.isEmpty()) {
      throw new IllegalArgumentException(
          "READY reports must be empty and non-ready reports need issues");
    }
  }

  public String conciseReason() {
    return issues.isEmpty() ? "ready" : issues.get(0).message();
  }
}
