package net.slimelabs.slslite.blueprint;

public record BlueprintCopy(String source, String target) {

  public BlueprintCopy {
    if (source == null || source.isBlank()) {
      throw new IllegalArgumentException("Copy source must not be blank");
    }
    if (target == null || target.isBlank()) {
      throw new IllegalArgumentException("Copy target must not be blank");
    }
    source = source.trim();
    target = target.trim();
  }
}
