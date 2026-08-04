package net.slimelabs.slslite.api.event;

import java.time.Instant;
import java.util.Objects;

/**
 * Bounded state transition for one shared automatic software installation.
 *
 * @param sequence provider-lifetime event sequence number
 * @param occurredAt transition time
 * @param softwareId configured software identifier
 * @param version requested software version
 * @param source installation provider/source family
 * @param channel requested release channel
 * @param status new installation status
 * @param failureCategory sanitized terminal failure category, or {@code NONE}
 */
public record SoftwareInstallationEvent(
    long sequence,
    Instant occurredAt,
    String softwareId,
    String version,
    SoftwareInstallationSource source,
    SoftwareReleaseChannel channel,
    SoftwareInstallationStatus status,
    SoftwareInstallationFailureCategory failureCategory)
    implements SLSLiteEvent {

  public SoftwareInstallationEvent {
    if (sequence < 1) {
      throw new IllegalArgumentException("sequence must be positive");
    }
    Objects.requireNonNull(occurredAt, "occurredAt");
    requireText(softwareId, "softwareId");
    requireText(version, "version");
    Objects.requireNonNull(source, "source");
    Objects.requireNonNull(channel, "channel");
    Objects.requireNonNull(status, "status");
    Objects.requireNonNull(failureCategory, "failureCategory");
    boolean normal =
        status == SoftwareInstallationStatus.STARTED || status == SoftwareInstallationStatus.READY;
    if (normal != (failureCategory == SoftwareInstallationFailureCategory.NONE)) {
      throw new IllegalArgumentException(
          "started/ready events require NONE; failed/cancelled require a category");
    }
    if ((status == SoftwareInstallationStatus.CANCELLED)
        != (failureCategory == SoftwareInstallationFailureCategory.CANCELLED)) {
      throw new IllegalArgumentException("cancelled status and category must match");
    }
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }
}
