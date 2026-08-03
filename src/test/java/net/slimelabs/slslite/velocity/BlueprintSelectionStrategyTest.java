package net.slimelabs.slslite.velocity;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;
import net.slimelabs.slslite.blueprint.Blueprint;
import org.junit.jupiter.api.Test;

class BlueprintSelectionStrategyTest {

  @Test
  void firstAvailablePrefersRequestedDefinition() {
    Blueprint requested = blueprint("requested");
    Blueprint alpha = blueprint("alpha");

    Blueprint selected =
        BlueprintSelectionStrategy.firstAvailable()
            .select(requested, List.of(alpha, requested))
            .orElseThrow();

    assertEquals(requested, selected);
  }

  @Test
  void firstAvailableUsesStableIdOrderWhenRequestedDefinitionIsIneligible() {
    Blueprint requested = blueprint("requested");
    Blueprint alpha = blueprint("alpha");
    Blueprint zulu = blueprint("zulu");

    Blueprint selected =
        BlueprintSelectionStrategy.firstAvailable()
            .select(requested, List.of(zulu, alpha))
            .orElseThrow();

    assertEquals(alpha, selected);
  }

  @Test
  void randomSelectsOnlyEligibleDefinitionsAndCanReachEachCandidate() {
    Blueprint requested = blueprint("requested");
    Blueprint alpha = blueprint("alpha");
    Blueprint zulu = blueprint("zulu");
    BlueprintSelectionStrategy strategy = BlueprintSelectionStrategy.random(new Random(782347));
    Set<Blueprint> selected = new HashSet<>();

    for (int attempt = 0; attempt < 100; attempt++) {
      selected.add(strategy.select(requested, List.of(alpha, zulu)).orElseThrow());
    }

    assertEquals(Set.of(alpha, zulu), selected);
    assertTrue(strategy.select(requested, List.of()).isEmpty());
  }

  private static Blueprint blueprint(String id) {
    return new Blueprint(id, id, "test", "paper", "26.1", 512, 20, 5, false, java.util.Map.of());
  }
}
