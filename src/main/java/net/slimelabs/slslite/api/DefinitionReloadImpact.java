package net.slimelabs.slslite.api;

/** Bounded impact summary for one committed definition reload. */
public record DefinitionReloadImpact(
    int affectedBlueprints, int runningInstances, int persistentInstances, String nextAction) {

  public DefinitionReloadImpact {
    if (affectedBlueprints < 0 || runningInstances < 0 || persistentInstances < 0) {
      throw new IllegalArgumentException("definition impact counts must not be negative");
    }
    nextAction = java.util.Objects.requireNonNull(nextAction, "nextAction").strip();
    if (nextAction.isEmpty()
        || nextAction.length() > 512
        || nextAction.indexOf('\n') >= 0
        || nextAction.indexOf('\r') >= 0) {
      throw new IllegalArgumentException("nextAction must be one line of 1 to 512 characters");
    }
  }
}
