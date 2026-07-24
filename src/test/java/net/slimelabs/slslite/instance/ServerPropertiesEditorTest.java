package net.slimelabs.slslite.instance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

import static org.junit.jupiter.api.Assertions.assertEquals;

class ServerPropertiesEditorTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void preservesExistingValuesAndAppliesManagedNetworkSettings() throws Exception {
        Files.writeString(
                temporaryDirectory.resolve("server.properties"),
                "motd=Test Server\nonline-mode=true\n",
                StandardCharsets.UTF_8
        );

        ServerPropertiesEditor.applyManagedNetworkSettings(temporaryDirectory, 25571);

        Properties properties = new Properties();
        try (Reader input = Files.newBufferedReader(
                temporaryDirectory.resolve("server.properties"),
                StandardCharsets.UTF_8
        )) {
            properties.load(input);
        }
        assertEquals("Test Server", properties.getProperty("motd"));
        assertEquals("false", properties.getProperty("online-mode"));
        assertEquals("127.0.0.1", properties.getProperty("server-ip"));
        assertEquals("25571", properties.getProperty("server-port"));
    }
}
