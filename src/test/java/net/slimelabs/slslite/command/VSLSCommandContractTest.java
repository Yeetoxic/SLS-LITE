package net.slimelabs.slslite.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.Test;

final class VSLSCommandContractTest {

  @Test
  void contractTargetsTheUpstreamMainBranch() {
    assertEquals("main", VSLSCommandContract.UPSTREAM_BRANCH);
  }

  @Test
  void branchInventoryIsCompleteAndStable() {
    assertEquals(
        Set.of(
            "info.summary",
            "info.server",
            "list",
            "create",
            "start.type",
            "start.blueprint",
            "join.type",
            "join.player",
            "join.player.force",
            "join-test.server",
            "find.player",
            "system",
            "node",
            "console.server",
            "console.follow",
            "blueprint.id",
            "blueprints",
            "debug",
            "delete.server",
            "delete.all",
            "logs.server",
            "logs.follow",
            "logs.unfollow",
            "reload.default",
            "reload.mode",
            "stop.current",
            "stop.server",
            "stop.all",
            "kill.current",
            "kill.server",
            "kill.all",
            "dequeue.self",
            "dequeue.selector",
            "status.current",
            "status.server",
            "status.remote",
            "stats.current",
            "stats.server",
            "version",
            "pause",
            "resume",
            "restart.current",
            "restart.server",
            "reset.current",
            "reset.server",
            "install.info",
            "install.logs",
            "install.warmup",
            "install.cleanup",
            "maintenance",
            "admin.claim",
            "admin.code",
            "admin.add",
            "admin.remove",
            "admin.list",
            "registries"),
        VSLSCommandContract.BRANCHES.stream()
            .map(VSLSCommandContract.Branch::id)
            .collect(Collectors.toSet()));
  }

  @Test
  void branchIdsAndSyntaxesCannotOverlap() {
    Set<String> ids = new HashSet<>();
    Set<String> syntaxes = new HashSet<>();

    for (VSLSCommandContract.Branch branch : VSLSCommandContract.BRANCHES) {
      assertTrue(ids.add(branch.id()), "duplicate id: " + branch.id());
      assertTrue(syntaxes.add(branch.syntax()), "duplicate syntax: " + branch.syntax());
    }
  }

  @Test
  void registryCoversEveryRuntimeRootAndPinnedHelpRoot() {
    Set<String> contractRoots =
        VSLSCommandContract.BRANCHES.stream()
            .map(VSLSCommandContract.Branch::root)
            .collect(Collectors.toSet());
    Set<String> runtimeRoots = new HashSet<>(VSLSCommandContract.PUBLIC_SUGGESTIONS);
    runtimeRoots.addAll(VSLSCommandContract.ADMIN_SUGGESTIONS);
    Set<String> pinnedHelpRoots =
        VSLSCommandContract.ADMIN_ROOT.stream()
            .map(VSLSCommandContractTest::root)
            .collect(Collectors.toSet());

    assertEquals(contractRoots, runtimeRoots);
    assertTrue(contractRoots.containsAll(pinnedHelpRoots));
    assertTrue(contractRoots.containsAll(VSLSCommandContract.PUBLIC_ROOT));
  }

  @Test
  void accessAndSenderExceptionsAreDeclared() {
    for (VSLSCommandContract.Branch branch : VSLSCommandContract.BRANCHES) {
      if (branch.access() == VSLSCommandContract.Access.ADMIN) {
        assertFalse(branch.permissionNodes().isEmpty(), branch.id());
      }
      if (branch.access() == VSLSCommandContract.Access.PUBLIC
          || branch.access() == VSLSCommandContract.Access.BOOTSTRAP
          || branch.access() == VSLSCommandContract.Access.BUILT_IN_ADMIN) {
        assertTrue(branch.permissionNodes().isEmpty(), branch.id());
      }
    }

    assertEquals(
        Set.of(
            "join.player",
            "join.player.force",
            "debug",
            "stop.current",
            "kill.current",
            "status.current",
            "stats.current",
            "restart.current",
            "reset.current",
            "admin.claim"),
        branchesWithSender(VSLSCommandContract.Sender.PLAYER_ONLY));
    assertEquals(Set.of("admin.code"), branchesWithSender(VSLSCommandContract.Sender.CONSOLE_ONLY));
  }

