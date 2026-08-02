package net.slimelabs.slslite.log;

import java.util.ArrayList;
import java.util.List;
import net.slimelabs.slslite.BuildInfo;
import org.slf4j.Logger;

public final class ConsoleBanner {

  private static final String RESET = "\u001B[0m";
  private static final String DARK_GREEN = "\u001B[32m";
  private static final String GREEN = "\u001B[92m";
  private static final String YELLOW = "\u001B[33m";
  private static final String BLUE = "\u001B[94m";
  private static final String MAGENTA = "\u001B[35m";
  private static final String GRAY = "\u001B[90m";
  private static final String RED = "\u001B[31m";

  private static final List<String> LOGO =
      List.of("  ___ _    ___ ", " / __| |  / __|", " \\__ \\ |__\\__ \\", " |___/____|___/");

  private ConsoleBanner() {}

  public static void logStartup(Logger logger) {
    startupBanner().forEach(logger::info);
  }

  public static void logShutdown(Logger logger) {
    shutdownBanner().forEach(logger::info);
  }

  static List<String> logo() {
    return LOGO;
  }

  static List<String> startupBanner() {
    List<String> lines = new ArrayList<>();
    lines.add("");
    lines.add(paint(DARK_GREEN, LOGO.get(0)));
    lines.add(
        paint(DARK_GREEN, LOGO.get(1))
            + "  "
            + paint(RED, "SLS-LITE")
            + " "
            + paint(YELLOW, "v" + BuildInfo.VERSION));
    lines.add(paint(DARK_GREEN, LOGO.get(2)) + "  " + paint(GRAY, "Server Launch System"));
    lines.add(
        paint(DARK_GREEN, LOGO.get(3))
            + "  "
            + paint(BLUE, "Copyright © 2020–2026 ")
            + paint(MAGENTA, BuildInfo.AUTHORS));
    lines.add("");
    lines.add(linkLine(" Website:", BuildInfo.WEBSITE_URL));
    lines.add(linkLine(" Source: ", BuildInfo.SOURCE_URL));
    lines.add(linkLine(" License:", BuildInfo.LICENSE_URL));
    lines.add("");
    lines.add(
        paint(BLUE, " This software is made available under the terms of the ")
            + paint(MAGENTA, "GNU AGPL v3.0")
            + paint(BLUE, "."));
    lines.add(paint(BLUE, " You may redistribute and modify it under that license; source and"));
    lines.add(paint(BLUE, " warranty terms are provided in the linked LICENSE file."));
    lines.add("");
    return List.copyOf(lines);
  }

  static List<String> shutdownBanner() {
    return List.of(
        "",
        paint(GREEN, " SLS-LITE")
            + " "
            + paint(YELLOW, "v" + BuildInfo.VERSION)
            + paint(GRAY, " stopped — thanks for using SLS-LITE."),
        linkLine(" Source  ", BuildInfo.SOURCE_URL),
        "");
  }

  private static String linkLine(String label, String url) {
    return paint(BLUE, label) + " " + RESET + url + RESET;
  }

  private static String paint(String color, String text) {
    return color + text + RESET;
  }
}
