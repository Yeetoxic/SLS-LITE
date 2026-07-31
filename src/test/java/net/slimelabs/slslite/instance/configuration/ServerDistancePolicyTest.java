package net.slimelabs.slslite.instance.configuration;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import net.slimelabs.slslite.instance.InstancePreparationException;
import org.junit.jupiter.api.Test;

class ServerDistancePolicyTest {

  @Test
  void acceptsSupportedDistancesForModernAndCalendarVersions() {
    assertDoesNotThrow(
        () ->
            ServerDistancePolicy.validate(
                "1.18.2", Map.of("view-distance", "10", "simulation-distance", "8")));
    assertDoesNotThrow(
        () ->
            ServerDistancePolicy.validate(
                "26.1", Map.of("view-distance", "12", "simulation-distance", "12")));
  }

  @Test
  void rejectsSimulationDistanceForRecognizedLegacyMinecraftVersions() {
    InstancePreparationException failure =
        assertThrows(
            InstancePreparationException.class,
            () -> ServerDistancePolicy.validate("1.17.1", Map.of("simulation-distance", "8")));

    assertTrue(failure.getMessage().contains("1.18 or newer"));
  }

  @Test
  void rejectsInvalidOrContradictoryDistances() {
    assertThrows(
        InstancePreparationException.class,
        () -> ServerDistancePolicy.validate("1.21.11", Map.of("view-distance", "33")));
    assertThrows(
        InstancePreparationException.class,
        () -> ServerDistancePolicy.validate("1.21.11", Map.of("simulation-distance", "far")));
    assertThrows(
        InstancePreparationException.class,
        () ->
            ServerDistancePolicy.validate(
                "1.21.11", Map.of("view-distance", "6", "simulation-distance", "8")));
  }
}
