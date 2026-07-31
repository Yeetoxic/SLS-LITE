package net.slimelabs.slslite.command;

import java.util.List;

public final class VSLSCommandContract {

  static final String RELEASE = "v0.2.0";
  static final String COMMIT = "8e8b1e3cf7d2157887764c16f11b8901f8241121";

  public static final List<String> LOCAL_CREATE_MODIFIERS =
      List.of("--save=", "--memory=", "--seed=", "--view-distance=", "--enable-command-block=");

  public static final List<String> DAEMON_CREATE_MODIFIERS =
      List.of(
          "--node=",
          "--cpu=",
          "--swap=",
          "--io_weight=",
          "--disk_space=",
          "--threads=",
          "--oom_disabled=",
          "--software=",
          "--version=",
          "--image=",
          "--env=");

  public static final String FORCE = "force";
  public static final String ADDITIVE_FORCE = "--force";
  public static final String REMOTE_STATUS = "remote";
  public static final String RELOAD_CONFIG = "config";

  static final List<String> PUBLIC_ROOT = List.of("join", "list", "find", "dequeue", "version");

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
