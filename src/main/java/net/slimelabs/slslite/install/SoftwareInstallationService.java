package net.slimelabs.slslite.install;

import java.io.IOException;
import java.io.InputStream;
import java.nio.channels.Channels;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Collection;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Consumer;
import net.slimelabs.slslite.io.BoundedFileReader;
import net.slimelabs.slslite.io.ConfinedFiles;
import net.slimelabs.slslite.process.JavaJarProcessSpecFactory;
import net.slimelabs.slslite.process.ProcessSpecificationException;
import net.slimelabs.slslite.software.SoftwareProfile;
import net.slimelabs.slslite.software.SoftwareReleaseChannel;
import net.slimelabs.slslite.software.SoftwareSource;
import org.slf4j.Logger;

public final class SoftwareInstallationService implements AutoCloseable {

  private static final int MAX_LOG_LINES = 200;
  private static final int DEFAULT_MAX_HISTORY = 100;
  private static final Duration DEFAULT_SHUTDOWN_TIMEOUT = Duration.ofSeconds(30);
  private static final String INSTALL_METADATA = ".sls-install.properties";
  private static final String STAGING_METADATA = ".sls-staging.properties";
  private static final int MAX_SCANNED_METADATA = 10_000;
  private static final int MAX_CLEANUP_CANDIDATES = 1_000;
  private static final int MAX_REPORTED_CANDIDATES = 100;
  static final int MAX_INSTALL_METADATA_BYTES = 64 * 1024;
  static final int MAX_EULA_BYTES = 64 * 1024;

  private final JavaJarProcessSpecFactory paths;
  private final Map<SoftwareSource, SoftwareInstallationProvider> providers;
  private final Map<Path, ActiveInstallation> active = new ConcurrentHashMap<>();
  private final Map<InstallationKey, MutableInstallation> history = new ConcurrentHashMap<>();
  private final ExecutorService executor;
  private final Logger logger;
  private final int maximumHistory;
  private final boolean autoAcceptEula;
  private final AtomicBoolean closed = new AtomicBoolean();
  private final Object lifecycleLock = new Object();
  private volatile Consumer<InstallationTransition> installationObserver = ignored -> {};

  public SoftwareInstallationService(
      JavaJarProcessSpecFactory paths,
      Collection<SoftwareInstallationProvider> providers,
      Logger logger) {
    this(paths, providers, DEFAULT_MAX_HISTORY, false, logger);
  }

  public SoftwareInstallationService(
      JavaJarProcessSpecFactory paths,
      Collection<SoftwareInstallationProvider> providers,
      int maximumHistory,
      Logger logger) {
    this(paths, providers, maximumHistory, false, logger);
  }

  public SoftwareInstallationService(
      JavaJarProcessSpecFactory paths,
      Collection<SoftwareInstallationProvider> providers,
      int maximumHistory,
      boolean autoAcceptEula,
      Logger logger) {
    this.paths = paths;
    this.logger = logger;
    if (maximumHistory < 0
        || maximumHistory
            > net.slimelabs.slslite.config.DiagnosticRetentionConfig
                .MAX_INSTALLER_HISTORY_ENTRIES) {
      throw new IllegalArgumentException("maximumHistory is outside the configured bounds");
    }
    this.maximumHistory = maximumHistory;
    this.autoAcceptEula = autoAcceptEula;
    EnumMap<SoftwareSource, SoftwareInstallationProvider> indexed =
        new EnumMap<>(SoftwareSource.class);
    for (SoftwareInstallationProvider provider : providers) {
      if (indexed.putIfAbsent(provider.source(), provider) != null) {
        throw new IllegalArgumentException("Duplicate installer for " + provider.source());
      }
    }
    this.providers = Map.copyOf(indexed);
    this.executor =
        Executors.newFixedThreadPool(
            2,
            runnable -> {
              Thread thread = new Thread(runnable, "sls-lite-software-installer");
              thread.setDaemon(true);
              return thread;
            });
  }

