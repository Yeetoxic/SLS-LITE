package net.slimelabs.slslite.command;

import java.util.List;
import java.util.Objects;

/**
 * Machine-readable command contract pinned to the upstream vSLS release.
 *
 * <p>The runtime remains intentionally hand-written, but every supported, adapted, additive, or
 * intentionally unavailable branch must be represented here and verified by contract tests.
 */
public final class VSLSCommandContract {

  static final String RELEASE = "v0.2.0";
  static final String COMMIT = "8e8b1e3cf7d2157887764c16f11b8901f8241121";

  public static final List<String> LOCAL_CREATE_MODIFIERS =
      List.of(
          "--save=",
          "--memory=",
          "--seed=",
          "--view-distance=",
          "--simulation-distance=",
          "--enable-command-block=");

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
  public static final String CONSOLE_FOLLOW = "--follow";
  public static final String CONSOLE_UNFOLLOW = "--unfollow";

  static final List<String> PUBLIC_ROOT = List.of("join", "list", "find", "dequeue", "version");

  static final List<String> PUBLIC_SUGGESTIONS =
      List.of("admin", "dequeue", "find", "info", "join", "list", "registries", "version");

  static final List<String> ADMIN_SUGGESTIONS =
      List.of(
          "blueprint",
          "blueprints",
          "console",
          "create",
          "debug",
          "delete",
          "install",
          "join-test",
          "kill",
          "logs",
          "node",
          "pause",
          "reload",
          "reset",
          "restart",
          "resume",
          "start",
          "stats",
          "status",
          "stop",
          "system");

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

