package net.slimelabs.slslite.instance;

import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Properties;

public final class ServerPropertiesEditor {

    private ServerPropertiesEditor() {
    }

    public static void applyManagedNetworkSettings(Path instanceDirectory, int port)
            throws IOException {
        if (port < 1024 || port > 65535) {
            throw new IllegalArgumentException("port must be between 1024 and 65535");
        }

        Path root = instanceDirectory.toAbsolutePath().normalize();
        Path propertiesPath = root.resolve("server.properties");
        Properties properties = new Properties();
        if (Files.exists(propertiesPath)) {
            try (Reader input = Files.newBufferedReader(propertiesPath, StandardCharsets.UTF_8)) {
                properties.load(input);
            }
        }

        properties.setProperty("server-ip", "127.0.0.1");
        properties.setProperty("server-port", Integer.toString(port));
        properties.setProperty("online-mode", "false");

        try (Writer output = Files.newBufferedWriter(propertiesPath, StandardCharsets.UTF_8)) {
            properties.store(output, "Managed by SLS-LITE");
        }
    }
}
