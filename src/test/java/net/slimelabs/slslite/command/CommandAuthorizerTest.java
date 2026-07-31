package net.slimelabs.slslite.command;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.velocitypowered.api.proxy.Player;
import java.lang.reflect.Proxy;
import java.nio.file.Path;
import java.util.Set;
import java.util.UUID;
import net.slimelabs.slslite.security.AdministratorStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class CommandAuthorizerTest {

  @TempDir Path temporaryDirectory;

  @Test
  void builtInAdministratorReceivesAllSlsPermissions() throws Exception {
    UUID uniqueId = UUID.randomUUID();
    AdministratorStore administrators = new AdministratorStore(temporaryDirectory);
    administrators.initialize();
    administrators.add(uniqueId, "Admin");
    CommandAuthorizer authorizer = new CommandAuthorizer(administrators);
    Player player = player(uniqueId, Set.of());

    assertTrue(authorizer.canAdminister(player, "start"));
    assertTrue(authorizer.canTargetOthers(player, "join"));
  }

  @Test
  void externalPermissionProvidersRemainAdditive() throws Exception {
    AdministratorStore administrators = new AdministratorStore(temporaryDirectory);
    administrators.initialize();
    CommandAuthorizer authorizer = new CommandAuthorizer(administrators);

    assertTrue(
        authorizer.canAdminister(player(UUID.randomUUID(), Set.of("sls.command.start")), "start"));
    assertFalse(authorizer.canAdminister(player(UUID.randomUUID(), Set.of()), "start"));
  }

  private static Player player(UUID uniqueId, Set<String> permissions) {
    return (Player)
        Proxy.newProxyInstance(
            Player.class.getClassLoader(),
            new Class<?>[] {Player.class},
            (proxy, method, arguments) ->
                switch (method.getName()) {
                  case "getUniqueId" -> uniqueId;
                  case "getUsername" -> "TestPlayer";
                  case "hasPermission" -> permissions.contains(arguments[0]);
                  default -> {
                    if (method.getReturnType() == boolean.class) {
                      yield false;
                    }
                    yield null;
                  }
                });
  }
}
