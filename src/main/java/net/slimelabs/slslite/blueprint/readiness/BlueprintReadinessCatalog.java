package net.slimelabs.slslite.blueprint.readiness;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.Set;
import net.slimelabs.slslite.api.BlueprintReadinessStatus;
import net.slimelabs.slslite.blueprint.Blueprint;
import net.slimelabs.slslite.config.SLSConfig;
import net.slimelabs.slslite.config.StorageStrategy;
import net.slimelabs.slslite.instance.InstanceOperationException;
import net.slimelabs.slslite.instance.storage.BlueprintContentPreflight;
import net.slimelabs.slslite.io.BoundedFileReader;
import net.slimelabs.slslite.process.JavaJarProcessSpecFactory;
import net.slimelabs.slslite.process.ProcessSpecificationException;
import net.slimelabs.slslite.software.SoftwareProfile;
import net.slimelabs.slslite.software.SoftwareSource;

/** Atomic, read-only readiness snapshot for the currently loaded blueprint catalog. */
public final class BlueprintReadinessCatalog {

  private static final int MAXIMUM_ISSUES_PER_BLUEPRINT = 8;
  private static final int MAXIMUM_METADATA_BYTES = 64 * 1024;
  private static final int MAXIMUM_PATH_ENTRIES = 128;

  private final SLSConfig config;
  private final JavaJarProcessSpecFactory processSpecs;
  private final BlueprintContentPreflight content;
  private final Set<SoftwareSource> providerSources;
  private final ExtensionBlueprintReadinessRegistry extensionReadiness;
  private volatile Map<String, BlueprintReadinessReport> reports = Map.of();

  public BlueprintReadinessCatalog(
      SLSConfig config,
      Path contentRoot,
      JavaJarProcessSpecFactory processSpecs,
      Set<SoftwareSource> providerSources,
      StorageStrategy selectedStorageStrategy) {
    this(
        config,
        contentRoot,
        processSpecs,
        providerSources,
        selectedStorageStrategy,
        new ExtensionBlueprintReadinessRegistry(org.slf4j.helpers.NOPLogger.NOP_LOGGER));
  }

  public BlueprintReadinessCatalog(
      SLSConfig config,
      Path contentRoot,
      JavaJarProcessSpecFactory processSpecs,
      Set<SoftwareSource> providerSources,
      StorageStrategy selectedStorageStrategy,
      ExtensionBlueprintReadinessRegistry extensionReadiness) {
    this.config = java.util.Objects.requireNonNull(config, "config");
    this.processSpecs = java.util.Objects.requireNonNull(processSpecs, "processSpecs");
    this.content =
        new BlueprintContentPreflight(
            config.instancesDirectory(), contentRoot, config.storage(), selectedStorageStrategy);
    this.providerSources = Set.copyOf(providerSources);
    this.extensionReadiness =
        java.util.Objects.requireNonNull(extensionReadiness, "extensionReadiness");
  }

  public synchronized BlueprintReadinessSummary refresh(
      Collection<Blueprint> blueprints, Collection<SoftwareProfile> profiles) {
    return refresh(blueprints, profiles, List.of());
  }

  public synchronized BlueprintReadinessSummary refresh(
      Collection<Blueprint> blueprints,
      Collection<SoftwareProfile> profiles,
      Collection<net.slimelabs.slslite.blueprint.BlueprintRepository.Rejection> rejections) {
    Map<String, SoftwareProfile> profilesById = new LinkedHashMap<>();
    profiles.forEach(profile -> profilesById.put(profile.id(), profile));
    Map<String, BlueprintReadinessReport> refreshed = new LinkedHashMap<>();
    blueprints.stream()
        .sorted(Comparator.comparing(Blueprint::id))
        .forEach(
            blueprint ->
                refreshed.put(
                    blueprint.id(),
                    inspectSafely(blueprint, profilesById.get(blueprint.software()))));
    rejections.stream()
        .sorted(
            Comparator.comparing(
                net.slimelabs.slslite.blueprint.BlueprintRepository.Rejection::path))
        .forEach(
            rejection ->
                refreshed.put(
                    rejection.path(),
                    new BlueprintReadinessReport(
                        rejection.path(),
                        BlueprintReadinessState.ACTION_NEEDED,
                        List.of(
                            new BlueprintReadinessIssue(
                                "definition-error",
                                BlueprintReadinessState.ACTION_NEEDED,
                                rejection.error())))));
    extensionReadiness.refresh(blueprints);
    reports = Map.copyOf(refreshed);
    return summary();
  }

  public synchronized Optional<BlueprintReadinessReport> get(String blueprintId) {
    return Optional.ofNullable(reports.get(blueprintId)).map(this::withExtensionFindings);
  }

  public synchronized Collection<BlueprintReadinessReport> reports() {
    return reports.values().stream()
        .map(this::withExtensionFindings)
        .sorted(Comparator.comparing(BlueprintReadinessReport::blueprintId))
        .toList();
  }

