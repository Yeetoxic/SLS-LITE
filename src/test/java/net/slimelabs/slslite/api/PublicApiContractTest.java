package net.slimelabs.slslite.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.AnnotatedType;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import net.slimelabs.slslite.api.event.ApiShutdownEvent;
import net.slimelabs.slslite.api.event.CatalogDelta;
import net.slimelabs.slslite.api.event.CatalogReloadEvent;
import net.slimelabs.slslite.api.event.CatalogReloadFailureCategory;
import net.slimelabs.slslite.api.event.CatalogReloadScope;
import net.slimelabs.slslite.api.event.CatalogReloadStatus;
import net.slimelabs.slslite.api.event.InstanceFailureCategory;
import net.slimelabs.slslite.api.event.InstanceFailureEvent;
import net.slimelabs.slslite.api.event.InstanceFailurePhase;
import net.slimelabs.slslite.api.event.InstanceLifecycleEvent;
import net.slimelabs.slslite.api.event.LobbyRoute;
import net.slimelabs.slslite.api.event.LobbyServiceStatus;
import net.slimelabs.slslite.api.event.LobbyStatusEvent;
import net.slimelabs.slslite.api.event.MatchmakingStatus;
import net.slimelabs.slslite.api.event.PlayerMatchmakingEvent;
import net.slimelabs.slslite.api.event.ReconciliationEvent;
import net.slimelabs.slslite.api.event.SLSLiteEvent;
import net.slimelabs.slslite.api.event.SoftwareInstallationEvent;
import net.slimelabs.slslite.api.event.SoftwareInstallationFailureCategory;
import net.slimelabs.slslite.api.event.SoftwareInstallationSource;
import net.slimelabs.slslite.api.event.SoftwareInstallationStatus;
import net.slimelabs.slslite.api.event.SoftwareReleaseChannel;
import net.slimelabs.slslite.api.event.Subscription;
import org.junit.jupiter.api.Test;

class PublicApiContractTest {

  private static final List<Class<?>> API_TYPES =
      List.of(
          ApiVersion.class,
          ApiStatus.class,
          BlueprintReadinessChecker.class,
          BlueprintReadinessFinding.class,
          BlueprintReadinessStatus.class,
          Capability.class,
          DiagnosticsSnapshot.class,
          ExtensionContext.class,
          HostCapabilityState.class,
          HostCapabilityView.class,
          InstallationDiagnosticView.class,
          InstanceLogSnapshot.class,
          InstanceReadyAction.class,
          InstanceStatus.class,
          InstanceStatisticsView.class,
          VolumeView.class,
          BlueprintView.class,
          InstanceView.class,
          LobbyDiagnosticView.class,
          MaintenanceView.class,
          NamespacedAnnotations.class,
          PostTransferAction.class,
          InstanceOverrides.class,
          StartRequest.class,
          InstanceOperationResult.class,
          DeleteResult.class,
          DefinitionReloadImpact.class,
          DefinitionReloadResult.class,
          ExtensionDiagnosticContributor.class,
          ExtensionDiagnosticFinding.class,
          ExtensionDiagnosticSeverity.class,
          ExtensionDiagnosticView.class,
          InstanceTransferRequest.class,
          InstanceTransferResult.class,
          InstanceTransferStatus.class,
          SoftwareInstallationRequest.class,
          SoftwareInstallationResult.class,
          QueueRequest.class,
          QueueTicket.class,
          QueueResult.class,
          SLSLiteApiException.class,
          SLSLiteApi.class,
          SLSLiteApiProvider.class,
          SystemDiagnosticView.class,
          SLSLiteEvent.class,
          ApiShutdownEvent.class,
          CatalogDelta.class,
          CatalogReloadEvent.class,
          CatalogReloadFailureCategory.class,
          CatalogReloadScope.class,
          CatalogReloadStatus.class,
          InstanceFailureEvent.class,
          InstanceFailurePhase.class,
          InstanceFailureCategory.class,
          InstanceLifecycleEvent.class,
          LobbyRoute.class,
          LobbyServiceStatus.class,
          LobbyStatusEvent.class,
          PlayerMatchmakingEvent.class,
          ReconciliationEvent.class,
          MatchmakingStatus.class,
          SoftwareInstallationEvent.class,
          SoftwareInstallationFailureCategory.class,
          SoftwareInstallationSource.class,
          SoftwareInstallationStatus.class,
          SoftwareReleaseChannel.class,
          Subscription.class);

