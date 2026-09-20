package net.slimelabs.slslite.blueprint;

import java.util.List;
import java.util.Objects;

/**
 * Mixins are reusable configuration overlays that blueprints can pull in with includes
 */
public record Mixin(String id, String description, List<String> extendsMixins, Overlay overlay) {

  public Mixin {
    if (id == null || id.isBlank()) {
      throw new IllegalArgumentException("Mixin id must not be blank");
    }
    id = id.trim();
    description = description == null || description.isBlank() ? "" : description.trim();
    extendsMixins = List.copyOf(extendsMixins);
    Objects.requireNonNull(overlay, "overlay");
  }

  public Mixin withOverlay(Overlay resolved) {
    return new Mixin(id, description, extendsMixins, resolved);
  }
}
