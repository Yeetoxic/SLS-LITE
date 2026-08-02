package net.slimelabs.slslite.log;

import java.util.ArrayList;
import java.util.List;
import net.slimelabs.slslite.BuildInfo;
import org.slf4j.Logger;

public final class ConsoleBanner {

  private static final String RESET = "\u001B[0m";
  private static final String DARK_GREEN = "\u001B[32m";
  private static final String GREEN = "\u001B[92m";
  private static final String YELLOW = "\u001B[93m";
  private static final String BLUE = "\u001B[94m";
  private static final String MAGENTA = "\u001B[95m";
  private static final String CYAN = "\u001B[96m";
  private static final String WHITE = "\u001B[97m";
  private static final String GRAY = "\u001B[90m";
  private static final String RED = "\u001B[31m";

  private static final List<String> LOGO =
      List.of(
          "  ___ _    ___       _    ___ _____ ___ ",
          " / __| |  / __| ___ | |  |_ _|_   _| __|",
          " \\__ \\ |__\\__ \\|___|| |__ | |  | | | _| ",
          " |___/____|___/     |____|___| |_| |___|");

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
    LOGO.stream().map(line -> paint(DARK_GREEN, line)).forEach(lines::add);
    lines.add(
        paint(RED, " SLS-LITE")
            + " "
            + paint(YELLOW, "v" + BuildInfo.VERSION)
            + " "
            + paint(CYAN, "| Standalone Server Launch System"));
    lines.add(
        paint(BLUE, " Copyright © 2020 - 2026 ")
            + paint(MAGENTA, "Protoxon, Yeetoxic & Contributors"));
    lines.add("");
    lines.add(linkLine(" Website:", BuildInfo.WEBSITE_URL));
    lines.add(linkLine(" Source: ", BuildInfo.SOURCE_URL));
    lines.add(linkLine(" License:", BuildInfo.LICENSE_URL));
    lines.add("");
    lines.add(
        paint(BLUE, " This software is made available under the terms of the ")
            + paint(MAGENTA, "GNU AGPL v3.0")
            + paint(BLUE, "."));
    lines.add(paint(BLUE, " You may redistribute and modify it under the terms of that license."));
    lines.add(paint(BLUE, " This program is distributed in the hope that it will be useful,"));
    lines.add(
        paint(BLUE, " but ")
            + paint(MAGENTA, "WITHOUT ANY WARRANTY")
            + paint(BLUE, "; without even the implied warranty of"));
    lines.add(paint(BLUE, " MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE."));
    lines.add("");
    return List.copyOf(lines);
  }

  static List<String> shutdownBanner() {
    return List.of(
        "",
        paint(GREEN, " SLS-LITE")
            + " "
            + paint(YELLOW, "v" + BuildInfo.VERSION)
            + paint(BLUE, " is shutting down."),
        paint(BLUE, " Thank you for using ") + paint(GREEN, "SLS-LITE") + paint(BLUE, "."),
        linkLine(" Source: ", BuildInfo.SOURCE_URL),
        "");
  }

  private static String linkLine(String label, String url) {
    return paint(BLUE, label) + " " + paint(WHITE, url);
  }

  private static String paint(String color, String text) {
    return color + text + RESET;
  }
}
