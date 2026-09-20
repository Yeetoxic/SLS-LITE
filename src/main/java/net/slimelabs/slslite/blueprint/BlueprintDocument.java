package net.slimelabs.slslite.blueprint;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;

/**
 * Parsed blueprint before mixin includes are applied.
 */
record BlueprintDocument(
    Path source,
    String id,
    String name,
    String type,
    List<String> includes,
    Overlay overlay,
    boolean save) {

  BlueprintDocument {
    Objects.requireNonNull(source, "source");
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("Blueprint id must not be blank");
    }
    id = id.trim();
    name = Objects.requireNonNull(name, "name").trim();
    type = Objects.requireNonNull(type, "type").trim();
    includes = List.copyOf(includes);
    overlay = overlay == null ? Overlay.empty() : overlay;
  }
}