  public static final List<Branch> BRANCHES =
      List.of(
          publicBranch("info.summary", "info", Origin.ADDITIVE),
          adminBranch("info.server", "info <server|this>", "info", Completion.INSTANCE_OR_THIS),
          publicBranch("list", "list", Origin.PINNED),
          adminBranch(
              "create",
              "create <type> <id> [flags...]",
              "create",
              List.of(),
              combinedCreateModifiers(),
              Completion.BLUEPRINT_TYPE,
              Completion.BLUEPRINT_ID_FOR_TYPE,
              Completion.CREATE_MODIFIER),
          adminBranch(
              "start.type",
              "start <type> <id>",
              "start",
              Completion.BLUEPRINT_TYPE,
              Completion.BLUEPRINT_ID_FOR_TYPE),
          branch(
              "start.blueprint",
              "start <blueprint>",
              Origin.ADDITIVE,
              Availability.ADAPTED,
              Access.ADMIN,
              Sender.ANY,
              List.of("sls.command.start"),
              List.of(),
              List.of(),
              Completion.BLUEPRINT),
          branch(
              "join.type",
              "join <type> <id> [all|local|player]",
              Origin.PINNED,
              Availability.ADAPTED,
              Access.SELF_PUBLIC_OTHER_ADMIN,
              Sender.ANY,
              List.of("sls.command.join.others", "sls.command.join"),
              List.of("all", "local", "player"),
              List.of(),
              Completion.BLUEPRINT_TYPE,
              Completion.BLUEPRINT_ID_FOR_TYPE,
              Completion.JOIN_SELECTOR),
          branch(
              "join.player",
              "join player <player>",
              Origin.PINNED,
              Availability.ADAPTED,
              Access.PUBLIC,
              Sender.PLAYER_ONLY,
              List.of(),
              List.of("player"),
              List.of(),
              Completion.JOIN_MODE,
              Completion.PLAYER),
          branch(
              "join.player.force",
              "join player <player> --force",
              Origin.ADDITIVE,
              Availability.ADAPTED,
              Access.ADMIN,
              Sender.ANY,
              List.of("sls.command.join"),
              List.of("player"),
              List.of(ADDITIVE_FORCE),
              Completion.JOIN_MODE,
              Completion.PLAYER,
              Completion.FORCE),
          branch(
              "join-test.server",
              "join-test <server|this>",
              Origin.ADDITIVE,
              Availability.SUPPORTED,
              Access.ADMIN,
              Sender.ANY,
              List.of("sls.command.join-test"),
              List.of("this"),
              List.of(),
              Completion.INSTANCE_OR_THIS),
          publicBranch("find.player", "find <player>", Origin.PINNED, Completion.PLAYER),
          adminBranch("system", "system", "system"),
          unavailableBranch(
              "node", "node <id> [drained [value]]", "node", Availability.LOCAL_MODE_RESPONSE),
          adminBranch(
              "console.server",
              "console <server|this> <command...>",
              "console",
              Completion.INSTANCE_OR_THIS),
          branch(
              "console.follow",
              "console <server|this> <--follow|--unfollow>",
              Origin.ADDITIVE,
              Availability.SUPPORTED,
              Access.ADMIN,
              Sender.ANY,
              List.of("sls.command.console"),
              List.of(),
              List.of(CONSOLE_FOLLOW, CONSOLE_UNFOLLOW),
              Completion.INSTANCE_OR_THIS,
              Completion.CONSOLE_FOLLOW),
          adminBranch("blueprint.id", "blueprint <id>", "blueprint", Completion.BLUEPRINT),
          branch(
              "blueprints",
              "blueprints [registry]",
              Origin.ADDITIVE,
              Availability.SUPPORTED,
              Access.ADMIN,
              Sender.ANY,
              List.of("sls.command.blueprints"),
              List.of(),
              List.of(),
              Completion.REGISTRY),
          playerAdminBranch("debug", "debug", "debug"),
          adminBranch(
              "delete.server", "delete <server|this>", "delete", Completion.INSTANCE_OR_PERSISTENT),
          adminBranch("delete.all", "delete all", "delete", List.of("all"), List.of()),
          adminBranch(
              "logs.server",
              "logs <server|this> [page] [lines]",
              "logs",
              Completion.INSTANCE_OR_THIS,
              Completion.LOG_PAGE,
              Completion.LOG_LINES),
          adminBranch("reload.default", "reload", "reload"),
          adminBranch(
              "reload.mode",
              "reload <all|config|blueprints|software>",
              "reload",
              List.of("all", "config", "blueprints", "software"),
              List.of(),
              Completion.RELOAD_MODE),
          playerAdminBranch("stop.current", "stop", "stop"),
          adminBranch(
              "stop.server",
              "stop <server|this> [force|--force]",
              "stop",
              List.of("this"),
              List.of(FORCE, ADDITIVE_FORCE),
              Completion.INSTANCE_OR_THIS,
              Completion.FORCE),
          protectedAdminBranch(
              "stop.all",
              "stop all [force|--force]",
              "stop",
              List.of("all"),
              List.of(FORCE, ADDITIVE_FORCE),
              Completion.FORCE),
          playerAdminBranch("kill.current", "kill", "kill"),
          adminBranch(
              "kill.server",
              "kill <server|this> [force|--force]",
              "kill",
              List.of("this"),
              List.of(FORCE, ADDITIVE_FORCE),
              Completion.INSTANCE_OR_THIS,
              Completion.FORCE),
          protectedAdminBranch(
              "kill.all",
              "kill all [force|--force]",
              "kill",
              List.of("all"),
              List.of(FORCE, ADDITIVE_FORCE),
              Completion.FORCE),
          publicBranch("dequeue.self", "dequeue", Origin.PINNED),
          branch(
              "dequeue.selector",
              "dequeue <all|local|player>",
              Origin.PINNED,
              Availability.SUPPORTED,
              Access.SELF_PUBLIC_OTHER_ADMIN,
              Sender.ANY,
              List.of("sls.command.dequeue.others", "sls.command.dequeue"),
              List.of("all", "local", "player"),
              List.of(),
              Completion.DEQUEUE_SELECTOR),
          playerAdminBranch("status.current", "status", "status"),
          adminBranch(
              "status.server", "status <server|this>", "status", Completion.INSTANCE_OR_THIS),
          adminBranch(
              "status.remote",
              "status <server|this> remote",
              "status",
              List.of("this"),
              List.of(REMOTE_STATUS),
              Completion.INSTANCE_OR_THIS,
              Completion.REMOTE),
          playerAdminBranch("stats.current", "stats", "stats"),
          adminBranch("stats.server", "stats <server|this>", "stats", Completion.INSTANCE_OR_THIS),
          publicBranch("version", "version", Origin.PINNED),
          unavailableBranch(
              "pause", "pause <server>", "pause", Availability.BUILD_RESPONSE, Completion.INSTANCE),
          unavailableBranch(
              "resume",
              "resume <server>",
              "resume",
              Availability.BUILD_RESPONSE,
              Completion.INSTANCE),
          playerAdminBranch("restart.current", "restart", "restart"),
          protectedAdminBranch(
              "restart.server",
              "restart <server|this> [--force]",
              "restart",
              List.of("this"),
              List.of(ADDITIVE_FORCE),
              Completion.INSTANCE_OR_PERSISTENT,
              Completion.FORCE),
          playerAdminBranch("reset.current", "reset", "reset"),
          protectedAdminBranch(
              "reset.server",
              "reset <server|this> [--force]",
              "reset",
              List.of("this"),
              List.of(ADDITIVE_FORCE),
              Completion.INSTANCE_OR_PERSISTENT,
              Completion.FORCE),
          adminBranch("install.info", "install info", "install", List.of("info"), List.of()),
          adminBranch(
              "install.logs",
              "install logs <software> <version>",
              "install",
              List.of("logs"),
              List.of(),
              Completion.INSTALL_MODE,
              Completion.SOFTWARE,
              Completion.SOFTWARE_VERSION),
          branch(
              "install.warmup",
              "install warmup <software> <version>",
              Origin.ADDITIVE,
              Availability.SUPPORTED,
              Access.ADMIN,
              Sender.ANY,
              List.of("sls.command.install"),
              List.of("warmup"),
              List.of(),
              Completion.INSTALL_MODE,
              Completion.SOFTWARE,
              Completion.SOFTWARE_VERSION),
          branch(
              "install.cleanup",
              "install cleanup <minimum-age-hours> [--confirm]",
              Origin.ADDITIVE,
              Availability.SUPPORTED,
              Access.ADMIN,
              Sender.ANY,
              List.of("sls.command.install"),
              List.of("cleanup"),
              List.of("--confirm"),
              Completion.INSTALL_MODE),
          branch(
              "admin.claim",
              "admin claim <code>",
              Origin.ADDITIVE,
              Availability.SUPPORTED,
              Access.BOOTSTRAP,
              Sender.PLAYER_ONLY,
              List.of(),
              List.of("claim"),
              List.of(),
              Completion.ADMIN_ACTION),
          branch(
              "admin.code",
              "admin code",
              Origin.ADDITIVE,
              Availability.SUPPORTED,
              Access.BUILT_IN_ADMIN,
              Sender.CONSOLE_ONLY,
              List.of(),
              List.of("code"),
              List.of(),
              Completion.ADMIN_ACTION),
          adminAdditiveBranch(
              "admin.add",
              "admin add <player>",
              List.of("add"),
              Completion.ADMIN_ACTION,
              Completion.PLAYER),
          adminAdditiveBranch(
              "admin.remove",
              "admin remove <player>",
              List.of("remove"),
              Completion.ADMIN_ACTION,
              Completion.ADMINISTRATOR),
          adminAdditiveBranch("admin.list", "admin list", List.of("list"), Completion.ADMIN_ACTION),
          publicBranch("registries", "registries", Origin.ADDITIVE));

