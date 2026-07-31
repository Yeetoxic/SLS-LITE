package net.slimelabs.slslite.command;

import java.util.List;

final class VSLSCommandContract {

  static final String RELEASE = "v0.2.0";
  static final String COMMIT = "8e8b1e3cf7d2157887764c16f11b8901f8241121";

  static final List<String> PUBLIC_ROOT = List.of("join", "list", "find", "dequeue");

  static final List<String> ADMIN_ROOT =
      List.of(
          "join",
          "create",
          "start",
          "pause",
          "resume",
          "restart",
          "debug",
          "stop",
          "kill",
          "reload",
          "status",
          "stats",
          "delete",
          "console",
          "dequeue",
          "blueprint",
          "version",
          "logs",
          "node",
          "reset",
          "info",
          "install info",
          "install logs",
          "list",
          "find",
          "system");

  private VSLSCommandContract() {}
}
