package net.slimelabs.slslite.config;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

public record DefinitionReloadReport(
    CatalogDelta blueprints,
    CatalogDelta software,
    int acceptedBlueprints,
    List<BlueprintRejection> rejectedBlueprints,
    List<String> affectedBlueprints) {

  public DefinitionReloadReport {
    if (acceptedBlueprints < 0) {
      throw new IllegalArgumentException("accepted blueprint count must not be negative");
    }
    rejectedBlueprints = List.copyOf(rejectedBlueprints);
    affectedBlueprints = List.copyOf(affectedBlueprints);
  }

  public record BlueprintRejection(String path, String error) {
    public BlueprintRejection {
      java.util.Objects.requireNonNull(path, "path");
      java.util.Objects.requireNonNull(error, "error");
    }
  }

  public record CatalogDelta(List<String> added, List<String> updated, List<String> removed) {

    public CatalogDelta {
      added = List.copyOf(added);
      updated = List.copyOf(updated);
      removed = List.copyOf(removed);
    }

    public int changedCount() {
      return added.size() + updated.size() + removed.size();
    }

    public String summary() {
      return added.size()
          + " added, "
          + updated.size()
          + " updated, "
          + removed.size()
          + " removed";
    }
  }

  static CatalogDelta delta(Map<String, ?> before, Map<String, ?> after) {
    List<String> added = new ArrayList<>();
    List<String> updated = new ArrayList<>();
    List<String> removed = new ArrayList<>();
    for (Map.Entry<String, ?> entry : after.entrySet()) {
      if (!before.containsKey(entry.getKey())) {
        added.add(entry.getKey());
      } else if (!java.util.Objects.equals(before.get(entry.getKey()), entry.getValue())) {
        updated.add(entry.getKey());
      }
    }
    for (String id : before.keySet()) {
      if (!after.containsKey(id)) {
        removed.add(id);
      }
    }
    added.sort(String::compareTo);
    updated.sort(String::compareTo);
    removed.sort(String::compareTo);
    return new CatalogDelta(added, updated, removed);
  }
}
