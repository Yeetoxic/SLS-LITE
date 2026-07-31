package net.slimelabs.slslite.command;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import net.slimelabs.slslite.instance.model.InstanceLaunchOverrides;
import org.junit.jupiter.api.Test;

final class CreateOverrideParserTest {

  @Test
  void parsesTheSafeLocalOverrideSubset() {
    InstanceLaunchOverrides overrides =
        CreateOverrideParser.parse(
            new String[] {
              "create",
              "minigame",
              "arena",
              "--memory=2048",
              "--save=true",
              "--seed=fixture-seed",
              "--view-distance=10",
              "--enable-command-block=false"
            });

    assertEquals(2048, overrides.memoryLimitMiB());
    assertEquals(true, overrides.save());
    assertEquals("fixture-seed", overrides.seed());
    assertEquals(10, overrides.viewDistance());
    assertEquals(false, overrides.enableCommandBlock());
  }

  @Test
  void rejectsDaemonOnlyMalformedDuplicateAndInvalidOverrides() {
    assertRejected("--node=node-one", "unavailable in local mode");
    assertRejected("memory=1024", "--name=value");
    assertRejected("--memory=one", "integer");
    assertRejected("--save=maybe", "true or false");
    assertRejected("--view-distance=64", "between 2 and 32");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            CreateOverrideParser.parse(
                new String[] {"create", "minigame", "arena", "--memory=1024", "--memory=2048"}));
  }

  private static void assertRejected(String argument, String expected) {
    IllegalArgumentException exception =
        assertThrows(
            IllegalArgumentException.class,
            () ->
                CreateOverrideParser.parse(new String[] {"create", "minigame", "arena", argument}));
    assertTrue(exception.getMessage().contains(expected));
  }
}
