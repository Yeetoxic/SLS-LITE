package net.slimelabs.slslite.api;

/** Immutable public description of one blueprint volume. */
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
