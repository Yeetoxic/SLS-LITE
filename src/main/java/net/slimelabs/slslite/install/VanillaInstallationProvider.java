package net.slimelabs.slslite.install;

import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import net.slimelabs.slslite.BuildInfo;
import net.slimelabs.slslite.software.SoftwareProfile;
import net.slimelabs.slslite.software.SoftwareSource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

public final class VanillaInstallationProvider implements SoftwareInstallationProvider {

  private static final URI MANIFEST =
      URI.create("https://piston-meta.mojang.com/mc/game/version_manifest_v2.json");
  private static final String USER_AGENT =
      "SLS-LITE/" + BuildInfo.VERSION + " (https://github.com/Yeetoxic/SLS-LITE)";
  private static final long MAX_DOWNLOAD_BYTES = 256L * 1024L * 1024L;

  private final HttpClient client;
  private final URI manifest;
  private final boolean requireOfficialHosts;

  public VanillaInstallationProvider() {
    this(
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(15))
            .build(),
        MANIFEST,
        true);
  }

  VanillaInstallationProvider(HttpClient client, URI manifest, boolean requireOfficialHosts) {
    this.client = client;
    this.manifest = manifest;
    this.requireOfficialHosts = requireOfficialHosts;
  }

  @Override
  public SoftwareSource source() {
    return SoftwareSource.VANILLA;
  }

  @Override
  public InstallationArtifact install(
      SoftwareProfile profile, String version, Path stagingDirectory, Consumer<String> log)
      throws Exception {
    Map<?, ?> root = object(fetch(manifest), "version manifest");
    URI metadata = null;
    Object versionsValue = root.get("versions");
    if (versionsValue instanceof List<?> versions) {
      for (Object value : versions) {
        Map<?, ?> candidate = object(value, "version");
        if (version.equals(String.valueOf(candidate.get("id")))) {
          metadata = URI.create(required(candidate, "url"));
          break;
        }
      }
    }
    if (metadata == null) {
      throw new SoftwareInstallationException("Unknown vanilla Minecraft version: " + version);
    }
    validateMojangUri(metadata);
    Map<?, ?> versionMetadata = object(fetch(metadata), "version metadata");
    Map<?, ?> downloads = object(versionMetadata.get("downloads"), "downloads");
    Map<?, ?> server = object(downloads.get("server"), "server download");
    URI download = URI.create(required(server, "url"));
    validateMojangUri(download);
    long expectedSize = Long.parseLong(required(server, "size"));
    if (expectedSize <= 0 || expectedSize > MAX_DOWNLOAD_BYTES) {
      throw new SoftwareInstallationException(
          "Vanilla artifact size is outside the allowed range: " + expectedSize);
    }
    String expectedSha1 = required(server, "sha1").toLowerCase();
    Path destination = stagingDirectory.resolve(profile.serverJar()).normalize();
    if (!destination.startsWith(stagingDirectory.normalize())) {
      throw new SoftwareInstallationException("Software JAR escapes the staging directory");
    }
    Files.createDirectories(destination.getParent());
    log.accept("Downloading vanilla server " + version);
    HttpResponse<InputStream> response =
        client.send(
            request(download).timeout(Duration.ofMinutes(3)).build(),
            HttpResponse.BodyHandlers.ofInputStream());
    requireSuccess(response.statusCode(), "Vanilla artifact");
    long actualSize;
    try (InputStream input = response.body()) {
      actualSize = copyBounded(input, destination, expectedSize);
    }
    if (actualSize != expectedSize) {
      throw new SoftwareInstallationException("Vanilla artifact size mismatch");
    }
    String actualSha1 = digest(destination, "SHA-1");
    if (!MessageDigest.isEqual(
        actualSha1.getBytes(StandardCharsets.US_ASCII),
        expectedSha1.getBytes(StandardCharsets.US_ASCII))) {
      throw new SoftwareInstallationException("Vanilla artifact SHA-1 mismatch");
    }
    log.accept("Verified Mojang SHA-1 " + actualSha1);
    return new InstallationArtifact(actualSize, "SHA-1", actualSha1);
  }

  private Object fetch(URI uri) throws Exception {
    HttpResponse<InputStream> response =
        client.send(
            request(uri).timeout(Duration.ofSeconds(30)).build(),
            HttpResponse.BodyHandlers.ofInputStream());
    requireSuccess(response.statusCode(), "Mojang metadata");
    String body;
    try (InputStream input = response.body()) {
      body = new String(readBounded(input, 4 * 1024 * 1024), StandardCharsets.UTF_8);
    }
    LoaderOptions options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    return new Yaml(new SafeConstructor(options)).load(body);
  }

  private HttpRequest.Builder request(URI uri) {
    return HttpRequest.newBuilder(uri)
        .header("User-Agent", USER_AGENT)
        .header("Accept", "application/json");
  }

  private void validateMojangUri(URI uri) throws SoftwareInstallationException {
    String host = uri.getHost();
    if (requireOfficialHosts
        && (!"https".equalsIgnoreCase(uri.getScheme())
            || host == null
            || !(host.equals("mojang.com") || host.endsWith(".mojang.com")))) {
      throw new SoftwareInstallationException("Mojang returned an untrusted download URL");
    }
  }

  private static Map<?, ?> object(Object value, String name) throws SoftwareInstallationException {
    if (value instanceof Map<?, ?> map) {
      return map;
    }
    throw new SoftwareInstallationException("Mojang metadata is missing " + name);
  }

  private static String required(Map<?, ?> map, String key) throws SoftwareInstallationException {
    Object value = map.get(key);
    if (value == null || value.toString().isBlank()) {
      throw new SoftwareInstallationException("Mojang metadata is missing " + key);
    }
    return value.toString();
  }

  private static void requireSuccess(int status, String operation)
      throws SoftwareInstallationException {
    if (status < 200 || status >= 300) {
      throw new SoftwareInstallationException(operation + " request failed with HTTP " + status);
    }
  }

  private static String digest(Path path, String algorithm) throws Exception {
    MessageDigest digest = MessageDigest.getInstance(algorithm);
    try (var input = Files.newInputStream(path)) {
      byte[] buffer = new byte[8192];
      int read;
      while ((read = input.read(buffer)) >= 0) {
        digest.update(buffer, 0, read);
      }
    }
    return HexFormat.of().formatHex(digest.digest());
  }

  private static long copyBounded(InputStream input, Path destination, long expected)
      throws Exception {
    long maximum = Math.min(MAX_DOWNLOAD_BYTES, expected);
    long total = 0;
    try (var output = Files.newOutputStream(destination)) {
      byte[] buffer = new byte[8192];
      int read;
      while ((read = input.read(buffer)) >= 0) {
        total += read;
        if (total > maximum) {
          throw new SoftwareInstallationException("Vanilla artifact exceeded its declared size");
        }
        output.write(buffer, 0, read);
      }
    }
    return total;
  }

  private static byte[] readBounded(InputStream input, int maximum) throws Exception {
    var output = new java.io.ByteArrayOutputStream();
    byte[] buffer = new byte[8192];
    int read;
    while ((read = input.read(buffer)) >= 0) {
      if (output.size() + read > maximum) {
        throw new SoftwareInstallationException("Mojang metadata response is too large");
      }
      output.write(buffer, 0, read);
    }
    return output.toByteArray();
  }
}
