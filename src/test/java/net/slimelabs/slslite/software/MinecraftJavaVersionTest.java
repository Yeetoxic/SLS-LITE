package net.slimelabs.slslite.software;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class MinecraftJavaVersionTest {

    @Test
    void mapsCurrentPaperVersionFamilies() {
        assertEquals(
                25,
                MinecraftJavaVersion.requiredMajor(
                        SoftwareConfigurator.PAPER,
                        "26.1"
                )
        );
        assertEquals(
                21,
                MinecraftJavaVersion.requiredMajor(
                        SoftwareConfigurator.PAPER,
                        "1.21.11"
                )
        );
        assertEquals(
                17,
                MinecraftJavaVersion.requiredMajor(
                        SoftwareConfigurator.PAPER,
                        "1.19.4"
                )
        );
    }

    @Test
    void mapsVanillaJava21Boundary() {
        assertEquals(
                21,
                MinecraftJavaVersion.requiredMajor(
                        SoftwareConfigurator.VANILLA,
                        "1.20.5"
                )
        );
        assertEquals(
                17,
                MinecraftJavaVersion.requiredMajor(
                        SoftwareConfigurator.VANILLA,
                        "1.20.4"
                )
        );
    }
}
