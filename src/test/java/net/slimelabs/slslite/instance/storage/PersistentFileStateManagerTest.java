package net.slimelabs.slslite.instance.storage;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.Properties;
import net.slimelabs.slslite.blueprint.BlueprintPersistentFile;
import net.slimelabs.slslite.instance.InstancePreparationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PersistentFileStateManagerTest {

  @TempDir Path temporaryDirectory;

  @Test
  void importsAndAtomicallyPublishesChangedFileWithBackup() throws Exception {
    Fixture fixture = fixture();
    fixture.manager.prepare("lobby.abcdef", fixture.instance, List.of(mapping()), () -> false);

    assertEquals("[]\n", Files.readString(fixture.target()));
    assertTrue(
        Files.isRegularFile(fixture.instance.resolve(PersistentFileStateManager.MANIFEST_FILE)));

    Files.writeString(fixture.target(), "[\"player\"]\n");
    fixture.manager.publish("lobby.abcdef", fixture.instance);

    assertEquals("[\"player\"]\n", Files.readString(fixture.source));
    assertEquals(
        "[]\n",
        Files.readString(
            findOnly(fixture.content.resolve("internal/persistent-file-backups"), "previous.bak")));
    assertFalse(Files.exists(fixture.source.resolveSibling(".sls-lite-whitelist.json.tmp")));
  }

  @Test
  void rejectsASecondActiveWriterForTheSameCanonicalFile() throws Exception {
    Fixture fixture = fixture();
    fixture.manager.prepare("lobby.abcdef", fixture.instance, List.of(mapping()), () -> false);
    Path second = Files.createDirectories(fixture.instances.resolve("lobby.123456"));

    InstancePreparationException failure =
        assertThrows(
            InstancePreparationException.class,
            () -> fixture.manager.prepare("lobby.123456", second, List.of(mapping()), () -> false));

    assertTrue(failure.getMessage().contains("already owned by active instance lobby.abcdef"));
  }

  @Test
  void externalAndInstanceChangesProduceConflictWithoutOverwritingCanonicalFile() throws Exception {
    Fixture fixture = fixture();
    fixture.manager.prepare("lobby.abcdef", fixture.instance, List.of(mapping()), () -> false);
    Files.writeString(fixture.source, "[\"external\"]\n");
    Files.writeString(fixture.target(), "[\"instance\"]\n");

    InstancePreparationException failure =
        assertThrows(
            InstancePreparationException.class,
            () -> fixture.manager.publish("lobby.abcdef", fixture.instance));

    assertTrue(failure.getMessage().contains("changed both"));
    assertEquals("[\"external\"]\n", Files.readString(fixture.source));
    assertEquals(
        "[\"instance\"]\n",
        Files.readString(
            findOnly(fixture.content.resolve("internal/persistent-file-conflicts"), "candidate")));

    Path second = Files.createDirectories(fixture.instances.resolve("lobby.123456"));
    InstancePreparationException unresolved =
        assertThrows(
            InstancePreparationException.class,
            () -> fixture.manager.prepare("lobby.123456", second, List.of(mapping()), () -> false));
    assertTrue(unresolved.getMessage().contains("unresolved conflict candidate"));
  }

  @Test
  void restartImportsAnExternallyChangedCanonicalFileWhenInstanceIsUnchanged() throws Exception {
    Fixture fixture = fixture();
    fixture.manager.prepare("lobby.abcdef", fixture.instance, List.of(mapping()), () -> false);
    fixture.manager.publish("lobby.abcdef", fixture.instance);
    Files.writeString(fixture.source, "[\"external\"]\n");

    fixture.manager.resume("lobby.abcdef", fixture.instance);

    assertEquals("[\"external\"]\n", Files.readString(fixture.target()));
  }

  @Test
  void restartRepairsManifestAfterCrashBetweenCanonicalWriteAndManifestCommit() throws Exception {
    Fixture fixture = fixture();
    fixture.manager.prepare("lobby.abcdef", fixture.instance, List.of(mapping()), () -> false);
    fixture.manager.release("lobby.abcdef");

    // Reproduce the durable state left when the atomic canonical replacement completed but the
    // process exited before the manifest's new digest could be committed.
    byte[] published =
        "[\"published-before-crash\"]\n".getBytes(java.nio.charset.StandardCharsets.UTF_8);
    Files.write(fixture.source, published);
    Files.write(fixture.target(), published);

    PersistentFileStateManager restarted =
        new PersistentFileStateManager(fixture.instances, fixture.content);
    restarted.resume("lobby.abcdef", fixture.instance);

    Properties manifest = new Properties();
    try (var input =
        Files.newInputStream(fixture.instance.resolve(PersistentFileStateManager.MANIFEST_FILE))) {
      manifest.load(input);
    }
    assertEquals(
        HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(published)),
        manifest.getProperty("file.0.sha256"));
    assertEquals(
        new String(published, java.nio.charset.StandardCharsets.UTF_8),
        Files.readString(fixture.source));
    assertEquals(
        new String(published, java.nio.charset.StandardCharsets.UTF_8),
        Files.readString(fixture.target()));
  }

  @Test
  void rejectsOversizedFilesBeforeImport() throws Exception {
    Fixture fixture = fixture();
    try (var output = Files.newOutputStream(fixture.source)) {
      output.write(new byte[PersistentFileStateManager.MAX_FILE_BYTES + 1]);
    }

    IOException failure =
        assertThrows(
            IOException.class,
            () ->
                fixture.manager.prepare(
                    "lobby.abcdef", fixture.instance, List.of(mapping()), () -> false));

    assertTrue(failure.getMessage().contains("exceeds"));
    assertFalse(Files.exists(fixture.target()));
  }

  @Test
  void rejectsSymbolicSourceWhenPlatformSupportsLinks() throws Exception {
    Fixture fixture = fixture();
    Path outside = Files.writeString(temporaryDirectory.resolve("outside.json"), "[]\n");
    Path link = fixture.content.resolve("volumes/whitelists/lobby/link.json");
    try {
      Files.createSymbolicLink(link, outside);
    } catch (UnsupportedOperationException | IOException exception) {
      return;
    }

    InstancePreparationException failure =
        assertThrows(
            InstancePreparationException.class,
            () ->
                fixture.manager.prepare(
                    "lobby.abcdef",
                    fixture.instance,
                    List.of(
                        new BlueprintPersistentFile(
                            "whitelist", "volumes/whitelists/lobby/link.json", "whitelist.json")),
                    () -> false));

    assertTrue(failure.getMessage().contains("symbolic links"));
  }

  private Fixture fixture() throws IOException {
    Path content = Files.createDirectories(temporaryDirectory.resolve("content"));
    Path instances = Files.createDirectories(content.resolve("instances"));
    Path instance = Files.createDirectories(instances.resolve("lobby.abcdef"));
    Path source = content.resolve("volumes/whitelists/lobby/whitelist.json");
    Files.createDirectories(source.getParent());
    Files.writeString(source, "[]\n");
    return new Fixture(
        content, instances, instance, source, new PersistentFileStateManager(instances, content));
  }

  private static BlueprintPersistentFile mapping() {
    return new BlueprintPersistentFile(
        "whitelist", "volumes/whitelists/lobby/whitelist.json", "whitelist.json");
  }

  @Test
  void requiredManifestCannotBeDeletedSilently() throws Exception {
    Fixture fixture = fixture();
    fixture.manager.prepare("lobby.abcdef", fixture.instance, List.of(mapping()), () -> false);
    Files.delete(fixture.instance.resolve(PersistentFileStateManager.MANIFEST_FILE));

    InstancePreparationException failure =
        assertThrows(
            InstancePreparationException.class,
            () -> fixture.manager.publish("lobby.abcdef", fixture.instance, true));

    assertTrue(failure.getMessage().contains("manifest is missing"));
    assertEquals("[]\n", Files.readString(fixture.source));
  }

  private static Path findOnly(Path root, String fileName) throws IOException {
    try (var paths = Files.walk(root)) {
      return paths
          .filter(path -> path.getFileName().toString().equals(fileName))
          .findFirst()
          .orElseThrow();
    }
  }

  private record Fixture(
      Path content,
      Path instances,
      Path instance,
      Path source,
      PersistentFileStateManager manager) {
    private Path target() {
      return instance.resolve("whitelist.json");
    }
  }
}