  public CompletableFuture<Path> ensureInstalled(SoftwareProfile profile, String version) {
    InstallationKey key = new InstallationKey(profile.id(), version);
    Path target;
    try {
      target = paths.resolveBaseDirectory(profile, version);
      synchronized (lifecycleLock) {
        if (closed.get()) {
          return CompletableFuture.failedFuture(
              new SoftwareInstallationException("Software installer is shut down"));
        }
        if (isReady(profile, version, target)) {
          history.computeIfAbsent(
              key, ignored -> MutableInstallation.ready(key, "Already installed"));
          pruneHistory();
          return CompletableFuture.completedFuture(target);
        }
      }
    } catch (ProcessSpecificationException exception) {
      return CompletableFuture.failedFuture(exception);
    }
    if (profile.source() == SoftwareSource.MANUAL) {
      return CompletableFuture.failedFuture(
          new SoftwareInstallationException("Manual software is missing: " + target));
    }
    if (!profile.acceptEula() && !autoAcceptEula) {
      return CompletableFuture.failedFuture(
          new SoftwareInstallationException(
              "Automatic installation requires either host "
                  + "software.auto_accept_eula=true or profile "
                  + "software.accept_eula=true after the operator reviews the Minecraft EULA"));
    }
    Path installationTarget = target.toAbsolutePath().normalize();
    ActiveInstallation selected;
    synchronized (lifecycleLock) {
      if (closed.get()) {
        return CompletableFuture.failedFuture(
            new SoftwareInstallationException("Software installer is shut down"));
      }
      try {
        selected =
            active.computeIfAbsent(
                installationTarget,
                ignored -> {
                  MutableInstallation record = new MutableInstallation(key);
                  history.put(key, record);
                  pruneHistory();
                  observe(
                      key,
                      profile.source(),
                      profile.channel(),
                      InstallationTransitionStatus.STARTED,
                      InstallationFailureCategory.NONE);
                  try {
                    CompletableFuture<Path> future =
                        CompletableFuture.supplyAsync(
                            () -> install(profile, version, target, record), executor);
                    return new ActiveInstallation(
                        profile.id(),
                        version,
                        profile.source(),
                        profile.channel(),
                        profile.installationSelection(version),
                        record,
                        future);
                  } catch (java.util.concurrent.RejectedExecutionException exception) {
                    failRecord(record, profile.source(), profile.channel(), exception);
                    throw exception;
                  }
                });
      } catch (java.util.concurrent.RejectedExecutionException exception) {
        return CompletableFuture.failedFuture(
            new SoftwareInstallationException("Software installer is shut down", exception));
      }
    }
    if (!selected.matches(profile, version)) {
      return CompletableFuture.failedFuture(
          new SoftwareInstallationException(
              "Installation already in progress at "
                  + installationTarget
                  + " with a different software profile"));
    }
    selected
        .future()
        .whenComplete(
            (installed, failure) -> {
              active.remove(installationTarget, selected);
              pruneHistory();
            });
    return selected.future();
  }

  public List<InstallationSnapshot> snapshots() {
    return history.values().stream()
        .map(MutableInstallation::snapshot)
        .sorted(Comparator.comparing(snapshot -> snapshot.key().toString()))
        .toList();
  }

  public void installObserver(Consumer<InstallationTransition> observer) {
    synchronized (lifecycleLock) {
      if (!active.isEmpty()) {
        throw new IllegalStateException(
            "Installation observer must be installed before installation work starts");
      }
      installationObserver = java.util.Objects.requireNonNull(observer, "observer");
    }
  }

  public InstallationSnapshot snapshot(String softwareId, String version) {
    MutableInstallation installation = history.get(new InstallationKey(softwareId, version));
    return installation == null ? null : installation.snapshot();
  }

  public SoftwareCacheCleanupReport cleanupCache(
      Duration minimumAge,
      boolean dryRun,
      boolean confirmed,
      Set<InstallationKey> protectedKeys,
      Collection<SoftwareProfile> knownProfiles)
      throws SoftwareInstallationException {
    java.util.Objects.requireNonNull(minimumAge, "minimumAge");
    java.util.Objects.requireNonNull(protectedKeys, "protectedKeys");
    java.util.Objects.requireNonNull(knownProfiles, "knownProfiles");
    if (minimumAge.compareTo(Duration.ofHours(1)) < 0) {
      throw new SoftwareInstallationException(
          "Software cleanup minimum age must be at least 1 hour");
    }
    if (!dryRun && !confirmed) {
      throw new SoftwareInstallationException(
          "Software cleanup deletion requires explicit confirmation");
    }
    synchronized (lifecycleLock) {
      return cleanupCacheLocked(
          minimumAge, dryRun, protectedKeys, java.util.List.copyOf(knownProfiles));
    }
  }

