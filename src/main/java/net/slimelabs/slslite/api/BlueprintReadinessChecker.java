package net.slimelabs.slslite.api;

import java.util.List;

/** Non-blocking extension check for one blueprint carrying the extension's annotation namespace. */
@FunctionalInterface
public interface BlueprintReadinessChecker {

  /**
   * Inspects immutable blueprint data without changing external state.
   *
   * @return at most eight findings; an empty list means this extension is ready
   */
  List<BlueprintReadinessFinding> check(BlueprintView blueprint, NamespacedAnnotations annotations);
}
