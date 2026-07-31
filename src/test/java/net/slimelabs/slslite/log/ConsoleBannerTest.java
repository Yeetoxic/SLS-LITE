package net.slimelabs.slslite.log;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
    assertTrue(logo.contains("_____"));
  }

  @Test
  void startupBannerContainsAnsiBrandingAndLegalNotice() {
    String banner = String.join("\n", ConsoleBanner.startupBanner());
    String plainBanner = banner.replaceAll("\\u001B\\[[;\\d]*m", "");

    assertTrue(banner.contains("\u001B[32m"));
    assertTrue(banner.contains("\u001B[92m"));
    assertTrue(banner.contains("\u001B[94m"));
    assertTrue(banner.contains("\u001B[95m"));
    assertTrue(plainBanner.contains("Website: https://slimelabs.net"));
    assertTrue(plainBanner.contains("GNU AGPL v3.0"));
    assertTrue(plainBanner.contains("WITHOUT ANY WARRANTY"));
    assertTrue(plainBanner.contains("MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE"));
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