  private SoftwareCacheCleanupReport cleanupCacheLocked(
      Duration minimumAge,
      boolean dryRun,
      Set<InstallationKey> protectedKeys,
      Collection<SoftwareProfile> knownProfiles)
      throws SoftwareInstallationException {
    requireCleanupNotInterrupted();
    Map<String, SoftwareProfile> profiles = new java.util.HashMap<>();
    knownProfiles.forEach(profile -> profiles.put(profile.id(), profile));
    Set<InstallationKey> protectedSnapshot = new java.util.HashSet<>(protectedKeys);
    active.values().stream()
        .map(installation -> new InstallationKey(installation.softwareId(), installation.version()))
        .forEach(protectedSnapshot::add);
    Set<Path> protectedDirectories = protectedDirectories(protectedSnapshot, profiles);
    Path dataRoot = paths.dataDirectory().toAbsolutePath().normalize();
    if (!Files.isDirectory(dataRoot)) {
      return new SoftwareCacheCleanupReport(dryRun, List.of(), List.of(), 0, 0, 0, 0, false);
    }

    List<CacheCandidate> candidates = new java.util.ArrayList<>();
    int protectedCount = 0;
    int tooNewCount = 0;
    int scanned = 0;
    boolean scanLimitReached = false;
    Instant cutoff = Instant.now().minus(minimumAge);
    try (var files =
        Files.find(
            dataRoot,
            12,
            (path, attributes) ->
                attributes.isRegularFile()
                    && (INSTALL_METADATA.equals(path.getFileName().toString())
                        || STAGING_METADATA.equals(path.getFileName().toString())))) {
      java.util.Iterator<Path> iterator = files.iterator();
      while (iterator.hasNext()) {
        requireCleanupNotInterrupted();
        if (scanned >= MAX_SCANNED_METADATA || candidates.size() >= MAX_CLEANUP_CANDIDATES) {
          scanLimitReached = true;
          break;
        }
        Path metadataPath = iterator.next();
        scanned++;
        CacheCandidate candidate = cacheCandidate(dataRoot, metadataPath, profiles);
        if (candidate == null) {
          continue;
        }
        if (candidate.kind() != CacheCandidateKind.QUARANTINE
            && (protectedSnapshot.contains(candidate.key())
                || protectedDirectories.contains(candidate.directory()))) {
          protectedCount++;
        } else if (candidate.modifiedAt().isAfter(cutoff)) {
          tooNewCount++;
        } else {
          candidates.add(candidate);
        }
      }
    } catch (IOException | java.io.UncheckedIOException exception) {
      throw new SoftwareInstallationException("Unable to inspect software cache", exception);
    }

    List<SoftwareCacheCleanupReport.Entry> eligible =
        candidates.stream()
            .limit(MAX_REPORTED_CANDIDATES)
            .map(
                candidate ->
                    new SoftwareCacheCleanupReport.Entry(candidate.key(), candidate.directory()))
            .toList();
    List<SoftwareCacheCleanupReport.Entry> removed = new java.util.ArrayList<>();
    int removedCount = 0;
    if (!dryRun) {
      for (CacheCandidate candidate : candidates) {
        requireCleanupNotInterrupted();
        try {
          deleteCacheDirectory(dataRoot, candidate.directory());
          removedCount++;
          if (removed.size() < MAX_REPORTED_CANDIDATES) {
            removed.add(
                new SoftwareCacheCleanupReport.Entry(candidate.key(), candidate.directory()));
          }
        } catch (IOException exception) {
          throw new SoftwareInstallationException(
              "Unable to remove software cache " + candidate.key() + ": " + exception.getMessage(),
              exception);
        }
      }
    }
    return new SoftwareCacheCleanupReport(
        dryRun,
        eligible,
        removed,
        candidates.size(),
        removedCount,
        protectedCount,
        tooNewCount,
        scanLimitReached);
  }

  private static void requireCleanupNotInterrupted() throws SoftwareInstallationException {
    if (Thread.currentThread().isInterrupted()) {
      throw new SoftwareInstallationException("Software cleanup was interrupted");
    }
  }

