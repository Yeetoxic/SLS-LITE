package net.slimelabs.slslite.instance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperRuntimeCachePromoterTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void promotesOnlyReusablePaperRuntimeDirectories() throws Exception {
        Path instance = Files.createDirectories(temporaryDirectory.resolve("instance"));
        Path software = Files.createDirectories(temporaryDirectory.resolve("software"));
        write(instance, "cache/mojang.jar", "mojang");
        write(instance, "libraries/example/library.jar", "library");
        write(instance, "world/level.dat", "world");
        write(instance, "logs/latest.log", "log");
        write(instance, "server.properties", "motd=instance");

        var promoted = new PaperRuntimeCachePromoter().promote(instance, software);

        assertEquals(java.util.Set.of("cache", "libraries"), java.util.Set.copyOf(promoted));
        assertEquals("mojang", Files.readString(software.resolve("cache/mojang.jar")));
        assertEquals(
                "library",
                Files.readString(software.resolve("libraries/example/library.jar"))
        );
        assertFalse(Files.exists(software.resolve("world")));
        assertFalse(Files.exists(software.resolve("logs")));
        assertFalse(Files.exists(software.resolve("server.properties")));
    }

    @Test
    void neverOverwritesAnExistingSharedCache() throws Exception {
        Path instance = Files.createDirectories(temporaryDirectory.resolve("instance"));
        Path software = Files.createDirectories(temporaryDirectory.resolve("software"));
        write(instance, "cache/mojang.jar", "instance");
        write(software, "cache/mojang.jar", "shared");

        var promoted = new PaperRuntimeCachePromoter().promote(instance, software);

        assertTrue(promoted.isEmpty());
        assertEquals("shared", Files.readString(software.resolve("cache/mojang.jar")));
    }

    private static void write(Path root, String relative, String value) throws Exception {
        Path target = root.resolve(relative);
        Files.createDirectories(target.getParent());
        Files.writeString(target, value);
    }
}
