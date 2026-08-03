package net.slimelabs.slslite.blueprint;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
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

  public BlueprintRepository(Path directory) {
    this(directory, new DefinitionCatalog());
  }

  public BlueprintRepository(Path directory, DefinitionCatalog catalog) {
    this.directory = directory.toAbsolutePath().normalize();
    this.catalog = catalog;
  }

  public void initialize() throws IOException, BlueprintException {
    ConfinedFiles.ensureDirectory(directory);
    installTemplateWhenEmpty();
    reload();
  }

  public synchronized void reload() throws IOException, BlueprintException {
    install(loadSnapshot());
  }

  public Snapshot loadSnapshot() throws IOException, BlueprintException {
    Map<String, Blueprint> loaded = new LinkedHashMap<>();

    for (Path path : blueprintFiles()) {
      Blueprint blueprint = parser.parse(path);
      Blueprint previous = loaded.putIfAbsent(blueprint.id(), blueprint);
      if (previous != null) {
        throw new BlueprintException("Duplicate blueprint id '" + blueprint.id() + "'");
      }
    }
    return new Snapshot(loaded);
  }

  public Snapshot snapshot() {
    return new Snapshot(catalog.snapshot().blueprints());
  }

  public synchronized void install(Snapshot snapshot) {
    catalog.installBlueprints(snapshot.values());
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

  public record Snapshot(Map<String, Blueprint> values) {
    public Snapshot {
      values = Map.copyOf(values);
    }

    public Collection<Blueprint> getAll() {
      return values.values();
    }
  }
}
