package net.slimelabs.slslite.config;

/** Host-wide policy for provider-backed software installation. */
public record SoftwareConfig(boolean autoAcceptEula) {

  public static SoftwareConfig defaults() {
    return new SoftwareConfig(false);
  }
}