  @Test
  void versionAndCapabilitiesAreStable() {
    assertEquals("1.2", ApiVersion.CURRENT.toString());
    assertTrue(Set.of(Capability.values()).contains(Capability.LIFECYCLE_EVENTS));
    assertTrue(Set.of(Capability.values()).contains(Capability.PLAYER_QUEUE));
    assertTrue(Set.of(Capability.values()).contains(Capability.MATCHMAKING_EVENTS));
    assertTrue(Set.of(Capability.values()).contains(Capability.INSTANCE_FAILURE_EVENTS));
    assertTrue(Set.of(Capability.values()).contains(Capability.CATALOG_RELOAD_EVENTS));
    assertTrue(Set.of(Capability.values()).contains(Capability.LOBBY_STATUS_EVENTS));
    assertTrue(Set.of(Capability.values()).contains(Capability.SOFTWARE_INSTALLATION_EVENTS));
    assertTrue(Set.of(Capability.values()).contains(Capability.RECONCILIATION_EVENTS));
    assertTrue(Set.of(Capability.values()).contains(Capability.API_SHUTDOWN_EVENTS));
    assertTrue(Set.of(Capability.values()).contains(Capability.DIAGNOSTICS));
    assertTrue(Set.of(Capability.values()).contains(Capability.EXTENSION_CONTEXTS));
    assertTrue(Set.of(Capability.values()).contains(Capability.EXTENSION_ACTIONS));
    assertTrue(Set.of(Capability.values()).contains(Capability.EXTENSION_BLUEPRINT_READINESS));
    assertTrue(Set.of(Capability.values()).contains(Capability.INSTANCE_RESTART));
    assertTrue(Set.of(Capability.values()).contains(Capability.INSTANCE_RESET));
    assertTrue(Set.of(Capability.values()).contains(Capability.SOFTWARE_INSTALLATION_REQUESTS));
    assertTrue(Set.of(Capability.values()).contains(Capability.DEFINITION_RELOAD_REQUESTS));
    assertTrue(Set.of(Capability.values()).contains(Capability.MAINTENANCE_CONTROL));
    assertTrue(Set.of(Capability.values()).contains(Capability.EXACT_INSTANCE_TRANSFER));
    assertTrue(Set.of(Capability.values()).contains(Capability.EXTENSION_DIAGNOSTICS));
  }

  @Test
  void publicMethodsAndCapabilitiesRemainDocumented() throws Exception {
    String documentation = Files.readString(Path.of("DOCS", "Java_API.md"));

    Arrays.stream(SLSLiteApi.class.getDeclaredMethods())
        .map(method -> "`" + method.getName() + "(")
        .forEach(token -> assertTrue(documentation.contains(token), () -> "Missing " + token));
    Arrays.stream(Capability.values())
        .map(Enum::name)
        .forEach(
            capability ->
                assertTrue(
                    documentation.contains("`" + capability + "`"),
                    () -> "Missing capability " + capability));
  }

  @Test
  void publicContractDoesNotExposeImplementationPackages() {
    for (Class<?> apiType : API_TYPES) {
      for (var method : apiType.getDeclaredMethods()) {
        assertPublicType(method.getGenericReturnType(), apiType + " return type");
        for (Type parameter : method.getGenericParameterTypes()) {
          assertPublicType(parameter, apiType + " parameter");
        }
      }
      for (var constructor : apiType.getDeclaredConstructors()) {
        for (Type parameter : constructor.getGenericParameterTypes()) {
          assertPublicType(parameter, apiType + " constructor");
        }
      }
      for (AnnotatedType permitted : apiType.getAnnotatedInterfaces()) {
        assertPublicType(permitted.getType(), apiType + " interface");
      }
    }
  }

  @Test
  void blueprintViewsDeepCopyExtensionMetadata() {
    List<String> nested = new ArrayList<>(List.of("one"));
    Map<String, Object> annotation = new LinkedHashMap<>();
    annotation.put("nested", nested);
    Map<String, Object> annotations = new LinkedHashMap<>();
    annotations.put("extension", annotation);
    BlueprintView view =
        new BlueprintView(
            "arena",
            "Arena",
            "minigame",
            "paper",
            "26.2",
            1024,
            20,
            1,
            false,
            List.of(new VolumeView("world", "volumes/worlds/arena", "/world", "cow")),
            false,
            Set.of("PUBLIC_ENDPOINT"),
            annotations);

    nested.add("two");
    annotation.put("late", true);
    annotations.clear();

    Map<?, ?> copied = (Map<?, ?>) view.annotations().get("extension");
    assertEquals(List.of("one"), copied.get("nested"));
    assertEquals(false, copied.containsKey("late"));
    assertThrows(UnsupportedOperationException.class, () -> view.annotations().clear());
    assertThrows(
        UnsupportedOperationException.class, () -> ((List<?>) copied.get("nested")).clear());
    assertThrows(
        IllegalArgumentException.class,
        () ->
            new BlueprintView(
                "unsafe",
                "Unsafe",
                "test",
                "paper",
                "26.3",
                512,
                20,
                1,
                false,
                List.of(),
                false,
                Set.of(),
                Map.of("extension", new AtomicInteger(1))));
  }

  @Test
  void exactInstanceTransferIdsAreNormalizedAndBounded() {
    UUID playerId = UUID.randomUUID();

    assertEquals(
        "arena.123", new InstanceTransferRequest(playerId, "  arena.123  ", false).instanceId());
    assertThrows(
        IllegalArgumentException.class, () -> new InstanceTransferRequest(playerId, "   ", false));
    assertThrows(
        IllegalArgumentException.class,
        () -> new InstanceTransferRequest(playerId, "arena\nother", false));
    assertThrows(
        IllegalArgumentException.class,
        () -> new InstanceTransferRequest(playerId, "x".repeat(129), false));
  }

  private static void assertPublicType(Type type, String context) {
    if (type instanceof Class<?> raw) {
      if (raw.isArray()) {
        assertPublicType(raw.getComponentType(), context);
        return;
      }
      String name = raw.getName();
      assertTrue(
          !name.startsWith("net.slimelabs.slslite.")
              || name.startsWith("net.slimelabs.slslite.api."),
          () -> context + " leaks " + name);
      return;
    }
    if (type instanceof ParameterizedType parameterized) {
      assertPublicType(parameterized.getRawType(), context);
      for (Type argument : parameterized.getActualTypeArguments()) {
        assertPublicType(argument, context);
      }
    }
  }
}
