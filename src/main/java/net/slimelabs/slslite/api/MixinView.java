package net.slimelabs.slslite.api;

import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Immutable, implementation-independent mixin view.
 *
 * <p>Fields reflect the mixin after {@code extends} chains are flattened. {@code extendsMixins} is
 * the declared parent list. Software and version may be absent because mixins are partial overlays.
 *
 * @param id stable mixin identifier
 * @param description optional operator-facing note; empty when omitted
 * @param extendsMixins declared parent mixin identifiers
 * @param software optional server software overlay
 * @param version optional software version overlay
 * @param volumes immutable configured volume mappings
 * @param hasCopies whether the mixin declares copy mappings
 * @param environmentVariables names of configured environment variables; values are never exposed
 * @param annotations deeply immutable extension-owned annotation data
 */
public record MixinView(
    String id,
    String description,
    List<String> extendsMixins,
    String software,
    String version,
    List<VolumeView> volumes,
    boolean hasCopies,
    Set<String> environmentVariables,
    Map<String, Object> annotations) {

  public MixinView {
    id = requireText(id, "id");
    description = description == null ? "" : description;
    extendsMixins = List.copyOf(extendsMixins);
    software = blankToNull(software);
    version = blankToNull(version);
    volumes = List.copyOf(volumes);
    environmentVariables = Set.copyOf(environmentVariables);
    annotations = NamespacedAnnotations.immutableValues(annotations);
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Mixin " + field + " must not be blank");
    }
    return value;
  }

  private static String blankToNull(String value) {
    return value == null || value.isBlank() ? null : value;
  }
}
