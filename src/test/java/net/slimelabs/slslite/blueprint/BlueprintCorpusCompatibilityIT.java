package net.slimelabs.slslite.blueprint;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class BlueprintCorpusCompatibilityIT {

    @Test
    void loadsConfiguredModernBlueprintCorpus() throws Exception {
        String configured = System.getProperty("sls.compatibility.blueprints");
        assertNotNull(
                configured,
                "Set -Dsls.compatibility.blueprints=<directory>"
        );

        BlueprintRepository repository = new BlueprintRepository(
                Path.of(configured)
        );
        repository.reload();

        assertFalse(repository.getAll().isEmpty(), "Blueprint corpus is empty");
        System.out.printf(
                "Loaded %d blueprints across registries %s%n",
                repository.getAll().size(),
                repository.getTypes()
        );
    }
}
