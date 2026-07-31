package net.slimelabs.slslite.install;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import net.slimelabs.slslite.software.SoftwareConfigurator;
import net.slimelabs.slslite.software.SoftwareProfile;
import net.slimelabs.slslite.software.SoftwareRuntime;
import net.slimelabs.slslite.software.SoftwareSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PaperInstallationProviderTest {

  @TempDir Path temporaryDirectory;

  @Test
  void downloadsLatestStableArtifactAndVerifiesChecksum() throws Exception {
    byte[] artifact = "paper-fixture".getBytes(StandardCharsets.UTF_8);
    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(artifact));
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/artifact.jar",
        exchange -> {
          exchange.sendResponseHeaders(200, artifact.length);
          exchange.getResponseBody().write(artifact);
          exchange.close();
        });
    server.createContext(
        "/v3/projects/paper/versions/1.0/builds",
        exchange -> {
          String base = "http://127.0.0.1:" + server.getAddress().getPort();
          byte[] body =
              ("""
                    [
                      {
                        "id": 43,
                        "channel": "BETA",
                        "downloads": {
                          "server:default": {
                            "url": "%s/artifact.jar",
                            "size": %d,
                            "checksums": {"sha256": "%s"}
                          }
                        }
                      },
                      {
                        "id": 42,
                        "channel": "STABLE",
                        "downloads": {
                          "server:default": {
                            "url": "%s/artifact.jar",
                            "size": %d,
                            "checksums": {"sha256": "%s"}
                          }
                        }
                      }
                    ]
                    """)
                  .formatted(base, artifact.length, hash, base, artifact.length, hash)
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    try {
      URI api =
          URI.create(
              "http://127.0.0.1:" + server.getAddress().getPort() + "/v3/projects/paper/versions/");
      PaperInstallationProvider provider =
          new PaperInstallationProvider(HttpClient.newHttpClient(), api, false);
      provider.install(profile(), "1.0", temporaryDirectory, ignored -> {});

      assertArrayEquals(artifact, Files.readAllBytes(temporaryDirectory.resolve("paper.jar")));

      Path betaDirectory = temporaryDirectory.resolve("beta");
      Files.createDirectories(betaDirectory);
      List<String> betaLogs = new ArrayList<>();
      provider.install(
          profile(net.slimelabs.slslite.software.SoftwareReleaseChannel.BETA),
          "1.0",
          betaDirectory,
          betaLogs::add);
      assertTrue(betaLogs.stream().anyMatch(line -> line.contains("beta Paper build 43")));
    } finally {
      server.stop(0);
    }
  }

  @Test
  void officialProviderRejectsNonPaperDownloadHost() throws Exception {
    var method =
        PaperInstallationProvider.class.getDeclaredMethod("validateDownloadUrl", URI.class);
    method.setAccessible(true);
    PaperInstallationProvider provider = new PaperInstallationProvider();

    try {
      method.invoke(provider, URI.create("https://example.com/paper.jar"));
    } catch (java.lang.reflect.InvocationTargetException exception) {
      assertTrue(exception.getCause() instanceof SoftwareInstallationException);
      return;
    }
    throw new AssertionError("Expected untrusted host rejection");
  }

  @Test
  void betaProfileFallsBackToStableBuild() throws Exception {
    byte[] artifact = "stable-paper".getBytes(StandardCharsets.UTF_8);
    String hash = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(artifact));
    HttpServer server = HttpServer.create(new InetSocketAddress(0), 0);
    server.createContext(
        "/artifact.jar",
        exchange -> {
          exchange.sendResponseHeaders(200, artifact.length);
          exchange.getResponseBody().write(artifact);
          exchange.close();
        });
    server.createContext(
        "/v3/projects/paper/versions/1.0/builds",
        exchange -> {
          String base = "http://127.0.0.1:" + server.getAddress().getPort();
          byte[] body =
              ("""
                    [
                      {
                        "id": 388,
                        "channel": "STABLE",
                        "downloads": {
                          "server:default": {
                            "url": "%s/artifact.jar",
                            "size": %d,
                            "checksums": {"sha256": "%s"}
                          }
                        }
                      }
                    ]
                    """)
                  .formatted(base, artifact.length, hash)
                  .getBytes(StandardCharsets.UTF_8);
          exchange.getResponseHeaders().add("Content-Type", "application/json");
          exchange.sendResponseHeaders(200, body.length);
          exchange.getResponseBody().write(body);
          exchange.close();
        });
    server.start();
    try {
      PaperInstallationProvider provider =
          new PaperInstallationProvider(
              HttpClient.newHttpClient(),
              URI.create(
                  "http://127.0.0.1:"
                      + server.getAddress().getPort()
                      + "/v3/projects/paper/versions/"),
              false);
      List<String> logs = new ArrayList<>();

      provider.install(
          profile(net.slimelabs.slslite.software.SoftwareReleaseChannel.BETA),
          "1.0",
          temporaryDirectory,
          logs::add);

      assertArrayEquals(artifact, Files.readAllBytes(temporaryDirectory.resolve("paper.jar")));
      assertTrue(logs.stream().anyMatch(line -> line.contains("stable Paper build 388")));
    } finally {
      server.stop(0);
    }
  }

  private SoftwareProfile profile() {
    return profile(net.slimelabs.slslite.software.SoftwareReleaseChannel.STABLE);
  }

  private SoftwareProfile profile(net.slimelabs.slslite.software.SoftwareReleaseChannel channel) {
    return new SoftwareProfile(
        "paper",
        SoftwareRuntime.JAVA_JAR,
        SoftwareConfigurator.PAPER,
        SoftwareSource.PAPER,
        channel,
        true,
        "java",
        java.util.Map.of(),
        "software/paper/{version}",
        "paper.jar",
        List.of(),
        List.of(),
        "Done",
        30,
        "stop",
        10);
  }
}
