package net.slimelabs.slslite.blueprint;

/**
 * A single-writer file whose canonical copy lives below the SLS-LITE content root.
 *
 * <p>The file is copied into an assembled instance and published atomically back to its source
 * after the managed process stops. It is intentionally distinct from directory volumes and
 * one-way {@link BlueprintCopy} seeds.
 */
public record BlueprintPersistentFile(String name, String source, String target) {

  public BlueprintPersistentFile {
    name = requireText(name, "name");
    source = requireText(source, "source");
    target = requireText(target, "target");
  }

  private static String requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException("Persistent file " + field + " must not be blank");
    }
    return value.trim();
  }
}
