package net.slimelabs.slslite.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import net.slimelabs.slslite.process.JavaJarProcessSpecFactory;
import net.slimelabs.slslite.software.SoftwareConfigurator;
import net.slimelabs.slslite.software.SoftwareProfile;
import net.slimelabs.slslite.software.SoftwareRuntime;
import net.slimelabs.slslite.software.SoftwareSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.LoggerFactory;

class SoftwareInstallationServiceTest {

  @TempDir Path temporaryDirectory;

  @Test
  void sharesConcurrentInstallAndPublishesCompleteDirectory() {
    AtomicInteger installs = new AtomicInteger();
    SoftwareInstallationProvider provider =
        new SoftwareInstallationProvider() {
          @Override
          public SoftwareSource source() {
            return SoftwareSource.PAPER;
          }

          @Override
          public InstallationArtifact install(
              SoftwareProfile profile,
              String version,
              Path stagingDirectory,
              java.util.function.Consumer<String> log)
              throws Exception {
            installs.incrementAndGet();
            Path jar = stagingDirectory.resolve("server.jar");
            Files.writeString(jar, "fixture");
            return artifact(jar);
          }
        };
    try (SoftwareInstallationService service =
        new SoftwareInstallationService(
            new JavaJarProcessSpecFactory(temporaryDirectory),
            List.of(provider),
            LoggerFactory.getLogger(getClass()))) {
      var first = service.ensureInstalled(profile(SoftwareSource.PAPER), "1.0");
      var second = service.ensureInstalled(profile(SoftwareSource.PAPER), "1.0");

      Path installed = first.join();
      assertEquals(installed, second.join());
      assertEquals(1, installs.get());
      assertEquals("fixture", read(installed.resolve("server.jar")));
      assertEquals(InstallationState.READY, service.snapshot("fixture", "1.0").state());
    }
  }

  @Test
  void rejectsProviderCacheWhoseJarChangedAfterInstallation() throws Exception {
    SoftwareInstallationProvider provider =
        new SoftwareInstallationProvider() {
          @Override
          public SoftwareSource source() {
            return SoftwareSource.PAPER;
          }

          @Override
          public InstallationArtifact install(
              SoftwareProfile profile,
              String version,
              Path stagingDirectory,
              java.util.function.Consumer<String> log)
              throws Exception {
            Path jar = stagingDirectory.resolve("server.jar");
            Files.writeString(jar, "verified");
            return artifact(jar);
          }
        };
    SoftwareProfile profile = profile(SoftwareSource.PAPER);
    Path installed;
    try (SoftwareInstallationService service = service(List.of(provider))) {
      installed = service.ensureInstalled(profile, "1.0").join();
    }
    Files.writeString(installed.resolve("server.jar"), "changed");

    try (SoftwareInstallationService service = service(List.of(provider))) {
      assertThrows(Exception.class, () -> service.ensureInstalled(profile, "1.0").join());
      assertEquals(InstallationState.FAILED, service.snapshot("fixture", "1.0").state());
    }
  }

  @Test
  void rejectsDifferentProfilesInstallingIntoSameDirectory() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    SoftwareInstallationProvider provider =
        new SoftwareInstallationProvider() {
          @Override
          public SoftwareSource source() {
            return SoftwareSource.PAPER;
          }

          @Override
          public InstallationArtifact install(
              SoftwareProfile profile,
              String version,
              Path stagingDirectory,
              java.util.function.Consumer<String> log)
              throws Exception {
            entered.countDown();
            release.await(5, TimeUnit.SECONDS);
            Path jar = stagingDirectory.resolve("server.jar");
            Files.writeString(jar, "verified");
            return artifact(jar);
          }
        };
    SoftwareProfile firstProfile = profile(SoftwareSource.PAPER);
    SoftwareProfile alias =
        new SoftwareProfile(
            "alias",
            firstProfile.runtime(),
            firstProfile.configurator(),
            firstProfile.source(),
            firstProfile.channel(),
            firstProfile.acceptEula(),
            firstProfile.javaExecutable(),
            firstProfile.javaExecutables(),
            firstProfile.baseDirectory(),
            firstProfile.serverJar(),
            firstProfile.jvmArguments(),
            firstProfile.serverArguments(),
            firstProfile.readinessPattern(),
            firstProfile.startupTimeoutSeconds(),
            firstProfile.stopCommand(),
            firstProfile.stopTimeoutSeconds());

    try (SoftwareInstallationService service = service(List.of(provider))) {
      var first = service.ensureInstalled(firstProfile, "1.0");
      assertEquals(true, entered.await(5, TimeUnit.SECONDS));
      assertThrows(Exception.class, () -> service.ensureInstalled(alias, "1.0").join());
      release.countDown();
      first.join();
    } finally {
      release.countDown();
    }
  }

  @Test
  void missingManualSoftwareFailsWithoutCreatingDirectory() {
    try (SoftwareInstallationService service = service(List.of())) {
      assertThrows(
          Exception.class,
          () -> service.ensureInstalled(profile(SoftwareSource.MANUAL), "1.0").join());
    }
  }

  private SoftwareInstallationService service(List<SoftwareInstallationProvider> providers) {
    return new SoftwareInstallationService(
        new JavaJarProcessSpecFactory(temporaryDirectory),
        providers,
        LoggerFactory.getLogger(getClass()));
  }

  private static InstallationArtifact artifact(Path jar) throws Exception {
    byte[] contents = Files.readAllBytes(jar);
    String checksum =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contents));
    return new InstallationArtifact(contents.length, "SHA-256", checksum);
  }

  private SoftwareProfile profile(SoftwareSource source) {
    return new SoftwareProfile(
        "fixture",
        SoftwareRuntime.JAVA_JAR,
        SoftwareConfigurator.GENERIC,
        source,
        net.slimelabs.slslite.software.SoftwareReleaseChannel.STABLE,
        source != SoftwareSource.MANUAL,
        "java",
        java.util.Map.of(),
        "software/fixture/{version}",
        "server.jar",
        List.of("-Xmx{memory_mib}M"),
        List.of("--nogui"),
        "Done",
        30,
        "stop",
        10);
  }

  private static String read(Path path) {
    try {
      return Files.readString(path);
    } catch (Exception exception) {
      throw new AssertionError(exception);
    }
  }
}
