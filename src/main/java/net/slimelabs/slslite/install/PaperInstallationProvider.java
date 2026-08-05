package net.slimelabs.slslite.install;

import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.channels.Channels;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.OptionalLong;
import java.util.function.Consumer;
import net.slimelabs.slslite.BuildInfo;
import net.slimelabs.slslite.software.SoftwareProfile;
import net.slimelabs.slslite.software.SoftwareSource;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

public final class PaperInstallationProvider implements SoftwareInstallationProvider {

  private static final URI API = URI.create("https://fill.papermc.io/v3/projects/paper/versions/");
  private static final String USER_AGENT =
      "SLS-LITE/" + BuildInfo.VERSION + " (https://github.com/Yeetoxic/SLS-LITE)";
  private static final long MAX_DOWNLOAD_BYTES = 256L * 1024L * 1024L;

  private final HttpClient client;
  private final URI api;
  private final boolean requireOfficialHosts;

  public PaperInstallationProvider() {
    this(
        HttpClient.newBuilder()
            .followRedirects(HttpClient.Redirect.NEVER)
            .connectTimeout(Duration.ofSeconds(15))
            .build(),
        API,
        true);
  }

  PaperInstallationProvider(HttpClient client, URI api, boolean requireOfficialHosts) {
    this.client = client;
    this.api = api;
    this.requireOfficialHosts = requireOfficialHosts;
  }

  @Override
  public SoftwareSource source() {
    return SoftwareSource.PAPER;
  }

  @Override
  public InstallationArtifact install(
      SoftwareProfile profile, String version, Path stagingDirectory, Consumer<String> log)
      throws Exception {
    OptionalLong requestedBuild = profile.paperBuildForVersion(version);
    PaperDownload download = resolve(version, profile.channel().name(), requestedBuild);
    log.accept(
        (requestedBuild.isPresent() ? "Selected pinned " : "Selected newest allowed ")
            + download.channel().toLowerCase()
            + " Paper build "
            + download.build()
            + " for exact version "
            + version);
    if (download.size() <= 0 || download.size() > MAX_DOWNLOAD_BYTES) {
      throw new SoftwareInstallationException(
          "Paper artifact size is outside the allowed range: " + download.size());
    }
    Path destination = stagingDirectory.resolve(profile.serverJar()).normalize();
    if (!destination.startsWith(stagingDirectory.normalize())) {
      throw new SoftwareInstallationException("Software JAR escapes the staging directory");
    }
    Files.createDirectories(destination.getParent());
    HttpRequest request = request(download.url()).timeout(Duration.ofMinutes(3)).build();
    HttpResponse<InputStream> response =
        client.send(request, HttpResponse.BodyHandlers.ofInputStream());
    requireSuccess(response.statusCode(), "Paper artifact");
    long actualSize;
    try (InputStream input = response.body()) {
      actualSize = copyBounded(input, destination, download.size());
    }
    if (actualSize != download.size()) {
      throw new SoftwareInstallationException(
          "Paper artifact size mismatch: expected " + download.size() + ", received " + actualSize);
    }
    String actualHash = sha256(destination);
    if (!MessageDigest.isEqual(
        actualHash.getBytes(StandardCharsets.US_ASCII),
        download.sha256().getBytes(StandardCharsets.US_ASCII))) {
      throw new SoftwareInstallationException("Paper artifact SHA-256 mismatch");
    }
    log.accept("Verified SHA-256 " + actualHash);
    return new InstallationArtifact(actualSize, "SHA-256", actualHash);
  }

