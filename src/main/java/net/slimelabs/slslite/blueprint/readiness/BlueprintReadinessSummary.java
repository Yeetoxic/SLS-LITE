package net.slimelabs.slslite.blueprint.readiness;

public record BlueprintReadinessSummary(int ready, int actionNeeded, int temporarilyUnavailable) {
  public BlueprintReadinessSummary {
    if (ready < 0 || actionNeeded < 0 || temporarilyUnavailable < 0) {
      throw new IllegalArgumentException("Readiness counts must not be negative");
    }
  }

  public int total() {
    return ready + actionNeeded + temporarilyUnavailable;
  }
}