  @Test
  void selectorsModifiersAndCompletionsRemainExplicit() {
    VSLSCommandContract.Branch create = branch("create");
    assertEquals(
        17,
        create.modifiers().size(),
        "six local and eleven daemon-only create modifiers must remain covered");
    assertTrue(create.modifiers().containsAll(VSLSCommandContract.LOCAL_CREATE_MODIFIERS));
    assertTrue(create.modifiers().containsAll(VSLSCommandContract.DAEMON_CREATE_MODIFIERS));

    assertEquals(List.of("all", "local", "player"), branch("join.type").selectors());
    assertEquals(
        List.of("all", "config", "blueprints", "software"), branch("reload.mode").selectors());
    assertEquals(
        List.of(VSLSCommandContract.FORCE, VSLSCommandContract.ADDITIVE_FORCE),
        branch("stop.server").modifiers());
    assertEquals(List.of(VSLSCommandContract.ADDITIVE_FORCE), branch("restart.server").modifiers());
    assertEquals(List.of(VSLSCommandContract.REMOTE_STATUS), branch("status.remote").modifiers());
    assertEquals(
        List.of(VSLSCommandContract.CONSOLE_FOLLOW, VSLSCommandContract.CONSOLE_UNFOLLOW),
        branch("console.follow").modifiers());
    assertEquals(
        List.of(
            VSLSCommandContract.Completion.SOFTWARE,
            VSLSCommandContract.Completion.SOFTWARE_VERSION),
        branch("install.logs").completions().subList(1, 3));
  }

  @Test
  void unavailableBranchesRemainVisibleCompatibilityResponses() {
    assertEquals(
        Set.of("node"),
        branchesWithAvailability(VSLSCommandContract.Availability.LOCAL_MODE_RESPONSE));
    assertEquals(
        Set.of("pause", "resume"),
        branchesWithAvailability(VSLSCommandContract.Availability.BUILD_RESPONSE));
  }

  @Test
  void registryAwarePinnedFormsRemainSeparateFromAdditiveCatalogHelpers() {
    assertEquals(VSLSCommandContract.Origin.PINNED, branch("create").origin());
    assertEquals(VSLSCommandContract.Origin.PINNED, branch("start.type").origin());
    assertEquals(VSLSCommandContract.Origin.PINNED, branch("join.type").origin());
    assertEquals(VSLSCommandContract.Origin.PINNED, branch("list").origin());
    assertTrue(branch("list").selectors().isEmpty());

    assertEquals(VSLSCommandContract.Origin.ADDITIVE, branch("start.blueprint").origin());
    assertEquals(VSLSCommandContract.Origin.ADDITIVE, branch("blueprints").origin());
    assertEquals(VSLSCommandContract.Origin.ADDITIVE, branch("registries").origin());
  }

  private static VSLSCommandContract.Branch branch(String id) {
    return VSLSCommandContract.BRANCHES.stream()
        .filter(candidate -> candidate.id().equals(id))
        .findFirst()
        .orElseThrow();
  }

  private static Set<String> branchesWithSender(VSLSCommandContract.Sender sender) {
    return VSLSCommandContract.BRANCHES.stream()
        .filter(branch -> branch.sender() == sender)
        .map(VSLSCommandContract.Branch::id)
        .collect(Collectors.toSet());
  }

  private static Set<String> branchesWithAvailability(
      VSLSCommandContract.Availability availability) {
    return VSLSCommandContract.BRANCHES.stream()
        .filter(branch -> branch.availability() == availability)
        .map(VSLSCommandContract.Branch::id)
        .collect(Collectors.toSet());
  }

  private static String root(String syntax) {
    int separator = syntax.indexOf(' ');
    return syntax.substring(0, separator < 0 ? syntax.length() : separator);
  }
}
