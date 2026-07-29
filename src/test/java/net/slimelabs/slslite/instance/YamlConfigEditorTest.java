package net.slimelabs.slslite.instance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YamlConfigEditorTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void recursivelyMergesAndAtomicallyWritesYaml() throws Exception {
        Files.writeString(
                temporaryDirectory.resolve("spigot.yml"),
                """
                        settings:
                          moved-wrongly-threshold: 0.0625
                          restart-on-crash: true
                        messages:
                          restart: Restarting
                        """
        );

        YamlConfigEditor.apply(
                temporaryDirectory,
                Map.of("spigot.yml", Map.of(
                        "settings", Map.of(
                                "moved-wrongly-threshold", 1000,
                                "moved-too-quickly-multiplier", 1000.0
                        )
                ))
        );

        LoaderOptions options = new LoaderOptions();
        try (InputStream input = Files.newInputStream(
                temporaryDirectory.resolve("spigot.yml")
        )) {
            @SuppressWarnings("unchecked")
            Map<String, Object> result = (Map<String, Object>) new Yaml(
                    new SafeConstructor(options)
            ).load(input);
            @SuppressWarnings("unchecked")
            Map<String, Object> settings =
                    (Map<String, Object>) result.get("settings");
            assertEquals(1000, settings.get("moved-wrongly-threshold"));
            assertEquals(1000.0, settings.get("moved-too-quickly-multiplier"));
            assertEquals(true, settings.get("restart-on-crash"));
            assertTrue(result.containsKey("messages"));
        }
        assertTrue(Files.notExists(temporaryDirectory.resolve("spigot.yml.tmp")));
    }

    @Test
    void rejectsTargetOutsideInstance() {
        assertThrows(
                java.io.IOException.class,
                () -> YamlConfigEditor.apply(
                        temporaryDirectory,
                        Map.of("../outside.yml", Map.of("enabled", true))
                )
        );
    }

    @Test
    void refusesPreexistingTemporaryPath() throws Exception {
        Files.writeString(
                temporaryDirectory.resolve("bukkit.yml.tmp"),
                "do-not-replace"
        );

        assertThrows(
                java.io.IOException.class,
                () -> YamlConfigEditor.apply(
                        temporaryDirectory,
                        Map.of("bukkit.yml", Map.of("settings", Map.of("allow-end", true)))
                )
        );
        assertEquals(
                "do-not-replace",
                Files.readString(temporaryDirectory.resolve("bukkit.yml.tmp"))
        );
    }
}
