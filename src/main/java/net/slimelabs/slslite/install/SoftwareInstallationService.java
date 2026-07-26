package net.slimelabs.slslite.install;

import net.slimelabs.slslite.process.JavaJarProcessSpecFactory;
import net.slimelabs.slslite.process.ProcessSpecificationException;
import net.slimelabs.slslite.software.SoftwareProfile;
import net.slimelabs.slslite.software.SoftwareSource;
import org.slf4j.Logger;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.nio.file.attribute.FileTime;
import java.security.MessageDigest;
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
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public final class SoftwareInstallationService implements AutoCloseable {

    private static final int MAX_LOG_LINES = 200;
    private static final int MAX_HISTORY = 100;
    private static final String INSTALL_METADATA = ".sls-install.properties";

    private final JavaJarProcessSpecFactory paths;
    private final Map<SoftwareSource, SoftwareInstallationProvider> providers;
    private final Map<Path, ActiveInstallation> active =
            new ConcurrentHashMap<>();
    private final Map<InstallationKey, MutableInstallation> history =
            new ConcurrentHashMap<>();
    private final Map<Path, ValidatedArtifact> validatedArtifacts =
            new ConcurrentHashMap<>();
    private final ExecutorService executor;
    private final Logger logger;

    public SoftwareInstallationService(
            JavaJarProcessSpecFactory paths,
            Collection<SoftwareInstallationProvider> providers,
            Logger logger
    ) {
        this.paths = paths;
        this.logger = logger;
        EnumMap<SoftwareSource, SoftwareInstallationProvider> indexed =
                new EnumMap<>(SoftwareSource.class);
        for (SoftwareInstallationProvider provider : providers) {
            if (indexed.putIfAbsent(provider.source(), provider) != null) {
                throw new IllegalArgumentException(
                        "Duplicate installer for " + provider.source()
                );
            }
        }
        this.providers = Map.copyOf(indexed);
        this.executor = Executors.newFixedThreadPool(2, runnable -> {
            Thread thread = new Thread(runnable, "sls-lite-software-installer");
            thread.setDaemon(true);
            return thread;
        });
    }

    public CompletableFuture<Path> ensureInstalled(
            SoftwareProfile profile,
            String version
    ) {
        InstallationKey key = new InstallationKey(profile.id(), version);
        Path target;
        try {
            target = paths.resolveBaseDirectory(profile, version);
            if (isReady(profile, version, target)) {
                history.computeIfAbsent(
                        key,
                        ignored -> MutableInstallation.ready(key, "Already installed")
                );
                pruneHistory();
                return CompletableFuture.completedFuture(target);
            }
        } catch (ProcessSpecificationException exception) {
            return CompletableFuture.failedFuture(exception);
        }
        if (profile.source() == SoftwareSource.MANUAL) {
            return CompletableFuture.failedFuture(new SoftwareInstallationException(
                    "Manual software is missing: " + target
            ));
        }
        if (!profile.acceptEula()) {
            return CompletableFuture.failedFuture(
                    new SoftwareInstallationException(
                            "Automatic installation requires software.accept_eula=true "
                                    + "after the operator reviews the Minecraft EULA"
                    )
            );
        }
        Path installationTarget = target.toAbsolutePath().normalize();
        ActiveInstallation selected = active.computeIfAbsent(
                installationTarget,
                ignored -> {
            MutableInstallation record = new MutableInstallation(key);
            history.put(key, record);
            pruneHistory();
            CompletableFuture<Path> future = CompletableFuture.supplyAsync(
                    () -> install(profile, version, target, record),
                    executor
            );
            return new ActiveInstallation(
                    profile.id(),
                    version,
                    profile.source(),
                    profile.channel().name(),
                    future
            );
        });
        if (!selected.matches(profile, version)) {
            return CompletableFuture.failedFuture(
                    new SoftwareInstallationException(
                            "Installation already in progress at "
                                    + installationTarget
                                    + " with a different software profile"
                    )
            );
        }
        selected.future().whenComplete((installed, failure) -> {
            active.remove(installationTarget, selected);
            pruneHistory();
        });
        return selected.future();
    }

    public List<InstallationSnapshot> snapshots() {
        return history.values().stream()
                .map(MutableInstallation::snapshot)
                .sorted(Comparator.comparing(
                        snapshot -> snapshot.key().toString()
                ))
                .toList();
    }

    public InstallationSnapshot snapshot(String softwareId, String version) {
        MutableInstallation installation = history.get(
                new InstallationKey(softwareId, version)
        );
        return installation == null ? null : installation.snapshot();
    }

    @Override
    public void close() {
        executor.shutdownNow();
    }

    private Path install(
            SoftwareProfile profile,
            String version,
            Path target,
            MutableInstallation record
    ) {
        SoftwareInstallationProvider provider = providers.get(profile.source());
        if (provider == null) {
            throw fail(
                    record,
                    new SoftwareInstallationException(
                            "No installer supports source " + profile.source()
                    )
            );
        }
        Path staging = target.resolveSibling(
                "." + target.getFileName() + ".installing-" + System.nanoTime()
        );
        try {
            if (Files.exists(target)) {
                throw new SoftwareInstallationException(
                        "Installation target exists but is incomplete: " + target
                );
            }
            cleanAbandonedStaging(target);
            Files.createDirectories(staging);
            record.log("Installing " + profile.id() + " " + version
                    + " with " + profile.source().name().toLowerCase());
            InstallationArtifact artifact = provider.install(
                    profile,
                    version,
                    staging,
                    record::log
            );
            Files.writeString(
                    staging.resolve("eula.txt"),
                    "eula=true" + System.lineSeparator()
            );
            writeInstallMetadata(profile, version, staging, artifact);
            if (!isReady(profile, version, staging)) {
                throw new SoftwareInstallationException(
                        "Installer output failed cache verification"
                );
            }
            Files.createDirectories(target.getParent());
            moveDirectory(staging, target);
            validatedArtifacts.remove(
                    staging.resolve(profile.serverJar()).normalize()
            );
            record.ready("Installed at " + target);
            logger.info("Installed software {} {} at {}", profile.id(), version, target);
            return target;
        } catch (Exception exception) {
            deleteRecursively(staging);
            throw fail(record, exception);
        }
    }

    private boolean isReady(
            SoftwareProfile profile,
            String version,
            Path directory
    ) {
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
            if (!Files.readString(eulaPath).lines()
                    .map(String::trim)
                    .anyMatch("eula=true"::equalsIgnoreCase)) {
                return false;
            }
            Properties metadata = new Properties();
            try (InputStream input = Files.newInputStream(metadataPath)) {
                metadata.load(input);
            }
            if (!"1".equals(metadata.getProperty("format"))
                    || !profile.id().equals(metadata.getProperty("software"))
                    || !version.equals(metadata.getProperty("version"))
                    || !profile.source().name().equals(
                            metadata.getProperty("source")
                    )
                    || !profile.channel().name().equals(
                            metadata.getProperty("channel")
                    )
                    || !profile.serverJar().equals(metadata.getProperty("jar"))) {
                return false;
            }
            long expectedSize = Long.parseLong(metadata.getProperty("size", ""));
            String algorithm = metadata.getProperty("digest");
            if (!"SHA-1".equals(algorithm) && !"SHA-256".equals(algorithm)) {
                return false;
            }
            String expectedChecksum = metadata.getProperty("checksum", "");
            BasicFileAttributes attributes = Files.readAttributes(
                    jar,
                    BasicFileAttributes.class,
                    java.nio.file.LinkOption.NOFOLLOW_LINKS
            );
            if (expectedSize != attributes.size()) {
                validatedArtifacts.remove(jar);
                return false;
            }
            ValidatedArtifact expected = new ValidatedArtifact(
                    expectedSize,
                    attributes.lastModifiedTime(),
                    algorithm,
                    expectedChecksum
            );
            if (expected.equals(validatedArtifacts.get(jar))) {
                return true;
            }
            boolean matches = MessageDigest.isEqual(
                            digest(jar, algorithm).getBytes(
                                    java.nio.charset.StandardCharsets.US_ASCII
                            ),
                            expectedChecksum.getBytes(
                                    java.nio.charset.StandardCharsets.US_ASCII
                            )
                    );
            if (matches) {
                validatedArtifacts.put(jar, expected);
            } else {
                validatedArtifacts.remove(jar);
            }
            return matches;
        } catch (Exception exception) {
            validatedArtifacts.remove(jar);
            return false;
        }
    }

    private static void writeInstallMetadata(
            SoftwareProfile profile,
            String version,
            Path directory,
            InstallationArtifact artifact
    ) throws IOException {
        Properties metadata = new Properties();
        metadata.setProperty("format", "1");
        metadata.setProperty("software", profile.id());
        metadata.setProperty("version", version);
        metadata.setProperty("source", profile.source().name());
        metadata.setProperty("channel", profile.channel().name());
        metadata.setProperty("jar", profile.serverJar());
        metadata.setProperty("size", Long.toString(artifact.size()));
        metadata.setProperty("digest", artifact.digestAlgorithm());
        metadata.setProperty("checksum", artifact.checksum());
        try (OutputStream output = Files.newOutputStream(
                directory.resolve(INSTALL_METADATA)
        )) {
            metadata.store(output, "SLS-LITE verified software cache");
        }
    }

    private static String digest(Path path, String algorithm) throws Exception {
        MessageDigest digest = MessageDigest.getInstance(algorithm);
        try (InputStream input = Files.newInputStream(path)) {
            byte[] buffer = new byte[8192];
            int read;
            while ((read = input.read(buffer)) >= 0) {
                digest.update(buffer, 0, read);
            }
        }
        return HexFormat.of().formatHex(digest.digest())
                .toLowerCase(Locale.ROOT);
    }

    private static void moveDirectory(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target);
        }
    }

    private void pruneHistory() {
        if (history.size() <= MAX_HISTORY) {
            return;
        }
        history.values().stream()
                .filter(value -> value.snapshot().state()
                        != InstallationState.INSTALLING)
                .sorted(Comparator.comparing(
                        value -> value.snapshot().startedAt()
                ))
                .limit(history.size() - MAX_HISTORY)
                .forEach(value -> history.remove(value.key, value));
    }

    private static void cleanAbandonedStaging(Path target) {
        Path parent = target.getParent();
        if (parent == null || !Files.isDirectory(parent)) {
            return;
        }
        String prefix = "." + target.getFileName() + ".installing-";
        try (var entries = Files.list(parent)) {
            entries.filter(path -> path.getFileName().toString().startsWith(prefix))
                    .forEach(SoftwareInstallationService::deleteRecursively);
        } catch (IOException ignored) {
        }
    }

    private CompletionException fail(
            MutableInstallation record,
            Exception exception
    ) {
        String detail = rootMessage(exception);
        record.failed(detail);
        logger.warn("Software installation {} failed: {}", record.key, detail);
        return new CompletionException(new SoftwareInstallationException(
                detail,
                exception
        ));
    }

    private static void deleteRecursively(Path path) {
        if (!Files.exists(path)) {
            return;
        }
        try (var entries = Files.walk(path)) {
            entries.sorted(Comparator.reverseOrder()).forEach(entry -> {
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
        return current.getMessage() == null
                ? current.getClass().getSimpleName()
                : current.getMessage();
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

        private static MutableInstallation ready(
                InstallationKey key,
                String detail
        ) {
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

        private synchronized void ready(String message) {
            log(message);
            state = InstallationState.READY;
            completedAt = Instant.now();
        }

        private synchronized void failed(String message) {
            log("Failed: " + message);
            state = InstallationState.FAILED;
            completedAt = Instant.now();
        }

        private synchronized InstallationSnapshot snapshot() {
            return new InstallationSnapshot(
                    key,
                    state,
                    detail,
                    startedAt,
                    completedAt,
                    List.copyOf(logs)
            );
        }
    }

    private record ActiveInstallation(
            String softwareId,
            String version,
            SoftwareSource source,
            String channel,
            CompletableFuture<Path> future
    ) {
        private boolean matches(SoftwareProfile profile, String requestedVersion) {
            return softwareId.equals(profile.id())
                    && version.equals(requestedVersion)
                    && source == profile.source()
                    && channel.equals(profile.channel().name());
        }
    }

    private record ValidatedArtifact(
            long size,
            FileTime modifiedAt,
            String digestAlgorithm,
            String checksum
    ) {
    }
}
