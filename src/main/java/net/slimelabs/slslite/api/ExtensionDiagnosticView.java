package net.slimelabs.slslite.api;

import java.time.Instant;
import java.util.List;

/** Immutable result of evaluating one namespaced extension diagnostic contributor. */
public record ExtensionDiagnosticView(
    String namespace, Instant inspectedAt, List<ExtensionDiagnosticFinding> findings) {

  public ExtensionDiagnosticView {
    if (namespace == null || !namespace.matches("[a-z][a-z0-9._-]{0,63}")) {
      throw new IllegalArgumentException("namespace must match [a-z][a-z0-9._-]{0,63}");
    }
    inspectedAt = java.util.Objects.requireNonNull(inspectedAt, "inspectedAt");
    findings = List.copyOf(findings);
    if (findings.size() > 16) {
      throw new IllegalArgumentException("extension diagnostics may contain at most 16 findings");
    }
  }
}
