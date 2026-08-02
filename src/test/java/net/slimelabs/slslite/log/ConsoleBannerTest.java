package net.slimelabs.slslite.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.slimelabs.slslite.BuildInfo;
import org.junit.jupiter.api.Test;

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
    assertTrue(logo.contains("|___/"));
  }

  @Test
  void startupBannerContainsCompactAnsiBrandingAndLicenseReference() {
    String banner = String.join("\n", ConsoleBanner.startupBanner());
    String plainBanner = banner.replaceAll("\\u001B\\[[;\\d]*m", "");

    assertTrue(banner.contains("\u001B[32m"));
    assertTrue(banner.contains("\u001B[31m"));
    assertTrue(banner.contains("\u001B[33m"));
    assertTrue(banner.contains("\u001B[35m"));
    assertTrue(banner.contains("\u001B[94m"));
    assertFalse(banner.contains("\u001B[95m"));
    assertFalse(banner.contains("\u001B[97m"));
    assertTrue(plainBanner.contains("Server Launch System"));
    assertFalse(plainBanner.contains("Standalone Server Launch System"));
    assertTrue(plainBanner.contains("Website: https://slimelabs.net"));
    assertTrue(plainBanner.contains("GNU AGPL v3.0"));
    assertTrue(plainBanner.contains(BuildInfo.LICENSE_URL));
    assertTrue(plainBanner.contains("warranty terms"));
    assertTrue(ConsoleBanner.startupBanner().size() <= 15);
  }

  @Test
  void everyColoredStartupLineResetsItsAnsiState() {
    ConsoleBanner.startupBanner().stream()
        .filter(line -> line.contains("\u001B["))
        .forEach(
            line -> {
              assertTrue(line.endsWith("\u001B[0m"));
              assertFalse(line.endsWith("\u001B[0m\u001B[0m"));
            });
  }
}