  public synchronized BlueprintReadinessSummary summary() {
    int ready = 0;
    int action = 0;
    int temporary = 0;
    for (BlueprintReadinessReport report : reports()) {
      switch (report.state()) {
        case READY -> ready++;
        case ACTION_NEEDED -> action++;
        case TEMPORARILY_UNAVAILABLE -> temporary++;
      }
    }
    return new BlueprintReadinessSummary(ready, action, temporary);
  }

  public synchronized void requireReady(String blueprintId) throws InstanceOperationException {
    BlueprintReadinessReport report = get(blueprintId).orElse(null);
    if (report == null || report.state() == BlueprintReadinessState.READY) {
      return;
    }
    throw new InstanceOperationException(
        "Blueprint "
            + blueprintId
            + " is "
            + stateName(report.state())
            + ": "
            + report.conciseReason()
            + ". Run /sls blueprint "
            + blueprintId
            + " for details; after supplying the missing input, run /sls reload and retry.");
  }

  private BlueprintReadinessReport inspect(Blueprint blueprint, SoftwareProfile profile) {
    List<BlueprintReadinessIssue> issues = new ArrayList<>();
    if (profile == null) {
      add(
          issues,
          "software-profile",
          BlueprintReadinessState.ACTION_NEEDED,
          "software profile '" + blueprint.software() + "' is not loaded");
    } else {
      inspectSoftware(blueprint, profile, issues);
      inspectJava(blueprint, profile, issues);
    }
    for (BlueprintContentPreflight.Problem problem : content.inspect(blueprint)) {
      add(
          issues,
          "content-source",
          problem.temporary()
              ? BlueprintReadinessState.TEMPORARILY_UNAVAILABLE
              : BlueprintReadinessState.ACTION_NEEDED,
          problem.message());
    }
    BlueprintReadinessState state = state(issues);
    return new BlueprintReadinessReport(blueprint.id(), state, issues);
  }

  private BlueprintReadinessReport withExtensionFindings(BlueprintReadinessReport core) {
    List<ExtensionBlueprintReadinessRegistry.Contribution> contributed =
        extensionReadiness.findings(core.blueprintId());
    if (contributed.isEmpty()) {
      return core;
    }
    List<BlueprintReadinessIssue> combined = new ArrayList<>(core.issues());
    contributed.forEach(
        contribution -> {
          var finding = contribution.finding();
          combined.add(
              new BlueprintReadinessIssue(
                  "extension." + contribution.namespace() + "." + finding.code(),
                  finding.status() == BlueprintReadinessStatus.ACTION_NEEDED
                      ? BlueprintReadinessState.ACTION_NEEDED
                      : BlueprintReadinessState.TEMPORARILY_UNAVAILABLE,
                  "[" + contribution.namespace() + "] " + finding.message()));
        });
    return new BlueprintReadinessReport(core.blueprintId(), state(combined), combined);
  }

  private BlueprintReadinessReport inspectSafely(Blueprint blueprint, SoftwareProfile profile) {
    try {
      return inspect(blueprint, profile);
    } catch (RuntimeException exception) {
      String detail = exception.getMessage();
      if (detail == null || detail.isBlank()) {
        detail = exception.getClass().getSimpleName();
      }
      if (detail.length() > 256) {
        detail = detail.substring(0, 256) + "...";
      }
      return new BlueprintReadinessReport(
          blueprint.id(),
          BlueprintReadinessState.TEMPORARILY_UNAVAILABLE,
          List.of(
              new BlueprintReadinessIssue(
                  "preflight-error",
                  BlueprintReadinessState.TEMPORARILY_UNAVAILABLE,
                  "readiness inspection could not complete: " + detail)));
    }
  }

  private void inspectSoftware(
      Blueprint blueprint, SoftwareProfile profile, List<BlueprintReadinessIssue> issues) {
    try {
      Path base =
          blueprint.softwarePath() == null
              ? processSpecs.resolveBaseDirectory(profile, blueprint.version())
              : processSpecs.resolveSoftwareOverridePath(blueprint.softwarePath());
      if (blueprint.softwarePath() != null || profile.source() == SoftwareSource.MANUAL) {
        if (!validServerBase(base, profile.serverJar())) {
          add(
              issues,
              "software-base",
              BlueprintReadinessState.ACTION_NEEDED,
              "manual server base or configured server JAR is missing or unsafe");
        }
        return;
      }
      if (verifiedCacheIdentity(base, profile, blueprint.version())) {
        return;
      }
      if (!providerSources.contains(profile.source())) {
        add(
            issues,
            "software-provider",
            BlueprintReadinessState.TEMPORARILY_UNAVAILABLE,
            "no installer provider is currently available for " + profile.source());
      } else if (!profile.acceptEula() && !config.software().autoAcceptEula()) {
        add(
            issues,
            "software-eula",
            BlueprintReadinessState.ACTION_NEEDED,
            "automatic installation awaits explicit Minecraft EULA acceptance");
      }
    } catch (ProcessSpecificationException exception) {
      add(issues, "software-path", BlueprintReadinessState.ACTION_NEEDED, exception.getMessage());
    }
  }

