package net.slimelabs.slslite.log;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ConsoleBannerTest {

    @Test
    void logoHasStableConsoleDimensions() {
        assertEquals(4, ConsoleBanner.logo().size());
        assertTrue(ConsoleBanner.logo().stream().allMatch(line -> line.length() <= 48));
    }

    @Test
    void logoIdentifiesSlsLite() {
        String logo = String.join("\n", ConsoleBanner.logo());

        assertTrue(logo.contains("___"));
        assertTrue(logo.contains("_____"));
    }
}
