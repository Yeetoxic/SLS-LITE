package net.slimelabs.slslite.instance;

import net.slimelabs.slslite.config.ForwardingConfig;
import net.slimelabs.slslite.config.ForwardingMode;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperForwardingEditorTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void configuresModernForwardingAndPreservesExistingValues() throws Exception {
        Path secret = temporaryDirectory.resolve("forwarding.secret");
        Files.writeString(secret, "test-secret\n");
        Files.writeString(temporaryDirectory.resolve("spigot.yml"), """
                settings:
                  restart-on-crash: true
                  bungeecord: true
                """);
        Path paper = temporaryDirectory.resolve("config/paper-global.yml");
        Files.createDirectories(paper.getParent());
        Files.writeString(paper, """
                proxies:
                  proxy-protocol: false
                """);

        PaperForwardingEditor.apply(
                temporaryDirectory,
                new ForwardingConfig(ForwardingMode.MODERN, true, secret)
        );

        Map<String, Object> spigot = yaml(temporaryDirectory.resolve("spigot.yml"));
        Map<String, Object> settings = map(spigot.get("settings"));
        assertEquals(false, settings.get("bungeecord"));
        assertEquals(true, settings.get("restart-on-crash"));

        Map<String, Object> paperConfig = yaml(paper);
        Map<String, Object> proxies = map(paperConfig.get("proxies"));
        Map<String, Object> velocity = map(proxies.get("velocity"));
        assertEquals(false, proxies.get("proxy-protocol"));
        assertEquals(true, velocity.get("enabled"));
        assertEquals(true, velocity.get("online-mode"));
        assertEquals("test-secret", velocity.get("secret"));
    }

    @Test
    void disablesForwardingWithoutReadingASecret() throws Exception {
        Path missingSecret = temporaryDirectory.resolve("missing.secret");

        PaperForwardingEditor.apply(
                temporaryDirectory,
                new ForwardingConfig(ForwardingMode.NONE, false, missingSecret)
        );

        Map<String, Object> paper = yaml(
                temporaryDirectory.resolve("config/paper-global.yml")
        );
        Map<String, Object> velocity = map(map(paper.get("proxies")).get("velocity"));
        assertEquals(false, velocity.get("enabled"));
        assertEquals("", velocity.get("secret"));
        assertFalse(Files.exists(missingSecret));
    }

    @Test
    void rejectsModernForwardingWhenSecretIsMissing() {
        InstancePreparationException exception = assertThrows(
                InstancePreparationException.class,
                () -> PaperForwardingEditor.apply(
                        temporaryDirectory,
                        new ForwardingConfig(
                                ForwardingMode.MODERN,
                                true,
                                temporaryDirectory.resolve("missing.secret")
                        )
                )
        );

        assertTrue(exception.getMessage().contains("does not exist"));
        assertFalse(Files.exists(temporaryDirectory.resolve("spigot.yml")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> yaml(Path path) throws Exception {
        LoaderOptions options = new LoaderOptions();
        options.setAllowDuplicateKeys(false);
        try (InputStream input = Files.newInputStream(path)) {
            return (Map<String, Object>) new Yaml(new SafeConstructor(options)).load(input);
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
