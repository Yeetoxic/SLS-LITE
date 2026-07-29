package net.slimelabs.slslite.instance;

import net.slimelabs.slslite.blueprint.Blueprint;
import net.slimelabs.slslite.blueprint.BlueprintCopy;
import net.slimelabs.slslite.blueprint.BlueprintVolume;
import net.slimelabs.slslite.software.SoftwareProfile;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

final class InstanceDefinitionIdentityTest {

    @Test
    void fingerprintIsStableAcrossMapIterationOrder() {
        Blueprint first = blueprint(
                Map.of("motd", "Test", "view-distance", "6"),
                Map.of("feature", true, "nested", Map.of("b", 2, "a", 1))
        );
        Blueprint second = blueprint(
                new java.util.LinkedHashMap<>(Map.of(
                        "view-distance", "6",
                        "motd", "Test"
                )),
                new java.util.LinkedHashMap<>(Map.of(
                        "nested", Map.of("a", 1, "b", 2),
                        "feature", true
                ))
        );

        assertEquals(
                InstanceDefinitionIdentity.from(first, profile()),
                InstanceDefinitionIdentity.from(second, profile())
        );
    }

    @Test
    void fingerprintChangesWhenPersistentContentDefinitionChanges() {
        InstanceDefinitionIdentity original = InstanceDefinitionIdentity.from(
                blueprint(Map.of("motd", "One"), Map.of()),
                profile()
        );
        InstanceDefinitionIdentity changedProperty = InstanceDefinitionIdentity.from(
                blueprint(Map.of("motd", "Two"), Map.of()),
                profile()
        );
        Blueprint changedVolume = new Blueprint(
                "fixture",
                "Fixture",
                "test",
                "paper",
                "1.21.11",
                256,
                20,
                1,
                true,
                Map.of("motd", "One"),
                Map.of(),
                List.of(new BlueprintVolume(
                        "world",
                        "worlds/other",
                        "/world",
                        BlueprintVolume.Mode.COW
                ))
        );

        assertNotEquals(original, changedProperty);
        assertNotEquals(
                original,
                InstanceDefinitionIdentity.from(changedVolume, profile())
        );

        Blueprint ephemeral = new Blueprint(
                "fixture",
                "Fixture",
                "test",
                "paper",
                "1.21.11",
                256,
                20,
                1,
                false,
                Map.of("motd", "One"),
                Map.of(),
                List.of(new BlueprintVolume(
                        "world",
                        "worlds/fixture",
                        "/world",
                        BlueprintVolume.Mode.COW
                ))
        );
        assertNotEquals(
                original,
                InstanceDefinitionIdentity.from(ephemeral, profile())
        );
    }

    @Test
    void fingerprintIncludesModernRuntimeAndYamlConfigAdaptations() {
        Blueprint original = compatibleBlueprint(
                "java_21",
                "paper/1.21.11",
                Map.of("bukkit.yml", Map.of(
                        "settings", Map.of("allow-end", false)
                ))
        );

        assertNotEquals(
                InstanceDefinitionIdentity.from(original, profile()),
                InstanceDefinitionIdentity.from(
                        compatibleBlueprint(
                                "java_25",
                                "paper/1.21.11",
                                original.yamlConfigs()
                        ),
                        profile()
                )
        );
        assertNotEquals(
                InstanceDefinitionIdentity.from(original, profile()),
                InstanceDefinitionIdentity.from(
                        compatibleBlueprint(
                                "java_21",
                                "paper/custom",
                                original.yamlConfigs()
                        ),
                        profile()
                )
        );
        assertNotEquals(
                InstanceDefinitionIdentity.from(original, profile()),
                InstanceDefinitionIdentity.from(
                        compatibleBlueprint(
                                "java_21",
                                "paper/1.21.11",
                                Map.of("bukkit.yml", Map.of(
                                        "settings", Map.of("allow-end", true)
                                ))
                        ),
                        profile()
                )
        );

        Blueprint changedTextPatch = new Blueprint(
                original.id(),
                original.name(),
                original.type(),
                original.software(),
                original.version(),
                original.image(),
                original.softwarePath(),
                original.memoryLimitMiB(),
                original.maxPlayers(),
                original.maxInstances(),
                original.save(),
                original.serverProperties(),
                original.yamlConfigs(),
                Map.of("whitelist.json", Map.of("[]", "[{\"name\":\"admin\"}]")),
                original.annotations(),
                original.volumes()
        );
        assertNotEquals(
                InstanceDefinitionIdentity.from(original, profile()),
                InstanceDefinitionIdentity.from(changedTextPatch, profile())
        );

        Blueprint changedCopy = new Blueprint(
                original.id(),
                original.name(),
                original.type(),
                original.software(),
                original.version(),
                original.image(),
                original.softwarePath(),
                original.memoryLimitMiB(),
                original.maxPlayers(),
                original.maxInstances(),
                original.save(),
                original.serverProperties(),
                original.yamlConfigs(),
                original.textFileConfigs(),
                original.annotations(),
                original.volumes(),
                List.of(new BlueprintCopy("files/source.yml", "plugins/config.yml")),
                Map.of()
        );
        assertNotEquals(
                InstanceDefinitionIdentity.from(original, profile()),
                InstanceDefinitionIdentity.from(changedCopy, profile())
        );

        Blueprint changedEnvironment = new Blueprint(
                original.id(),
                original.name(),
                original.type(),
                original.software(),
                original.version(),
                original.image(),
                original.softwarePath(),
                original.memoryLimitMiB(),
                original.maxPlayers(),
                original.maxInstances(),
                original.save(),
                original.serverProperties(),
                original.yamlConfigs(),
                original.textFileConfigs(),
                original.annotations(),
                original.volumes(),
                List.of(),
                Map.of("FEATURE_FLAG", "enabled")
        );
        assertNotEquals(
                InstanceDefinitionIdentity.from(original, profile()),
                InstanceDefinitionIdentity.from(changedEnvironment, profile())
        );
    }

    private static Blueprint blueprint(
            Map<String, String> properties,
            Map<String, Object> annotations
    ) {
        return new Blueprint(
                "fixture",
                "Fixture",
                "test",
                "paper",
                "1.21.11",
                256,
                20,
                1,
                true,
                properties,
                annotations,
                List.of(new BlueprintVolume(
                        "world",
                        "worlds/fixture",
                        "/world",
                        BlueprintVolume.Mode.COW
                ))
        );
    }

    private static Blueprint compatibleBlueprint(
            String image,
            String softwarePath,
            Map<String, Map<String, Object>> yamlConfigs
    ) {
        return new Blueprint(
                "fixture",
                "Fixture",
                "test",
                "paper",
                "1.21.11",
                image,
                softwarePath,
                256,
                20,
                1,
                true,
                Map.of(),
                yamlConfigs,
                Map.of(),
                List.of()
        );
    }

    private static SoftwareProfile profile() {
        return new SoftwareProfile(
                "paper",
                "java",
                "software/paper/{version}",
                "paper.jar",
                List.of("-Xmx{memory_mib}M"),
                List.of(),
                "Done",
                60,
                "stop",
                30
        );
    }
}
