package net.slimelabs.slslite.api;

import java.util.List;

/** Read-only extension status callback evaluated by SLS-LITE on a bounded worker. */
@FunctionalInterface
public interface ExtensionDiagnosticContributor {

  /** Returns a bounded snapshot of current findings. */
  List<ExtensionDiagnosticFinding> inspect();
}