  private PaperDownload resolve(String version, String channel, OptionalLong requestedBuild)
      throws Exception {
    String encoded = URLEncoder.encode(version, StandardCharsets.UTF_8);
    URI endpoint = api.resolve(encoded + "/builds");
    HttpResponse<InputStream> response =
        client.send(
            request(endpoint).timeout(Duration.ofSeconds(30)).build(),
            HttpResponse.BodyHandlers.ofInputStream());
    requireSuccess(response.statusCode(), "Paper build metadata");
    String body;
    try (InputStream input = response.body()) {
      body = new String(readBounded(input, 1024 * 1024), StandardCharsets.UTF_8);
    }

    LoaderOptions options = new LoaderOptions();
    options.setAllowDuplicateKeys(false);
    Object parsed = new Yaml(new SafeConstructor(options)).load(body);
    if (!(parsed instanceof List<?> builds)) {
      throw new SoftwareInstallationException("Paper build metadata has an unexpected format");
    }
    Map<?, ?> selected = null;
    String selectedChannel = null;
    long selectedBuild = -1;
    boolean pinnedBuildExistsOutsideChannel = false;
    for (Object value : builds) {
      if (!(value instanceof Map<?, ?> build)) {
        continue;
      }
      String buildChannel = String.valueOf(build.get("channel"));
      String buildId = required(build, "id");
      long buildNumber;
      try {
        buildNumber = Long.parseLong(buildId);
      } catch (NumberFormatException exception) {
        throw new SoftwareInstallationException(
            "Paper build has an invalid numeric ID: " + buildId);
      }
      if (buildNumber < 0) {
        throw new SoftwareInstallationException("Paper build has a negative ID: " + buildId);
      }
      if (requestedBuild.isPresent() && buildNumber != requestedBuild.getAsLong()) {
        continue;
      }
      if (!channelAllowed(channel, buildChannel)) {
        if (requestedBuild.isPresent()) {
          pinnedBuildExistsOutsideChannel = true;
        }
        continue;
      }
      if (selected == null || buildNumber > selectedBuild) {
        selectedBuild = buildNumber;
        selected = build;
        selectedChannel = buildChannel;
      }
    }
    if (selected != null) {
      Map<?, ?> downloads = map(selected.get("downloads"), "downloads");
      Map<?, ?> artifact = map(downloads.get("server:default"), "server:default");
      Map<?, ?> checksums = map(artifact.get("checksums"), "checksums");
      URI url = URI.create(required(artifact, "url"));
      validateDownloadUrl(url);
      return new PaperDownload(
          Long.toString(selectedBuild),
          selectedChannel,
          url,
          Long.parseLong(String.valueOf(artifact.get("size"))),
          required(checksums, "sha256").toLowerCase());
    }
    if (requestedBuild.isPresent()) {
      String requested = Long.toString(requestedBuild.getAsLong());
      if (pinnedBuildExistsOutsideChannel) {
        throw new SoftwareInstallationException(
            "Pinned Paper build "
                + requested
                + " for exact version "
                + version
                + " is outside the configured "
                + channel.toLowerCase()
                + " channel");
      }
      throw new SoftwareInstallationException(
          "Pinned Paper build " + requested + " is unavailable for exact version " + version);
    }
    throw new SoftwareInstallationException(
        "No Paper build compatible with the "
            + channel.toLowerCase()
            + " channel is available for exact version "
            + version);
  }

  private static boolean channelAllowed(String requested, String available) {
    int requestedRank = channelRank(requested);
    int availableRank = channelRank(available);
    return requestedRank >= 0 && availableRank >= 0 && availableRank <= requestedRank;
  }

  private static int channelRank(String channel) {
    return switch (channel.toUpperCase(java.util.Locale.ROOT)) {
      case "STABLE" -> 0;
      case "BETA" -> 1;
      case "ALPHA" -> 2;
      default -> -1;
    };
  }

  private HttpRequest.Builder request(URI uri) {
    return HttpRequest.newBuilder(uri)
        .header("User-Agent", USER_AGENT)
        .header("Accept", "application/json");
  }

  private void validateDownloadUrl(URI uri) throws SoftwareInstallationException {
    String host = uri.getHost();
    if (requireOfficialHosts
        && (!"https".equalsIgnoreCase(uri.getScheme())
            || host == null
            || !(host.equals("papermc.io") || host.endsWith(".papermc.io")))) {
      throw new SoftwareInstallationException("Paper returned an untrusted download URL");
    }
  }

  private static Map<?, ?> map(Object value, String field) throws SoftwareInstallationException {
    if (value instanceof Map<?, ?> map) {
      return map;
    }
    throw new SoftwareInstallationException("Paper metadata is missing " + field);
  }

  private static String required(Map<?, ?> map, String field) throws SoftwareInstallationException {
    Object value = map.get(field);
    if (value == null || value.toString().isBlank()) {
      throw new SoftwareInstallationException("Paper metadata is missing " + field);
    }
    return value.toString();
  }

  private static void requireSuccess(int status, String operation)
      throws SoftwareInstallationException {
    if (status < 200 || status >= 300) {
      throw new SoftwareInstallationException(operation + " request failed with HTTP " + status);
    }
  }

  private static String sha256(Path path) throws Exception {
    MessageDigest digest = MessageDigest.getInstance("SHA-256");
    try (var channel =
            Files.newByteChannel(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        var input = Channels.newInputStream(channel)) {
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
    try (var output =
        Files.newOutputStream(
            destination,
            StandardOpenOption.CREATE_NEW,
            StandardOpenOption.WRITE,
            LinkOption.NOFOLLOW_LINKS)) {
      byte[] buffer = new byte[8192];
      int read;
      while ((read = input.read(buffer)) >= 0) {
        total += read;
        if (total > maximum) {
          throw new SoftwareInstallationException("Paper artifact exceeded its declared size");
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
        throw new SoftwareInstallationException("Paper metadata response is too large");
      }
      output.write(buffer, 0, read);
    }
    return output.toByteArray();
  }

  private record PaperDownload(String build, String channel, URI url, long size, String sha256) {}
}
