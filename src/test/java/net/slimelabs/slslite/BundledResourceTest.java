package net.slimelabs.slslite;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.util.List;
import org.junit.jupiter.api.Test;

class BundledResourceTest {

  @Test
  void packagesOnlyTheSupportedRuntimeResourceContract() {
    ClassLoader loader = getClass().getClassLoader();
    for (String resource :
        List.of(
            "defaults/host/config.yml",
            "defaults/blueprints/template.yml",
            "defaults/software/paper-software.yml",
            "defaults/software/vanilla-software.yml",
            "velocity-plugin.json",
            "limbo/nanolimbo-1.13.0.jar")) {
      assertNotNull(loader.getResource(resource), () -> "Missing bundled resource: " + resource);
    }

    assertNull(loader.getResource("Data_Versions"));
    assertNull(loader.getResource("Protocol_Versions"));
    assertNull(loader.getResource("config.yml"));
    assertNull(loader.getResource("paper-software.yml"));
    assertNull(loader.getResource("template.yml"));
    assertNull(loader.getResource("vanilla-software.yml"));
  }
}
