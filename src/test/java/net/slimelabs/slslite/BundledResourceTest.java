package net.slimelabs.slslite;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class BundledResourceTest {

    @Test
    void packagesOnlyTheSupportedRuntimeResourceContract() {
        ClassLoader loader = getClass().getClassLoader();
        for (String resource : List.of(
                "config.yml",
                "paper-software.yml",
                "template.yml",
                "vanilla-software.yml",
                "velocity-plugin.json",
                "limbo/nanolimbo-1.13.0.jar"
        )) {
            assertNotNull(
                    loader.getResource(resource),
                    () -> "Missing bundled resource: " + resource
            );
        }

        assertNull(loader.getResource("Data_Versions"));
        assertNull(loader.getResource("Protocol_Versions"));
    }
}
