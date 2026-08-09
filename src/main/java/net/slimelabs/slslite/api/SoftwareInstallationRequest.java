package net.slimelabs.slslite.api;

/** Request to ensure one configured software release is installed. */
public record SoftwareInstallationRequest(String softwareId, String version) {

  public SoftwareInstallationRequest {
    softwareId = text(softwareId, "softwareId");
    version = text(version, "version");
  }

  private static String text(String value, String field) {
    if (value == null
        || value.isBlank()
        || value.length() > 64
        || value.indexOf('\n') >= 0
        || value.indexOf('\r') >= 0) {
      throw new IllegalArgumentException(field + " must be one line of 1 to 64 characters");
    }
    return value.strip();
  }
}
