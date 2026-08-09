package net.slimelabs.slslite.instance.model;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Collection;
import java.util.Comparator;
import java.util.Map;
import java.util.Objects;
import net.slimelabs.slslite.blueprint.Blueprint;
import net.slimelabs.slslite.blueprint.BlueprintCopy;
import net.slimelabs.slslite.blueprint.BlueprintPersistentFile;
import net.slimelabs.slslite.blueprint.BlueprintVolume;
import net.slimelabs.slslite.software.SoftwareProfile;

public record InstanceDefinitionIdentity(
    String softwareId, String softwareVersion, String fingerprint) {

  public InstanceDefinitionIdentity {
    requireText(softwareId, "softwareId");
    requireText(softwareVersion, "softwareVersion");
    requireText(fingerprint, "fingerprint");
  }

  public static InstanceDefinitionIdentity from(Blueprint blueprint, SoftwareProfile profile) {
    Objects.requireNonNull(blueprint, "blueprint");
    Objects.requireNonNull(profile, "profile");
    if (!blueprint.software().equals(profile.id())) {
      throw new IllegalArgumentException(
          "Blueprint software does not match profile: "
              + blueprint.software()
              + " != "
              + profile.id());
    }

    try {
      ByteArrayOutputStream bytes = new ByteArrayOutputStream();
      try (DataOutputStream output = new DataOutputStream(bytes)) {
        writeValue(output, profile.id());
        writeValue(output, profile.runtime());
        writeValue(output, profile.configurator());
        writeValue(output, profile.source());
        writeValue(output, profile.channel());
        writeValue(output, profile.acceptEula());
        writeValue(output, profile.javaExecutable());
        writeValue(output, profile.javaExecutables());
        writeValue(output, profile.baseDirectory());
        writeValue(output, profile.serverJar());
        writeValue(output, profile.jvmArguments());
        writeValue(output, profile.serverArguments());
        writeValue(output, profile.serverProperties());
        writeValue(output, profile.readinessPattern());
        writeValue(output, profile.startupTimeoutSeconds());
        writeValue(output, profile.stopCommand());
        writeValue(output, profile.stopTimeoutSeconds());
        writeValue(output, blueprint.image());
        writeValue(output, blueprint.softwarePath());
        writeValue(output, blueprint.save());
        writeValue(output, blueprint.serverProperties());
        writeValue(output, blueprint.yamlConfigs());
        writeValue(output, blueprint.textFileConfigs());
        writeValue(output, blueprint.annotations());
        writeValue(
            output,
            blueprint.volumes().stream().map(InstanceDefinitionIdentity::volumeValues).toList());
        writeValue(
            output,
            blueprint.copies().stream().map(InstanceDefinitionIdentity::copyValues).toList());
        // Preserve the published RC.1 fingerprint byte stream for blueprints that do not use the
        // additive RC.2 field. Existing persistent instances must remain restartable after upgrade.
        if (!blueprint.persistentFiles().isEmpty()) {
          writeValue(
              output,
              blueprint.persistentFiles().stream()
                  .map(InstanceDefinitionIdentity::persistentFileValues)
                  .toList());
        }
        writeValue(output, blueprint.environment());
      }
      MessageDigest digest = MessageDigest.getInstance("SHA-256");
      return new InstanceDefinitionIdentity(
          blueprint.software(),
          blueprint.version(),
          java.util.HexFormat.of().formatHex(digest.digest(bytes.toByteArray())));
    } catch (IOException exception) {
      throw new IllegalStateException("Unable to fingerprint instance definition", exception);
    } catch (NoSuchAlgorithmException exception) {
      throw new IllegalStateException("SHA-256 is unavailable", exception);
    }
  }

  private static Map<String, Object> volumeValues(BlueprintVolume volume) {
    return Map.of(
        "name", volume.name(),
        "source", volume.source(),
        "target", volume.target(),
        "mode", volume.mode().name());
  }

  private static Map<String, Object> copyValues(BlueprintCopy copy) {
    return Map.of(
        "source", copy.source(),
        "target", copy.target());
  }

  private static Map<String, Object> persistentFileValues(BlueprintPersistentFile file) {
    return Map.of(
        "name", file.name(),
        "source", file.source(),
        "target", file.target());
  }

  private static void writeValue(DataOutputStream output, Object value) throws IOException {
    if (value == null) {
      output.writeByte(0);
    } else if (value instanceof Map<?, ?> map) {
      output.writeByte(1);
      var entries =
          map.entrySet().stream()
              .sorted(Comparator.comparing(entry -> String.valueOf(entry.getKey())))
              .toList();
      output.writeInt(entries.size());
      for (Map.Entry<?, ?> entry : entries) {
        writeValue(output, String.valueOf(entry.getKey()));
        writeValue(output, entry.getValue());
      }
    } else if (value instanceof Collection<?> collection) {
      output.writeByte(2);
      output.writeInt(collection.size());
      for (Object item : collection) {
        writeValue(output, item);
      }
    } else {
      output.writeByte(3);
      byte[] encoded = String.valueOf(value).getBytes(StandardCharsets.UTF_8);
      output.writeInt(encoded.length);
      output.write(encoded);
    }
  }

  private static void requireText(String value, String field) {
    if (value == null || value.isBlank()) {
      throw new IllegalArgumentException(field + " must not be blank");
    }
  }
}
