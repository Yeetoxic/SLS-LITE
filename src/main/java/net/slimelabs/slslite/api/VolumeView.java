package net.slimelabs.slslite.api;

/**
 * Immutable public description of one blueprint volume.
 *
 * @param name configured volume name
 * @param source operator-relative source path
 * @param target instance-relative target path
 * @param mode normalized mapping mode
 */
public record VolumeView(String name, String source, String target, String mode) {

  public VolumeView {
    name = requireText(name, "name");
    source = requireText(source, "source");
    target = requireText(target, "target");
    mode = requireText(mode, "mode");
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Volume " + field + " must not be blank");
    }
    return value;
  }
}