  public enum Origin {
    PINNED,
    ADDITIVE
  }

  public enum Availability {
    SUPPORTED,
    ADAPTED,
    LOCAL_MODE_RESPONSE,
    BUILD_RESPONSE
  }

  public enum Access {
    PUBLIC,
    ADMIN,
    SELF_PUBLIC_OTHER_ADMIN,
    BOOTSTRAP,
    BUILT_IN_ADMIN
  }

  public enum Sender {
    ANY,
    PLAYER_ONLY,
    CONSOLE_ONLY
  }

  public enum Completion {
    BLUEPRINT_TYPE,
    BLUEPRINT_ID_FOR_TYPE,
    BLUEPRINT,
    CREATE_MODIFIER,
    INSTANCE,
    INSTANCE_OR_THIS,
    INSTANCE_OR_PERSISTENT,
    PLAYER,
    JOIN_MODE,
    JOIN_SELECTOR,
    DEQUEUE_SELECTOR,
    FORCE,
    RELOAD_MODE,
    REMOTE,
    LOG_PAGE,
    LOG_LINES,
    REGISTRY,
    INSTALL_MODE,
    SOFTWARE,
    SOFTWARE_VERSION,
    ADMIN_ACTION,
    ADMINISTRATOR,
    CONSOLE_FOLLOW
  }

