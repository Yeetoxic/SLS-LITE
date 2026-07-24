package net.slimelabs.slslite.instance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InstanceDirectoryPreparerTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void createsIndependentDirectoryCopy() throws Exception {
        Path source = createSource();
        Path instances = temporaryDirectory.resolve("instances");
        InstanceDirectoryPreparer preparer = new InstanceDirectoryPreparer(instances);

        Path prepared = preparer.prepare("game.x82odk", source);
        Files.writeString(prepared.resolve("config/settings.yml"), "changed");

        assertEquals("original", Files.readString(source.resolve("config/settings.yml")));
        assertEquals("server", Files.readString(prepared.resolve("server.jar")));
    }

    @Test
    void refusesExistingInstanceDirectory() throws Exception {
        Path source = createSource();
        Path instances = temporaryDirectory.resolve("instances");
        Files.createDirectories(instances.resolve("game.x82odk"));
        InstanceDirectoryPreparer preparer = new InstanceDirectoryPreparer(instances);

        assertThrows(
                InstancePreparationException.class,
                () -> preparer.prepare("game.x82odk", source)
        );
    }

    @Test
    void rejectsUnsafeInstanceId() throws Exception {
        Path source = createSource();
        InstanceDirectoryPreparer preparer =
                new InstanceDirectoryPreparer(temporaryDirectory.resolve("instances"));

        assertThrows(
                InstancePreparationException.class,
                () -> preparer.prepare("../outside", source)
        );
        assertFalse(Files.exists(temporaryDirectory.resolve("outside")));
    }

    @Test
    void deletesOnlyNamedInstanceDirectory() throws Exception {
        Path source = createSource();
        Path instances = temporaryDirectory.resolve("instances");
        InstanceDirectoryPreparer preparer = new InstanceDirectoryPreparer(instances);
        Path prepared = preparer.prepare("game.x82odk", source);

        preparer.delete("game.x82odk");

        assertFalse(Files.exists(prepared));
        assertTrue(Files.exists(source));
    }

    private Path createSource() throws Exception {
        Path source = temporaryDirectory.resolve("software");
        Files.createDirectories(source.resolve("config"));
        Files.writeString(source.resolve("server.jar"), "server");
        Files.writeString(source.resolve("config/settings.yml"), "original");
        return source;
    }
}
