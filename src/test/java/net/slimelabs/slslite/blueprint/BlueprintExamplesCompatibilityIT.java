package net.slimelabs.slslite.blueprint;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class BlueprintExamplesCompatibilityIT {

    @TempDir
    Path temporaryDirectory;

    @Test
    void loadsEachConfiguredUpstreamExampleIndependently() throws Exception {
        String configured = System.getProperty("sls.compatibility.examples");
        assertNotNull(
                configured,
                "Set -Dsls.compatibility.examples=<directory>"
        );

        Path examples = Path.of(configured);
        List<Path> definitions;
        try (var files = Files.walk(examples)) {
            definitions = files
                    .filter(Files::isRegularFile)
                    .filter(BlueprintExamplesCompatibilityIT::isYaml)
                    .sorted()
                    .toList();
        }
        assertFalse(definitions.isEmpty(), "Blueprint example directory is empty");

        Set<String> expectedRejected = Arrays.stream(System.getProperty(
                        "sls.compatibility.expectedRejected",
                        ""
                ).split(","))
                .map(String::trim)
                .filter(value -> !value.isEmpty())
                .collect(Collectors.toSet());
        Set<String> rejected = new HashSet<>();
        int loaded = 0;
        for (int index = 0; index < definitions.size(); index++) {
            Path definition = definitions.get(index);
            String fileName = definition.getFileName().toString();
            Path testDirectory = temporaryDirectory.resolve("example-" + index);
            Files.createDirectories(testDirectory);
            Files.copy(
                    definition,
                    testDirectory.resolve(definition.getFileName()),
                    StandardCopyOption.REPLACE_EXISTING
            );
            BlueprintRepository repository = new BlueprintRepository(testDirectory);
            try {
                repository.reload();
            } catch (BlueprintException exception) {
                if (!expectedRejected.contains(fileName)) {
                    throw exception;
                }
                rejected.add(fileName);
                continue;
            }
            assertFalse(
                    repository.getAll().isEmpty(),
                    () -> "No blueprint loaded from " + definition
            );
            loaded++;
        }

        assertEquals(
                expectedRejected,
                rejected,
                "Expected-rejection list no longer matches upstream behavior"
        );
        System.out.printf(
                "Loaded %d upstream blueprint examples; rejected %s as expected%n",
                loaded,
                rejected
        );
    }

    private static boolean isYaml(Path path) {
        String name = path.getFileName().toString().toLowerCase(java.util.Locale.ROOT);
        return name.endsWith(".yml") || name.endsWith(".yaml");
    }
}
