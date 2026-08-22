package net.slimelabs.slslite.blueprint;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import net.slimelabs.slslite.config.DefinitionCatalog;
import net.slimelabs.slslite.io.ConfinedFiles;

public final class BlueprintRepository {

  private static final String DEFAULT_TEMPLATE_RESOURCE =
      "defaults/blueprints/template.yml.example";
  private static final String DEFAULT_TEMPLATE_FILE = "template.yml.example";

  private final Path directory;
  private final DefinitionCatalog catalog;
  private final BlueprintParser parser = new BlueprintParser();
  private volatile List<Rejection> rejections = List.of();

  public BlueprintRepository(Path directory) {
    this(directory, new DefinitionCatalog());
  }

  public BlueprintRepository(Path directory, DefinitionCatalog catalog) {
    this.directory = directory.toAbsolutePath().normalize();
    this.catalog = catalog;
  }

  public void initialize() throws IOException, BlueprintException {
    prepare();
    reload();
  }

  public void prepare() throws IOException {
    ConfinedFiles.ensureDirectory(directory);
    installTemplateWhenEmpty();
  }

  public synchronized void reload() throws IOException, BlueprintException {
    LoadResult result = loadIsolated();
    if (!result.rejections().isEmpty()) {
      Rejection rejection = result.rejections().getFirst();
      throw new BlueprintException(rejection.path() + ": " + rejection.error());
    }
    install(result.snapshot(), List.of());
  }

  public Snapshot loadSnapshot() throws IOException, BlueprintException {
    LoadResult result = loadIsolated();
    if (!result.rejections().isEmpty()) {
      Rejection rejection = result.rejections().getFirst();
      throw new BlueprintException(rejection.path() + ": " + rejection.error());
    }
    return result.snapshot();
  }

  public LoadResult loadIsolated() throws IOException {
    Map<String, List<LoadedBlueprint>> byId = new LinkedHashMap<>();
    List<Rejection> rejections = new ArrayList<>();
    for (Path path : blueprintFiles()) {
      String relativePath = relativePath(path);
      try {
        Blueprint blueprint = parser.parse(path);
        byId.computeIfAbsent(blueprint.id(), ignored -> new ArrayList<>())
            .add(new LoadedBlueprint(relativePath, blueprint));
      } catch (BlueprintException exception) {
        rejections.add(new Rejection(relativePath, exception.getMessage()));
      }
    }

    Map<String, LoadedBlueprint> accepted = new LinkedHashMap<>();
    byId.forEach(
        (id, candidates) -> {
          if (candidates.size() == 1) {
            accepted.put(id, candidates.getFirst());
            return;
          }
          candidates.forEach(
              candidate ->
                  rejections.add(
                      new Rejection(
                          candidate.path(),
                          "Duplicate blueprint id '" + id + "' is declared by multiple files")));
        });
    rejections.sort(java.util.Comparator.comparing(Rejection::path));
    return new LoadResult(accepted, rejections);
  }

  public Snapshot snapshot() {
    return new Snapshot(catalog.snapshot().blueprints());
  }

  public synchronized void install(Snapshot snapshot) {
    install(snapshot, List.of());
  }

  public synchronized void install(Snapshot snapshot, List<Rejection> rejected) {
    catalog.installBlueprints(snapshot.values());
    rejections = List.copyOf(rejected);
  }

  public synchronized void installRejections(List<Rejection> rejected) {
    rejections = List.copyOf(rejected);
  }

  public List<Rejection> rejections() {
    return rejections;
  }

  public DefinitionCatalog catalog() {
    return catalog;
  }

  public Optional<Blueprint> get(String id) {
    return Optional.ofNullable(catalog.snapshot().blueprints().get(id));
  }

  public Optional<Blueprint> get(String type, String id) {
    return get(id).filter(blueprint -> blueprint.type().equals(type));
  }

  public Collection<Blueprint> getAll() {
    return catalog.snapshot().blueprints().values().stream()
        .sorted(java.util.Comparator.comparing(Blueprint::id))
        .toList();
  }

  public Collection<Blueprint> getByType(String type) {
    return catalog.snapshot().blueprints().values().stream()
        .filter(blueprint -> blueprint.type().equals(type))
        .sorted(java.util.Comparator.comparing(Blueprint::id))
        .toList();
  }

  public Set<String> getTypes() {
    return catalog.snapshot().blueprints().values().stream()
        .map(Blueprint::type)
        .collect(Collectors.toUnmodifiableSet());
  }

  private List<Path> blueprintFiles() throws IOException {
    ConfinedFiles.ensureDirectory(directory);
    try (Stream<Path> files = Files.walk(directory)) {
      return files
          .filter(path -> Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS))
          .filter(BlueprintRepository::isYaml)
          .sorted()
          .toList();
    }
  }

  private void installTemplateWhenEmpty() throws IOException {
    if (!blueprintFiles().isEmpty()
        || Files.exists(directory.resolve(DEFAULT_TEMPLATE_FILE), LinkOption.NOFOLLOW_LINKS)) {
      return;
    }

    try (InputStream source =
        getClass().getClassLoader().getResourceAsStream(DEFAULT_TEMPLATE_RESOURCE)) {
      if (source == null) {
        throw new IOException("Bundled blueprint default is missing: " + DEFAULT_TEMPLATE_RESOURCE);
      }
      ConfinedFiles.atomicCopy(
          directory, DEFAULT_TEMPLATE_FILE, source, BlueprintParser.MAX_BLUEPRINT_BYTES);
    }
  }

  private static boolean isYaml(Path path) {
    String name = path.getFileName().toString().toLowerCase(Locale.ROOT);
    return name.endsWith(".yml") || name.endsWith(".yaml");
  }

  private String relativePath(Path path) {
    return directory.relativize(path.toAbsolutePath().normalize()).toString().replace('\\', '/');
  }

  public record LoadedBlueprint(String path, Blueprint blueprint) {
    public LoadedBlueprint {
      java.util.Objects.requireNonNull(path, "path");
      java.util.Objects.requireNonNull(blueprint, "blueprint");
    }
  }

  public record Rejection(String path, String error) {
    public Rejection {
      java.util.Objects.requireNonNull(path, "path");
      java.util.Objects.requireNonNull(error, "error");
    }
  }

  public record LoadResult(Map<String, LoadedBlueprint> accepted, List<Rejection> rejections) {
    public LoadResult {
      accepted = Map.copyOf(accepted);
      rejections = List.copyOf(rejections);
    }

    public Snapshot snapshot() {
      Map<String, Blueprint> values = new LinkedHashMap<>();
      accepted.forEach((id, loaded) -> values.put(id, loaded.blueprint()));
      return new Snapshot(values);
    }
  }

  public record Snapshot(Map<String, Blueprint> values) {
    public Snapshot {
      values = Map.copyOf(values);
    }

    public Collection<Blueprint> getAll() {
      return values.values();
    }
  }
}