  private void inspectJava(
      Blueprint blueprint, SoftwareProfile profile, List<BlueprintReadinessIssue> issues) {
    try {
      String executable =
          processSpecs.resolveJavaExecutable(profile, blueprint.version(), blueprint.image());
      if (!executableAvailable(executable)) {
        add(
            issues,
            "java-runtime",
            BlueprintReadinessState.ACTION_NEEDED,
            "selected Java executable is unavailable: " + executable);
      }
    } catch (ProcessSpecificationException exception) {
      add(issues, "java-runtime", BlueprintReadinessState.ACTION_NEEDED, exception.getMessage());
    }
  }

  private static boolean validServerBase(Path base, String serverJar) {
    if (Files.isSymbolicLink(base) || !Files.isDirectory(base, LinkOption.NOFOLLOW_LINKS)) {
      return false;
    }
    Path jar = base.resolve(serverJar).normalize();
    return jar.startsWith(base)
        && !Files.isSymbolicLink(jar)
        && Files.isRegularFile(jar, LinkOption.NOFOLLOW_LINKS);
  }

  private static boolean verifiedCacheIdentity(Path base, SoftwareProfile profile, String version) {
    if (!validServerBase(base, profile.serverJar())) {
      return false;
    }
    Path metadata = base.resolve(".sls-install.properties");
    Path eula = base.resolve("eula.txt");
    if (Files.isSymbolicLink(metadata)
        || !Files.isRegularFile(metadata, LinkOption.NOFOLLOW_LINKS)
        || Files.isSymbolicLink(eula)
        || !Files.isRegularFile(eula, LinkOption.NOFOLLOW_LINKS)) {
      return false;
    }
    try {
      boolean accepted =
          BoundedFileReader.readStringNoFollow(
                  eula, java.nio.charset.StandardCharsets.UTF_8, MAXIMUM_METADATA_BYTES)
              .lines()
              .map(String::trim)
              .anyMatch("eula=true"::equalsIgnoreCase);
      if (!accepted) {
        return false;
      }
      Properties values = new Properties();
      try (InputStream input = BoundedFileReader.openNoFollow(metadata, MAXIMUM_METADATA_BYTES)) {
        values.load(input);
      }
      return "1".equals(values.getProperty("format"))
          && profile.id().equals(values.getProperty("software"))
          && version.equals(values.getProperty("version"))
          && profile.source().name().equals(values.getProperty("source"))
          && profile.channel().name().equals(values.getProperty("channel"))
          && profile.installationSelection(version).equals(values.getProperty("selection"))
          && profile.serverJar().equals(values.getProperty("jar"));
    } catch (IOException | RuntimeException exception) {
      return false;
    }
  }

  private static boolean executableAvailable(String executable) {
    if (executable.indexOf('/') >= 0 || executable.indexOf('\\') >= 0) {
      return executableFile(Path.of(executable));
    }
    if ("java".equals(executable)) {
      Path bundled =
          Path.of(System.getProperty("java.home"), "bin", isWindows() ? "java.exe" : "java");
      if (executableFile(bundled)) {
        return true;
      }
    }
    String path = System.getenv("PATH");
    if (path == null || path.isBlank()) {
      return false;
    }
    int inspected = 0;
    for (String entry : path.split(java.util.regex.Pattern.quote(java.io.File.pathSeparator))) {
      if (++inspected > MAXIMUM_PATH_ENTRIES || entry.isBlank()) {
        break;
      }
      Path candidate = Path.of(entry).resolve(executable);
      if (executableFile(candidate)
          || isWindows() && executableFile(candidate.resolveSibling(executable + ".exe"))) {
        return true;
      }
    }
    return false;
  }

  private static boolean executableFile(Path path) {
    return Files.isRegularFile(path) && (isWindows() || Files.isExecutable(path));
  }

  private static boolean isWindows() {
    return java.io.File.separatorChar == '\\';
  }

  private static void add(
      List<BlueprintReadinessIssue> issues,
      String code,
      BlueprintReadinessState state,
      String message) {
    if (issues.size() < MAXIMUM_ISSUES_PER_BLUEPRINT) {
      issues.add(new BlueprintReadinessIssue(code, state, message));
    }
  }

  private static BlueprintReadinessState state(List<BlueprintReadinessIssue> issues) {
    if (issues.stream().anyMatch(issue -> issue.state() == BlueprintReadinessState.ACTION_NEEDED)) {
      return BlueprintReadinessState.ACTION_NEEDED;
    }
    return issues.isEmpty()
        ? BlueprintReadinessState.READY
        : BlueprintReadinessState.TEMPORARILY_UNAVAILABLE;
  }

  private static String stateName(BlueprintReadinessState state) {
    return state.name().toLowerCase(java.util.Locale.ROOT).replace('_', ' ');
  }
}