  public record Branch(
      String id,
      String syntax,
      Origin origin,
      Availability availability,
      Access access,
      Sender sender,
      List<String> permissionNodes,
      List<String> selectors,
      List<String> modifiers,
      List<Completion> completions) {

    public Branch {
      if (id == null || id.isBlank() || syntax == null || syntax.isBlank()) {
        throw new IllegalArgumentException("Command branch id and syntax must not be blank");
      }
      Objects.requireNonNull(origin, "origin");
      Objects.requireNonNull(availability, "availability");
      Objects.requireNonNull(access, "access");
      Objects.requireNonNull(sender, "sender");
      permissionNodes = List.copyOf(permissionNodes);
      selectors = List.copyOf(selectors);
      modifiers = List.copyOf(modifiers);
      completions = List.copyOf(completions);
    }

    public String root() {
      int separator = syntax.indexOf(' ');
      return syntax.substring(0, separator < 0 ? syntax.length() : separator);
    }
  }

  private static Branch publicBranch(
      String id, String syntax, Origin origin, Completion... values) {
    return branch(
        id,
        syntax,
        origin,
        Availability.SUPPORTED,
        Access.PUBLIC,
        Sender.ANY,
        List.of(),
        List.of(),
        List.of(),
        values);
  }

  private static Branch adminBranch(
      String id, String syntax, String operation, Completion... values) {
    return adminBranch(id, syntax, operation, List.of(), List.of(), values);
  }

  private static Branch adminBranch(
      String id,
      String syntax,
      String operation,
      List<String> selectors,
      List<String> modifiers,
      Completion... values) {
    return branch(
        id,
        syntax,
        Origin.PINNED,
        Availability.ADAPTED,
        Access.ADMIN,
        Sender.ANY,
        List.of("sls.command." + operation),
        selectors,
        modifiers,
        values);
  }

  private static Branch playerAdminBranch(String id, String syntax, String operation) {
    return branch(
        id,
        syntax,
        Origin.PINNED,
        Availability.ADAPTED,
        Access.ADMIN,
        Sender.PLAYER_ONLY,
        List.of("sls.command." + operation),
        List.of(),
        List.of());
  }

  private static Branch protectedAdminBranch(
      String id,
      String syntax,
      String operation,
      List<String> selectors,
      List<String> modifiers,
      Completion... values) {
    return branch(
        id,
        syntax,
        Origin.PINNED,
        Availability.ADAPTED,
        Access.ADMIN,
        Sender.ANY,
        List.of("sls.command." + operation, "sls.command." + operation + ".force"),
        selectors,
        modifiers,
        values);
  }

  private static Branch unavailableBranch(
      String id, String syntax, String operation, Availability availability, Completion... values) {
    return branch(
        id,
        syntax,
        Origin.PINNED,
        availability,
        Access.ADMIN,
        Sender.ANY,
        List.of("sls.command." + operation),
        List.of(),
        List.of(),
        values);
  }

  private static Branch adminAdditiveBranch(
      String id, String syntax, List<String> selectors, Completion... values) {
    return branch(
        id,
        syntax,
        Origin.ADDITIVE,
        Availability.SUPPORTED,
        Access.ADMIN,
        Sender.ANY,
        List.of(CommandPermissions.ADMIN),
        selectors,
        List.of(),
        values);
  }

  private static Branch branch(
      String id,
      String syntax,
      Origin origin,
      Availability availability,
      Access access,
      Sender sender,
      List<String> permissions,
      List<String> selectors,
      List<String> modifiers,
      Completion... completions) {
    return new Branch(
        id,
        syntax,
        origin,
        availability,
        access,
        sender,
        permissions,
        selectors,
        modifiers,
        List.of(completions));
  }

  private static List<String> combinedCreateModifiers() {
    java.util.ArrayList<String> modifiers = new java.util.ArrayList<>(LOCAL_CREATE_MODIFIERS);
    modifiers.addAll(DAEMON_CREATE_MODIFIERS);
    return List.copyOf(modifiers);
  }

  private VSLSCommandContract() {}
}
