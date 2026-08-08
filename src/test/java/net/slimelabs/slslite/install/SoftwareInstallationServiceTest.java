package net.slimelabs.slslite.install;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
import java.time.Duration;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
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
      var transitions =
          new CopyOnWriteArrayList<SoftwareInstallationService.InstallationTransition>();
      service.installObserver(transitions::add);
      var first = service.ensureInstalled(profile(SoftwareSource.PAPER), "1.0");
      var second = service.ensureInstalled(profile(SoftwareSource.PAPER), "1.0");

      Path installed = first.join();
      assertEquals(installed, second.join());
      assertEquals(1, installs.get());
      assertEquals("fixture", read(installed.resolve("server.jar")));
      assertEquals(InstallationState.READY, service.snapshot("fixture", "1.0").state());
      assertEquals(
          List.of(
              SoftwareInstallationService.InstallationTransitionStatus.STARTED,
              SoftwareInstallationService.InstallationTransitionStatus.READY),
          transitions.stream().map(transition -> transition.status()).toList());
    }
  }

  @Test
  void zeroInstallerHistoryDoesNotAffectSuccessfulInstallation() {
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
            Files.writeString(jar, "fixture");
            return artifact(jar);
          }
        };
    try (SoftwareInstallationService service =
        new SoftwareInstallationService(
            new JavaJarProcessSpecFactory(temporaryDirectory),
            List.of(provider),
            0,
            LoggerFactory.getLogger(getClass()))) {
      service.ensureInstalled(profile(SoftwareSource.PAPER), "1.0").join();
      service.ensureInstalled(profile(SoftwareSource.PAPER), "1.0").join();

      assertEquals(List.of(), service.snapshots());
    }
  }

  @Test
  void replacesProviderCacheWhoseJarChangedAfterInstallation() throws Exception {
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
            Path jar = stagingDirectory.resolve("server.jar");
            Files.writeString(jar, "verified-" + installs.incrementAndGet());
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
      Path replacement = service.ensureInstalled(profile, "1.0").join();

      assertEquals("verified-2", read(replacement.resolve("server.jar")));
      assertEquals(InstallationState.READY, service.snapshot("fixture", "1.0").state());
      try (var siblings = Files.list(replacement.getParent())) {
        Path quarantine =
            siblings
                .filter(path -> path.getFileName().toString().startsWith(".1.0.incomplete-"))
                .findFirst()
                .orElseThrow();
        assertEquals("changed", read(quarantine.resolve("server.jar")));
      }
    }
  }

  @Test
  void rehashesAndReplacesCachedJarWhenSizeAndTimestampAreUnchanged() throws Exception {
    SoftwareProfile profile = profile(SoftwareSource.PAPER);
    SoftwareInstallationProvider provider =
        new SoftwareInstallationProvider() {
          @Override
          public SoftwareSource source() {
            return SoftwareSource.PAPER;
          }

          @Override
          public InstallationArtifact install(
              SoftwareProfile ignored,
              String version,
              Path stagingDirectory,
              java.util.function.Consumer<String> log)
              throws Exception {
            Path jar = stagingDirectory.resolve("server.jar");
            Files.writeString(jar, "original");
            return artifact(jar);
          }
        };
    try (SoftwareInstallationService service = service(List.of(provider))) {
      Path installed = service.ensureInstalled(profile, "1.0").join();
      Path jar = installed.resolve("server.jar");
      FileTime timestamp = Files.getLastModifiedTime(jar);
      Files.writeString(jar, "tampered");
      Files.setLastModifiedTime(jar, timestamp);

      assertEquals(installed, service.ensureInstalled(profile, "1.0").join());
      assertEquals("original", read(jar));
    }
  }

  @Test
  void paperPinInvalidatesUnpinnedCacheAndPinnedCacheIsReused() {
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
            Files.writeString(jar, profile.installationSelection(version));
            return artifact(jar);
          }
        };
    SoftwareProfile newest = profile(SoftwareSource.PAPER);
    SoftwareProfile pinned = withPaperPin(newest, "1.0", 41L);

    try (SoftwareInstallationService service = service(List.of(provider))) {
      Path installed = service.ensureInstalled(newest, "1.0").join();
      assertEquals("paper-build:newest", read(installed.resolve("server.jar")));

      Path replaced = service.ensureInstalled(pinned, "1.0").join();
      assertEquals("paper-build:41", read(replaced.resolve("server.jar")));
      assertEquals(replaced, service.ensureInstalled(pinned, "1.0").join());
      assertEquals(2, installs.get());
    }
  }

  @Test
  void retriesFailedProviderInstallation() {
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
            if (installs.incrementAndGet() == 1) {
              throw new java.io.IOException("simulated download failure");
            }
            Path jar = stagingDirectory.resolve("server.jar");
            Files.writeString(jar, "retry");
            return artifact(jar);
          }
        };
    try (SoftwareInstallationService service = service(List.of(provider))) {
      var transitions =
          new CopyOnWriteArrayList<SoftwareInstallationService.InstallationTransition>();
      service.installObserver(transitions::add);
      assertThrows(
          Exception.class,
          () -> service.ensureInstalled(profile(SoftwareSource.PAPER), "1.0").join());

      Path installed = service.ensureInstalled(profile(SoftwareSource.PAPER), "1.0").join();

      assertEquals("retry", read(installed.resolve("server.jar")));
      assertEquals(
          List.of(
              SoftwareInstallationService.InstallationTransitionStatus.STARTED,
              SoftwareInstallationService.InstallationTransitionStatus.FAILED,
              SoftwareInstallationService.InstallationTransitionStatus.STARTED,
              SoftwareInstallationService.InstallationTransitionStatus.READY),
          transitions.stream().map(transition -> transition.status()).toList());
      assertEquals(
          SoftwareInstallationService.InstallationFailureCategory.IO,
          transitions.get(1).failureCategory());
      assertEquals(2, installs.get());
    }
  }

  @Test
  void restoresIncompleteCacheWhenReplacementFails() throws Exception {
    Path target = temporaryDirectory.resolve("software/fixture/1.0");
    Files.createDirectories(target);
    Files.writeString(target.resolve("partial.txt"), "preserve me");
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
            throw new java.io.IOException("replacement failed");
          }
        };
    try (SoftwareInstallationService service = service(List.of(provider))) {
      assertThrows(
          Exception.class,
          () -> service.ensureInstalled(profile(SoftwareSource.PAPER), "1.0").join());

      assertEquals("preserve me", read(target.resolve("partial.txt")));
    }
  }

  @Test
  void shutdownInterruptsAndAwaitsActiveInstallation() throws Exception {
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch interrupted = new CountDownLatch(1);
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
            try {
              Thread.sleep(TimeUnit.MINUTES.toMillis(1));
            } catch (InterruptedException exception) {
              interrupted.countDown();
              throw exception;
            }
            throw new AssertionError("installation was not interrupted");
          }
        };
    SoftwareInstallationService service = service(List.of(provider));
    var transitions =
        new CopyOnWriteArrayList<SoftwareInstallationService.InstallationTransition>();
    service.installObserver(transitions::add);
    service.ensureInstalled(profile(SoftwareSource.PAPER), "1.0");
    assertEquals(true, entered.await(5, TimeUnit.SECONDS));

    service.shutdown(Duration.ofSeconds(5));

    assertEquals(true, interrupted.await(1, TimeUnit.SECONDS));
    assertEquals(
        List.of(
            SoftwareInstallationService.InstallationTransitionStatus.STARTED,
            SoftwareInstallationService.InstallationTransitionStatus.CANCELLED),
        transitions.stream().map(transition -> transition.status()).toList());
    assertThrows(
        Exception.class,
        () -> service.ensureInstalled(profile(SoftwareSource.PAPER), "2.0").join());
  }

  @Test
  void cacheCleanupIsDryRunByDefaultAndProtectsReferencedVersions() throws Exception {
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
            Files.writeString(jar, version);
            return artifact(jar);
          }
        };
    try (SoftwareInstallationService service = service(List.of(provider))) {
      Path removable = service.ensureInstalled(profile(SoftwareSource.PAPER), "1.0").join();
      Path protectedPath = service.ensureInstalled(profile(SoftwareSource.PAPER), "2.0").join();
      FileTime old = FileTime.from(java.time.Instant.now().minus(Duration.ofDays(2)));
      Files.setLastModifiedTime(removable.resolve(".sls-install.properties"), old);
      Files.setLastModifiedTime(protectedPath.resolve(".sls-install.properties"), old);
      InstallationKey protectedKey = new InstallationKey("fixture", "2.0");

      SoftwareCacheCleanupReport dryRun =
          service.cleanupCache(
              Duration.ofHours(24),
              true,
              false,
              Set.of(protectedKey),
              List.of(profile(SoftwareSource.PAPER)));

      assertEquals(1, dryRun.eligible().size());
      assertEquals(1, dryRun.eligibleCount());
      assertEquals(1, dryRun.protectedCount());
      assertEquals(true, Files.isDirectory(removable));

      SoftwareCacheCleanupReport committed =
          service.cleanupCache(
              Duration.ofHours(24),
              false,
              true,
              Set.of(protectedKey),
              List.of(profile(SoftwareSource.PAPER)));

      assertEquals(1, committed.removed().size());
      assertEquals(false, Files.exists(removable));
      assertEquals(true, Files.isDirectory(protectedPath));
    }
  }

  @Test
  void cacheCleanupRejectsUnsafeAgeAndUnconfirmedDeletion() {
    try (SoftwareInstallationService service = service(List.of())) {
      assertThrows(
          SoftwareInstallationException.class,
          () -> service.cleanupCache(Duration.ZERO, true, false, Set.of(), List.of()));
      assertThrows(
          SoftwareInstallationException.class,
          () -> service.cleanupCache(Duration.ofHours(1), false, false, Set.of(), List.of()));
    }
  }

  @Test
  void cacheCleanupDiscoversConfiguredDirectoriesOutsideDefaultSoftwareRoot() throws Exception {
    SoftwareInstallationProvider provider = providerWritingVersion();
    SoftwareProfile custom =
        profile("custom", SoftwareSource.PAPER, "provider-cache/custom/{version}");
    try (SoftwareInstallationService service = service(List.of(provider))) {
      Path installed = service.ensureInstalled(custom, "1.0").join();
      Files.setLastModifiedTime(
          installed.resolve(".sls-install.properties"),
          FileTime.from(java.time.Instant.now().minus(Duration.ofDays(2))));

      SoftwareCacheCleanupReport report =
          service.cleanupCache(Duration.ofHours(24), true, false, Set.of(), List.of(custom));

      assertEquals(1, report.eligibleCount());
      assertEquals(installed.toRealPath(), report.eligible().getFirst().directory());
    }
  }

  @Test
  void cacheCleanupProtectsAliasedResolvedDirectory() throws Exception {
    SoftwareProfile owner = profile(SoftwareSource.PAPER);
    SoftwareProfile alias = profile("alias", SoftwareSource.PAPER, owner.baseDirectory());
    try (SoftwareInstallationService service = service(List.of(providerWritingVersion()))) {
      Path installed = service.ensureInstalled(owner, "1.0").join();
      Files.setLastModifiedTime(
          installed.resolve(".sls-install.properties"),
          FileTime.from(java.time.Instant.now().minus(Duration.ofDays(2))));

      SoftwareCacheCleanupReport report =
          service.cleanupCache(
              Duration.ofHours(24),
              true,
              false,
              Set.of(new InstallationKey("alias", "1.0")),
              List.of(owner, alias));

      assertEquals(0, report.eligibleCount());
      assertEquals(1, report.protectedCount());
    }
  }

  @Test
  void cacheCleanupRemovesAgedIncompleteQuarantineButProtectsReplacement() throws Exception {
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
            Path jar = stagingDirectory.resolve("server.jar");
            Files.writeString(jar, "install-" + installs.incrementAndGet());
            return artifact(jar);
          }
        };
    SoftwareProfile profile = profile(SoftwareSource.PAPER);
    try (SoftwareInstallationService service = service(List.of(provider))) {
      Path installed = service.ensureInstalled(profile, "1.0").join();
      Files.writeString(installed.resolve("server.jar"), "corrupt");
      service.ensureInstalled(profile, "1.0").join();
      Path quarantine;
      try (var siblings = Files.list(installed.getParent())) {
        quarantine =
            siblings
                .filter(path -> path.getFileName().toString().startsWith(".1.0.incomplete-"))
                .findFirst()
                .orElseThrow();
      }
      FileTime old = FileTime.from(java.time.Instant.now().minus(Duration.ofDays(2)));
      Files.setLastModifiedTime(quarantine.resolve(".sls-install.properties"), old);
      Files.setLastModifiedTime(installed.resolve(".sls-install.properties"), old);

      SoftwareCacheCleanupReport report =
          service.cleanupCache(
              Duration.ofHours(24),
              false,
              true,
              Set.of(new InstallationKey("fixture", "1.0")),
              List.of(profile));

      assertEquals(1, report.removedCount());
      assertEquals(false, Files.exists(quarantine));
      assertEquals(true, Files.isDirectory(installed));
    }
  }

  @Test
  void cacheCleanupProtectsAnInstallationCurrentlyBeingPublished() throws Exception {
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
            Files.writeString(jar, version);
            return artifact(jar);
          }
        };
    SoftwareProfile profile = profile(SoftwareSource.PAPER);
    try (SoftwareInstallationService service = service(List.of(provider))) {
      var installation = service.ensureInstalled(profile, "1.0");
      assertEquals(true, entered.await(5, TimeUnit.SECONDS));
      Path expected = temporaryDirectory.resolve("software/fixture/1.0");
      Path staging;
      try (var siblings = Files.list(expected.getParent())) {
        staging =
            siblings
                .filter(path -> path.getFileName().toString().startsWith(".1.0.installing-"))
                .findFirst()
                .orElseThrow();
      }
      Files.setLastModifiedTime(
          staging.resolve(".sls-staging.properties"),
          FileTime.from(java.time.Instant.now().minus(Duration.ofDays(2))));

      SoftwareCacheCleanupReport report =
          service.cleanupCache(Duration.ofHours(24), false, true, Set.of(), List.of(profile));

      assertEquals(0, report.removedCount());
      assertEquals(1, report.protectedCount());
      assertEquals(true, Files.isDirectory(staging));
      release.countDown();
      assertEquals(expected, installation.join());
    } finally {
      release.countDown();
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

  @Test
  void reusesPreparedManualSoftwareWithoutProviderMetadata() throws Exception {
    Path target = temporaryDirectory.resolve("software/fixture/1.0");
    Files.createDirectories(target);
    Files.writeString(target.resolve("server.jar"), "operator supplied");

    try (SoftwareInstallationService service = service(List.of())) {
      assertEquals(target, service.ensureInstalled(profile(SoftwareSource.MANUAL), "1.0").join());
    }
  }

  @Test
  void blocksProviderInstallationUntilEulaIsAccepted() {
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
              java.util.function.Consumer<String> log) {
            installs.incrementAndGet();
            throw new AssertionError("provider must not run before EULA acceptance");
          }
        };
    SoftwareProfile accepted = profile(SoftwareSource.PAPER);
    SoftwareProfile notAccepted =
        new SoftwareProfile(
            accepted.id(),
            accepted.runtime(),
            accepted.configurator(),
            accepted.source(),
            accepted.channel(),
            false,
            accepted.javaExecutable(),
            accepted.javaExecutables(),
            accepted.baseDirectory(),
            accepted.serverJar(),
            accepted.jvmArguments(),
            accepted.serverArguments(),
            accepted.readinessPattern(),
            accepted.startupTimeoutSeconds(),
            accepted.stopCommand(),
            accepted.stopTimeoutSeconds());

    try (SoftwareInstallationService service = service(List.of(provider))) {
      assertThrows(Exception.class, () -> service.ensureInstalled(notAccepted, "1.0").join());
      assertEquals(0, installs.get());
    }
  }

  @Test
  void hostWideAcceptanceAllowsProviderInstallationWithoutProfileAcceptance() {
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
    SoftwareProfile accepted = profile(SoftwareSource.PAPER);
    SoftwareProfile notAccepted =
        new SoftwareProfile(
            accepted.id(),
            accepted.runtime(),
            accepted.configurator(),
            accepted.source(),
            accepted.channel(),
            false,
            accepted.javaExecutable(),
            accepted.javaExecutables(),
            accepted.baseDirectory(),
            accepted.serverJar(),
            accepted.jvmArguments(),
            accepted.serverArguments(),
            accepted.readinessPattern(),
            accepted.startupTimeoutSeconds(),
            accepted.stopCommand(),
            accepted.stopTimeoutSeconds());

    try (SoftwareInstallationService service =
        new SoftwareInstallationService(
            new JavaJarProcessSpecFactory(temporaryDirectory),
            List.of(provider),
            100,
            true,
            LoggerFactory.getLogger(getClass()))) {
      Path installed = service.ensureInstalled(notAccepted, "1.0").join();

      assertEquals(1, installs.get());
      assertEquals("eula=true" + System.lineSeparator(), read(installed.resolve("eula.txt")));
    }
  }

  @Test
  void verifiedCacheRemainsReusableWhenEffectiveEulaPolicyIsLaterFalse() {
    SoftwareInstallationProvider provider = providerWritingVersion();
    SoftwareProfile accepted = profile(SoftwareSource.PAPER);
    Path installed;
    try (SoftwareInstallationService service = service(List.of(provider))) {
      installed = service.ensureInstalled(accepted, "1.0").join();
    }
    SoftwareProfile notAccepted =
        new SoftwareProfile(
            accepted.id(),
            accepted.runtime(),
            accepted.configurator(),
            accepted.source(),
            accepted.channel(),
            false,
            accepted.javaExecutable(),
            accepted.javaExecutables(),
            accepted.baseDirectory(),
            accepted.serverJar(),
            accepted.jvmArguments(),
            accepted.serverArguments(),
            accepted.readinessPattern(),
            accepted.startupTimeoutSeconds(),
            accepted.stopCommand(),
            accepted.stopTimeoutSeconds());

    try (SoftwareInstallationService service = service(List.of(provider))) {
      assertEquals(installed, service.ensureInstalled(notAccepted, "1.0").join());
    }
  }

  private SoftwareInstallationService service(List<SoftwareInstallationProvider> providers) {
    return new SoftwareInstallationService(
        new JavaJarProcessSpecFactory(temporaryDirectory),
        providers,
        LoggerFactory.getLogger(getClass()));
  }

  private static SoftwareInstallationProvider providerWritingVersion() {
    return new SoftwareInstallationProvider() {
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
        Files.writeString(jar, version);
        return artifact(jar);
      }
    };
  }

  private static InstallationArtifact artifact(Path jar) throws Exception {
    byte[] contents = Files.readAllBytes(jar);
    String checksum =
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(contents));
    return new InstallationArtifact(contents.length, "SHA-256", checksum);
  }

  private SoftwareProfile profile(SoftwareSource source) {
    return profile("fixture", source, "software/fixture/{version}");
  }

  private SoftwareProfile profile(String id, SoftwareSource source, String baseDirectory) {
    return new SoftwareProfile(
        id,
        SoftwareRuntime.JAVA_JAR,
        SoftwareConfigurator.GENERIC,
        source,
        net.slimelabs.slslite.software.SoftwareReleaseChannel.STABLE,
        source != SoftwareSource.MANUAL,
        "java",
        java.util.Map.of(),
        baseDirectory,
        "server.jar",
        List.of("-Xmx{memory_mib}M"),
        List.of("--nogui"),
        "Done",
        30,
        "stop",
        10);
  }

  private static SoftwareProfile withPaperPin(SoftwareProfile profile, String version, long build) {
    return new SoftwareProfile(
        profile.id(),
        profile.name(),
        profile.runtime(),
        profile.configurator(),
        profile.source(),
        profile.channel(),
        profile.acceptEula(),
        profile.javaExecutable(),
        profile.javaExecutables(),
        profile.baseDirectory(),
        profile.serverJar(),
        profile.jvmArguments(),
        profile.serverArguments(),
        profile.serverProperties(),
        profile.readinessPattern(),
        profile.startupTimeoutSeconds(),
        profile.stopCommand(),
        profile.stopTimeoutSeconds(),
        profile.defaultMemoryLimitMiB(),
        profile.images(),
        profile.versionMappings(),
        profile.defaultImage(),
        java.util.Map.of(version, build));
  }

  private static String read(Path path) {
    try {
      return Files.readString(path);
    } catch (Exception exception) {
      throw new AssertionError(exception);
    }
  }
}
