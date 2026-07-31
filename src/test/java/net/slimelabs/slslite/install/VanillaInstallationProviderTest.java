package net.slimelabs.slslite.install;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import net.slimelabs.slslite.software.SoftwareConfigurator;
import net.slimelabs.slslite.software.SoftwareProfile;
import net.slimelabs.slslite.software.SoftwareReleaseChannel;
import net.slimelabs.slslite.software.SoftwareRuntime;
import net.slimelabs.slslite.software.SoftwareSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class VanillaInstallationProviderTest {

  @TempDir Path temporaryDirectory;

  @Test
  void downloadsExactVersionAndVerifiesPublishedArtifact() throws Exception {
    byte[] artifact = "vanilla-fixture".getBytes(StandardCharsets.UTF_8);
    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-1").digest(artifact));
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    String base = "http://127.0.0.1:" + server.getAddress().getPort();
    server.createContext(
        "/server.jar",
        exchange -> {
          exchange.sendResponseHeaders(200, artifact.length);
          exchange.getResponseBody().write(artifact);
          exchange.close();
        });
    server.createContext(
        "/version.json",
        exchange ->
            respond(
                exchange,
                """
                {
                  "downloads": {
                    "server": {
                      "url": "%s/server.jar",
                      "size": %d,
                      "sha1": "%s"
                    }
                  }
                }
                """
                    .formatted(base, artifact.length, hash)));
    server.createContext(
        "/manifest.json",
        exchange ->
            respond(
                exchange,
                """
                {
                  "versions": [
                    {"id": "1.20.6", "url": "%s/version.json"}
                  ]
                }
                """
                    .formatted(base)));
    server.start();
    try {
      VanillaInstallationProvider provider =
          new VanillaInstallationProvider(
              HttpClient.newHttpClient(), URI.create(base + "/manifest.json"), false);

      InstallationArtifact result =
          provider.install(profile(), "1.20.6", temporaryDirectory, ignored -> {});

      assertArrayEquals(artifact, Files.readAllBytes(temporaryDirectory.resolve("server.jar")));
      assertEquals(artifact.length, result.size());
      assertEquals("SHA-1", result.digestAlgorithm());
      assertEquals(hash, result.checksum());
    } finally {
      server.stop(0);
    }
  }

  @Test
  void officialProviderRejectsNonMojangMetadataHost() throws Exception {
    var method =
        VanillaInstallationProvider.class.getDeclaredMethod("validateMojangUri", URI.class);
    method.setAccessible(true);
    VanillaInstallationProvider provider = new VanillaInstallationProvider();

    try {
      method.invoke(provider, URI.create("https://example.com/version.json"));
    } catch (java.lang.reflect.InvocationTargetException exception) {
      assertTrue(exception.getCause() instanceof SoftwareInstallationException);
      return;
    }
    throw new AssertionError("Expected untrusted host rejection");
  }

  private static void respond(com.sun.net.httpserver.HttpExchange exchange, String body)
      throws java.io.IOException {
    byte[] response = body.getBytes(StandardCharsets.UTF_8);
    exchange.getResponseHeaders().add("Content-Type", "application/json");
    exchange.sendResponseHeaders(200, response.length);
    exchange.getResponseBody().write(response);
    exchange.close();
  }

  private static SoftwareProfile profile() {
    return new SoftwareProfile(
        "vanilla",
        SoftwareRuntime.JAVA_JAR,
        SoftwareConfigurator.VANILLA,
        SoftwareSource.VANILLA,
        SoftwareReleaseChannel.STABLE,
        true,
        "java",
        Map.of(),
        "software/vanilla/{version}",
        "server.jar",
        List.of(),
        List.of("nogui"),
        "Done",
        30,
        "stop",
        10);
  }
}
