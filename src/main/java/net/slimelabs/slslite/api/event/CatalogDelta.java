package net.slimelabs.slslite.api.event;

/**
 * Bounded counts describing changes committed for one definition family.
 *
 * @param added definitions newly added
 * @param updated existing definitions changed
 * @param removed definitions removed
 */
public record CatalogDelta(int added, int updated, int removed) {

  public CatalogDelta {
    if (added < 0 || updated < 0 || removed < 0) {
      throw new IllegalArgumentException("catalog delta counts must not be negative");
    }
  }

  public int changed() {
    return added + updated + removed;
  }
}
