package net.slimelabs.slslite.config;

public record DiagnosticRetentionConfig(
    int consoleTailLines, int installerHistoryEntries, int failureReports) {

  public static final int MAX_CONSOLE_TAIL_LINES = 10_000;
  public static final int MAX_INSTALLER_HISTORY_ENTRIES = 1_000;
  public static final int MAX_FAILURE_REPORTS = 1_000;

  public static DiagnosticRetentionConfig defaults() {
    return new DiagnosticRetentionConfig(1_000, 100, 64);
  }

  public DiagnosticRetentionConfig {
    bounded(consoleTailLines, MAX_CONSOLE_TAIL_LINES, "console_tail_lines");
    bounded(installerHistoryEntries, MAX_INSTALLER_HISTORY_ENTRIES, "installer_history_entries");
    bounded(failureReports, MAX_FAILURE_REPORTS, "failure_reports");
  }

  private static void bounded(int value, int maximum, String key) {
    if (value < 0 || value > maximum) {
      throw new IllegalArgumentException(
          "diagnostics." + key + " must be between 0 and " + maximum);
    }
  }
}
