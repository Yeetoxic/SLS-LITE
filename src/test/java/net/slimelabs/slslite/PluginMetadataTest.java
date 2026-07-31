package net.slimelabs.slslite;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.junit.jupiter.api.Test;

class PluginMetadataTest {

  @Test
  void packagesVelocityPluginMetadata() throws Exception {
    try (InputStream input =
        getClass().getClassLoader().getResourceAsStream("velocity-plugin.json")) {
      assertNotNull(input, "velocity-plugin.json must be packaged");
      String metadata = new String(input.readAllBytes(), StandardCharsets.UTF_8);
      assertTrue(metadata.contains("\"id\": \"sls-lite\""));
      assertTrue(metadata.contains("\"main\": \"net.slimelabs.slslite.SLSLite\""));
      assertTrue(metadata.contains("\"version\": \"" + BuildInfo.VERSION + "\""));
      assertTrue(metadata.contains("\"id\": \"viaversion\""));
      assertTrue(metadata.contains("\"optional\": true"));
    }
  }
}