  @Override
  public void close() {
    shutdown(DEFAULT_SHUTDOWN_TIMEOUT);
  }

  public void shutdown(Duration timeout) {
    java.util.Objects.requireNonNull(timeout, "timeout");
    synchronized (lifecycleLock) {
      if (!closed.compareAndSet(false, true)) {
        return;
      }
      active
          .values()
          .forEach(
              installation -> {
                if (installation.record().cancelled()) {
                  observe(
                      installation.record().key,
                      installation.source(),
                      installation.channel(),
                      InstallationTransitionStatus.CANCELLED,
                      InstallationFailureCategory.CANCELLED);
                }
                installation.future().cancel(true);
              });
      executor.shutdownNow();
    }
    try {
      if (!executor.awaitTermination(Math.max(0L, timeout.toNanos()), TimeUnit.NANOSECONDS)) {
        logger.warn("Timed out waiting for software installer tasks to stop");
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      logger.warn("Interrupted while waiting for software installer tasks to stop");
    }
  }

  private Path install(
      SoftwareProfile profile, String version, Path target, MutableInstallation record) {
    SoftwareInstallationProvider provider = providers.get(profile.source());
    if (provider == null) {
      throw fail(
          record,
          profile.source(),
          profile.channel(),
          new SoftwareInstallationException("No installer supports source " + profile.source()));
    }
    Path staging =
        target.resolveSibling("." + target.getFileName() + ".installing-" + System.nanoTime());
    Path quarantined = null;
    try {
      logger.info(
          "Software installation started: {} {} from {} ({})",
          profile.id(),
          version,
          profile.source().name().toLowerCase(Locale.ROOT),
          profile.channel().name().toLowerCase(Locale.ROOT));
      if (Files.exists(target, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
        quarantined = quarantineIncomplete(target);
        record.log("Preserved incomplete cache at " + quarantined.getFileName());
        logger.warn("Preserved incomplete software cache {} at {}", target, quarantined);
      }
      cleanAbandonedStaging(target);
      Files.createDirectories(staging);
      writeStagingMetadata(profile, version, staging);
      record.log(
          "Installing "
              + profile.id()
              + " "
              + version
              + " with "
              + profile.source().name().toLowerCase());
      InstallationArtifact artifact = provider.install(profile, version, staging, record::log);
      ConfinedFiles.atomicWrite(
          staging,
          "eula.txt",
          ("eula=true" + System.lineSeparator()).getBytes(java.nio.charset.StandardCharsets.UTF_8),
          MAX_EULA_BYTES);
      writeInstallMetadata(profile, version, staging, artifact);
      Files.deleteIfExists(staging.resolve(STAGING_METADATA));
      if (!isReady(profile, version, staging)) {
        throw new SoftwareInstallationException("Installer output failed cache verification");
      }
      Files.createDirectories(target.getParent());
      moveDirectory(staging, target);
      if (record.ready("Installed at " + target)) {
        observe(
            record.key,
            profile.source(),
            profile.channel(),
            InstallationTransitionStatus.READY,
            InstallationFailureCategory.NONE);
      }
      logger.info("Installed software {} {} at {}", profile.id(), version, target);
      return target;
    } catch (Exception exception) {
      deleteRecursively(staging);
      if (quarantined != null && !Files.exists(target)) {
        try {
          moveDirectory(quarantined, target);
          record.log("Restored incomplete cache after replacement failed");
        } catch (IOException restoreFailure) {
          exception.addSuppressed(restoreFailure);
          logger.error(
              "Unable to restore incomplete software cache {} from {}",
              target,
              quarantined,
              restoreFailure);
        }
      }
      throw fail(record, profile.source(), profile.channel(), exception);
    }
  }

  private static Path quarantineIncomplete(Path target) throws IOException {
    Path parent = target.getParent();
    if (parent == null) {
      throw new IOException("Installation target has no parent: " + target);
    }
    for (int attempt = 0; attempt < 100; attempt++) {
      Path quarantine =
          parent.resolve(
              "."
                  + target.getFileName()
                  + ".incomplete-"
                  + Long.toUnsignedString(System.nanoTime())
                  + "-"
                  + attempt);
      if (Files.exists(quarantine)) {
        continue;
      }
      moveDirectory(target, quarantine);
      return quarantine;
    }
    throw new IOException("Unable to allocate incomplete-cache quarantine beside " + target);
  }

  private boolean isReady(SoftwareProfile profile, String version, Path directory) {
    if (Files.isSymbolicLink(directory) || !Files.isDirectory(directory)) {
      return false;
    }
    Path jar = directory.resolve(profile.serverJar()).normalize();
    if (!jar.startsWith(directory.normalize())
        || Files.isSymbolicLink(jar)
        || !Files.isRegularFile(jar)) {
      return false;
    }
    if (profile.source() == SoftwareSource.MANUAL) {
      return true;
    }

    Path metadataPath = directory.resolve(INSTALL_METADATA);
    Path eulaPath = directory.resolve("eula.txt");
    if (Files.isSymbolicLink(metadataPath)
        || !Files.isRegularFile(metadataPath)
        || Files.isSymbolicLink(eulaPath)
        || !Files.isRegularFile(eulaPath)) {
      return false;
    }
    try {
      if (!BoundedFileReader.readStringNoFollow(
              eulaPath, java.nio.charset.StandardCharsets.UTF_8, MAX_EULA_BYTES)
          .lines()
          .map(String::trim)
          .anyMatch("eula=true"::equalsIgnoreCase)) {
        return false;
      }
      Properties metadata = new Properties();
      try (InputStream input =
          BoundedFileReader.openNoFollow(metadataPath, MAX_INSTALL_METADATA_BYTES)) {
        metadata.load(input);
      }
      if (!"1".equals(metadata.getProperty("format"))
          || !profile.id().equals(metadata.getProperty("software"))
          || !version.equals(metadata.getProperty("version"))
          || !profile.source().name().equals(metadata.getProperty("source"))
          || !profile.channel().name().equals(metadata.getProperty("channel"))
          || !selectionMatches(profile, version, metadata.getProperty("selection"))
          || !profile.serverJar().equals(metadata.getProperty("jar"))) {
        return false;
      }
      long expectedSize = Long.parseLong(metadata.getProperty("size", ""));
      String algorithm = metadata.getProperty("digest");
      if (!"SHA-1".equals(algorithm) && !"SHA-256".equals(algorithm)) {
        return false;
      }
      String expectedChecksum = metadata.getProperty("checksum", "");
      if (expectedSize != Files.size(jar)) {
        return false;
      }
      return MessageDigest.isEqual(
          digest(jar, algorithm).getBytes(java.nio.charset.StandardCharsets.US_ASCII),
          expectedChecksum.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
    } catch (Exception exception) {
      return false;
    }
  }

  private static void writeInstallMetadata(
      SoftwareProfile profile, String version, Path directory, InstallationArtifact artifact)
      throws IOException {
    Properties metadata = new Properties();
    metadata.setProperty("format", "1");
    metadata.setProperty("software", profile.id());
    metadata.setProperty("version", version);
    metadata.setProperty("source", profile.source().name());
    metadata.setProperty("channel", profile.channel().name());
    metadata.setProperty("selection", profile.installationSelection(version));
    metadata.setProperty("jar", profile.serverJar());
    metadata.setProperty("size", Long.toString(artifact.size()));
    metadata.setProperty("digest", artifact.digestAlgorithm());
    metadata.setProperty("checksum", artifact.checksum());
    ConfinedFiles.atomicWriteProperties(
        directory,
        INSTALL_METADATA,
        metadata,
        "SLS-LITE verified software cache",
        MAX_INSTALL_METADATA_BYTES);
  }

  private static void writeStagingMetadata(SoftwareProfile profile, String version, Path directory)
      throws IOException {
    Properties metadata = new Properties();
    metadata.setProperty("format", "1");
    metadata.setProperty("software", profile.id());
    metadata.setProperty("version", version);
    metadata.setProperty("source", profile.source().name());
    metadata.setProperty("channel", profile.channel().name());
    metadata.setProperty("selection", profile.installationSelection(version));
    ConfinedFiles.atomicWriteProperties(
        directory,
        STAGING_METADATA,
        metadata,
        "SLS-LITE software staging ownership",
        MAX_INSTALL_METADATA_BYTES);
  }

  private Set<Path> protectedDirectories(
      Set<InstallationKey> protectedKeys, Map<String, SoftwareProfile> profiles) {
    Set<Path> protectedPaths = new java.util.HashSet<>();
    for (InstallationKey key : protectedKeys) {
      SoftwareProfile profile = profiles.get(key.softwareId());
      if (profile == null) {
        continue;
      }
      try {
        Path path = paths.resolveBaseDirectory(profile, key.version()).toAbsolutePath().normalize();
        protectedPaths.add(Files.exists(path) ? path.toRealPath() : path);
      } catch (IOException | ProcessSpecificationException ignored) {
        // The logical key still protects a matching cache even when its current path is invalid.
      }
    }
    return Set.copyOf(protectedPaths);
  }

  private CacheCandidate cacheCandidate(
      Path dataRoot, Path metadataPath, Map<String, SoftwareProfile> profiles) {
    try {
      if (Files.isSymbolicLink(metadataPath)) {
        return null;
      }
      Path directory = metadataPath.getParent().toAbsolutePath().normalize();
      Path realRoot = dataRoot.toRealPath();
      Path realDirectory = directory.toRealPath();
      if (realDirectory.equals(realRoot) || !realDirectory.startsWith(realRoot)) {
        return null;
      }
      Properties metadata = new Properties();
      try (InputStream input =
          BoundedFileReader.openNoFollow(metadataPath, MAX_INSTALL_METADATA_BYTES)) {
        metadata.load(input);
      }
      if (!"1".equals(metadata.getProperty("format"))) {
        return null;
      }
      String software = metadata.getProperty("software");
      String version = metadata.getProperty("version");
      if (software == null || software.isBlank() || version == null || version.isBlank()) {
        return null;
      }
      SoftwareProfile profile = profiles.get(software);
      if (profile == null
          || profile.source() == SoftwareSource.MANUAL
          || !profile.source().name().equals(metadata.getProperty("source"))
          || !profile.channel().name().equals(metadata.getProperty("channel"))) {
        return null;
      }
      Path expected = paths.resolveBaseDirectory(profile, version).toAbsolutePath().normalize();
      Path expectedParent = expected.getParent();
      if (expectedParent == null || !Files.isDirectory(expectedParent)) {
        return null;
      }
      Path realExpectedParent = expectedParent.toRealPath();
      String directoryName = directory.getFileName().toString();
      String expectedName = expected.getFileName().toString();
      boolean staging = STAGING_METADATA.equals(metadataPath.getFileName().toString());
      CacheCandidateKind kind;
      if (staging) {
        if (!realDirectory.getParent().equals(realExpectedParent)
            || !directoryName.startsWith("." + expectedName + ".installing-")) {
          return null;
        }
        kind = CacheCandidateKind.STAGING;
      } else if (Files.exists(expected)
          && realDirectory.equals(expected.toRealPath())
          && profile.serverJar().equals(metadata.getProperty("jar"))) {
        kind = CacheCandidateKind.CACHE;
      } else {
        if (!realDirectory.getParent().equals(realExpectedParent)
            || !directoryName.startsWith("." + expectedName + ".incomplete-")
            || !profile.serverJar().equals(metadata.getProperty("jar"))) {
          return null;
        }
        kind = CacheCandidateKind.QUARANTINE;
      }
      return new CacheCandidate(
          new InstallationKey(software, version),
          realDirectory,
          Files.getLastModifiedTime(metadataPath).toInstant(),
          kind);
    } catch (IOException | ProcessSpecificationException exception) {
      return null;
    }
  }

  private static void deleteCacheDirectory(Path dataRoot, Path directory) throws IOException {
    Path realRoot = dataRoot.toRealPath();
    Path realDirectory = directory.toRealPath();
    if (realDirectory.equals(realRoot) || !realDirectory.startsWith(realRoot)) {
      throw new IOException("Cache directory escapes the SLS-LITE data root");
    }
    Path tombstone =
        realDirectory.resolveSibling(
            "." + realDirectory.getFileName() + ".cleanup-" + System.nanoTime());
    moveDirectory(realDirectory, tombstone);
    try {
      try (var entries = Files.walk(tombstone)) {
        for (Path entry : entries.sorted(Comparator.reverseOrder()).toList()) {
          Files.delete(entry);
        }
      }
    } catch (IOException deletionFailure) {
      if (Files.exists(tombstone, java.nio.file.LinkOption.NOFOLLOW_LINKS)
          && !Files.exists(realDirectory, java.nio.file.LinkOption.NOFOLLOW_LINKS)) {
        try {
          moveDirectory(tombstone, realDirectory);
        } catch (IOException restoreFailure) {
          deletionFailure.addSuppressed(restoreFailure);
        }
      }
      throw deletionFailure;
    }
  }

  private static String digest(Path path, String algorithm) throws Exception {
    MessageDigest digest = MessageDigest.getInstance(algorithm);
    try (var channel =
            Files.newByteChannel(path, StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        InputStream input = Channels.newInputStream(channel)) {
      byte[] buffer = new byte[8192];
      int read;
      while ((read = input.read(buffer)) >= 0) {
        digest.update(buffer, 0, read);
      }
    }
    return HexFormat.of().formatHex(digest.digest()).toLowerCase(Locale.ROOT);
  }

  private static boolean selectionMatches(
      SoftwareProfile profile, String version, String cachedSelection) {
    if (cachedSelection != null) {
      return profile.installationSelection(version).equals(cachedSelection);
    }
    // Format-1 caches created before build pins existed used the same newest-allowed policy.
    return profile.paperBuildForVersion(version).isEmpty();
  }

  private static void moveDirectory(Path source, Path target) throws IOException {
    try {
      Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
    } catch (AtomicMoveNotSupportedException exception) {
      Files.move(source, target);
    }
  }

  private void pruneHistory() {
    if (history.size() <= maximumHistory) {
      return;
    }
    history.values().stream()
        .filter(value -> value.snapshot().state() != InstallationState.INSTALLING)
        .sorted(Comparator.comparing(value -> value.snapshot().startedAt()))
        .limit(history.size() - maximumHistory)
        .forEach(value -> history.remove(value.key, value));
  }

  private static void cleanAbandonedStaging(Path target) {
    Path parent = target.getParent();
    if (parent == null || !Files.isDirectory(parent)) {
      return;
    }
    String prefix = "." + target.getFileName() + ".installing-";
    try (var entries = Files.list(parent)) {
      entries
          .filter(path -> path.getFileName().toString().startsWith(prefix))
          .forEach(SoftwareInstallationService::deleteRecursively);
    } catch (IOException ignored) {
    }
  }

  private CompletionException fail(
      MutableInstallation record,
      SoftwareSource source,
      SoftwareReleaseChannel channel,
      Exception exception) {
    String detail = rootMessage(exception);
    if (failRecord(record, source, channel, exception)) {
      logger.warn("Software installation {} failed: {}", record.key, detail);
    }
    return new CompletionException(new SoftwareInstallationException(detail, exception));
  }

  private boolean failRecord(
      MutableInstallation record,
      SoftwareSource source,
      SoftwareReleaseChannel channel,
      Throwable failure) {
    if (record.failed(rootMessage(failure))) {
      observe(
          record.key,
          source,
          channel,
          InstallationTransitionStatus.FAILED,
          failureCategory(failure));
      return true;
    }
    return false;
  }

  private void observe(
      InstallationKey key,
      SoftwareSource source,
      SoftwareReleaseChannel channel,
      InstallationTransitionStatus status,
      InstallationFailureCategory failureCategory) {
    try {
      installationObserver.accept(
          new InstallationTransition(key, source, channel, status, failureCategory, Instant.now()));
    } catch (RuntimeException ignored) {
      // Observability must never alter installation ownership or completion.
    }
  }

  private static InstallationFailureCategory failureCategory(Throwable failure) {
    Throwable root = failure;
    while (root.getCause() != null) {
      root = root.getCause();
    }
    if (root instanceof IOException) {
      return InstallationFailureCategory.IO;
    }
    if (root instanceof SoftwareInstallationException) {
      return InstallationFailureCategory.INSTALLER;
    }
    return InstallationFailureCategory.INTERNAL;
  }

  private static void deleteRecursively(Path path) {
    if (!Files.exists(path)) {
      return;
    }
    try (var entries = Files.walk(path)) {
      entries
          .sorted(Comparator.reverseOrder())
          .forEach(
              entry -> {
                try {
                  Files.deleteIfExists(entry);
                } catch (IOException ignored) {
                }
              });
    } catch (IOException ignored) {
    }
  }

  private static String rootMessage(Throwable failure) {
    Throwable current = failure;
    while (current.getCause() != null) {
      current = current.getCause();
    }
    return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
  }

  private static final class MutableInstallation {
    private final InstallationKey key;
    private final Instant startedAt;
    private final ArrayDeque<String> logs = new ArrayDeque<>();
    private InstallationState state;
    private String detail;
    private Instant completedAt;

    private MutableInstallation(InstallationKey key) {
      this.key = key;
      this.startedAt = Instant.now();
      this.state = InstallationState.INSTALLING;
      this.detail = "Preparing installation";
    }

    private static MutableInstallation ready(InstallationKey key, String detail) {
      MutableInstallation installation = new MutableInstallation(key);
      installation.ready(detail);
      return installation;
    }

    private synchronized void log(String line) {
      if (logs.size() == MAX_LOG_LINES) {
        logs.removeFirst();
      }
      logs.addLast(line);
      detail = line;
    }

    private synchronized boolean ready(String message) {
      if (state != InstallationState.INSTALLING) {
        return false;
      }
      log(message);
      state = InstallationState.READY;
      completedAt = Instant.now();
      return true;
    }

    private synchronized boolean failed(String message) {
      if (state != InstallationState.INSTALLING) {
        return false;
      }
      log("Failed: " + message);
      state = InstallationState.FAILED;
      completedAt = Instant.now();
      return true;
    }

    private synchronized boolean cancelled() {
      if (state != InstallationState.INSTALLING) {
        return false;
      }
      log("Cancelled during shutdown");
      state = InstallationState.FAILED;
      completedAt = Instant.now();
      return true;
    }

    private synchronized InstallationSnapshot snapshot() {
      return new InstallationSnapshot(
          key, state, detail, startedAt, completedAt, List.copyOf(logs));
    }
  }

  private record ActiveInstallation(
      String softwareId,
      String version,
      SoftwareSource source,
      SoftwareReleaseChannel channel,
      String selection,
      MutableInstallation record,
      CompletableFuture<Path> future) {
    private boolean matches(SoftwareProfile profile, String requestedVersion) {
      return softwareId.equals(profile.id())
          && version.equals(requestedVersion)
          && source == profile.source()
          && channel == profile.channel()
          && selection.equals(profile.installationSelection(requestedVersion));
    }
  }

  public enum InstallationTransitionStatus {
    STARTED,
    READY,
    FAILED,
    CANCELLED
  }

  public enum InstallationFailureCategory {
    NONE,
    IO,
    INSTALLER,
    INTERNAL,
    CANCELLED
  }

  public record InstallationTransition(
      InstallationKey key,
      SoftwareSource source,
      SoftwareReleaseChannel channel,
      InstallationTransitionStatus status,
      InstallationFailureCategory failureCategory,
      Instant occurredAt) {

    public InstallationTransition {
      java.util.Objects.requireNonNull(key, "key");
      java.util.Objects.requireNonNull(source, "source");
      java.util.Objects.requireNonNull(channel, "channel");
      java.util.Objects.requireNonNull(status, "status");
      java.util.Objects.requireNonNull(failureCategory, "failureCategory");
      java.util.Objects.requireNonNull(occurredAt, "occurredAt");
      boolean normal =
          status == InstallationTransitionStatus.STARTED
              || status == InstallationTransitionStatus.READY;
      if (normal != (failureCategory == InstallationFailureCategory.NONE)) {
        throw new IllegalArgumentException(
            "started/ready transitions require NONE; failed/cancelled require a category");
      }
      if ((status == InstallationTransitionStatus.CANCELLED)
          != (failureCategory == InstallationFailureCategory.CANCELLED)) {
        throw new IllegalArgumentException("cancelled status and category must match");
      }
    }
  }

  private record CacheCandidate(
      InstallationKey key, Path directory, Instant modifiedAt, CacheCandidateKind kind) {}

  private enum CacheCandidateKind {
    CACHE,
    QUARANTINE,
    STAGING
  }
}
