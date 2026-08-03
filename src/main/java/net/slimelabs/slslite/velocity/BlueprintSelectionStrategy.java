package net.slimelabs.slslite.velocity;

import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.random.RandomGenerator;
import net.slimelabs.slslite.blueprint.Blueprint;
import net.slimelabs.slslite.config.BlueprintSelectionMode;

@FunctionalInterface
public interface BlueprintSelectionStrategy {

  Optional<Blueprint> select(Blueprint requested, List<Blueprint> eligible);

  static BlueprintSelectionStrategy forMode(BlueprintSelectionMode mode) {
    return switch (Objects.requireNonNull(mode, "mode")) {
      case FIRST_AVAILABLE -> firstAvailable();
      case RANDOM -> random(RandomGenerator.getDefault());
    };
  }

  static BlueprintSelectionStrategy firstAvailable() {
    return (requested, eligible) ->
        eligible.stream()
            .sorted(
                Comparator.comparing(
                        (Blueprint candidate) -> candidate.id().equals(requested.id()) ? 0 : 1)
                    .thenComparing(Blueprint::id))
            .findFirst();
  }

  static BlueprintSelectionStrategy random(RandomGenerator random) {
    Objects.requireNonNull(random, "random");
    return (requested, eligible) ->
        eligible.isEmpty()
            ? Optional.empty()
            : Optional.of(eligible.get(random.nextInt(eligible.size())));
  }
}
