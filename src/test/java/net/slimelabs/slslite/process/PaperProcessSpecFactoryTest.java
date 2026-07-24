package net.slimelabs.slslite.process;

import net.slimelabs.slslite.blueprint.Blueprint;
import net.slimelabs.slslite.software.SoftwareProfile;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class PaperProcessSpecFactoryTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void buildsShellFreePaperCommandWithExpandedArguments() throws Exception {
        PaperProcessSpecFactory factory = new PaperProcessSpecFactory(temporaryDirectory);
        Path instanceDirectory = temporaryDirectory.resolve("instances/game-abc123");

        ProcessSpec spec = factory.create(
                profile("paper.jar"),
                blueprint("26.1"),
                "game-abc123",
                instanceDirectory,
                25571
        );

        assertEquals("java", spec.command().get(0));
        assertTrue(spec.command().contains("-Xmx1536M"));
        assertTrue(spec.command().contains("-Dsls.port=25571"));
        assertTrue(spec.command().contains("-jar"));
        assertTrue(spec.command().contains(instanceDirectory.resolve("paper.jar")
                .toAbsolutePath().normalize().toString()));
        assertEquals(instanceDirectory.toAbsolutePath().normalize(), spec.workingDirectory());
    }

    @Test
    void resolvesVersionedSoftwareDirectoryUnderDataDirectory() throws Exception {
        PaperProcessSpecFactory factory = new PaperProcessSpecFactory(temporaryDirectory);

        Path result = factory.resolveBaseDirectory(profile("paper.jar"), "26.1");

        assertEquals(
                temporaryDirectory.resolve("software/paper/26.1").toAbsolutePath().normalize(),
                result
        );
    }

    @Test
    void rejectsUnsafeVersionAndJarTraversal() {
        PaperProcessSpecFactory factory = new PaperProcessSpecFactory(temporaryDirectory);
        Path instanceDirectory = temporaryDirectory.resolve("instances/game-abc123");

        assertThrows(
                ProcessSpecificationException.class,
                () -> factory.resolveBaseDirectory(profile("paper.jar"), "../outside")
        );
        assertThrows(
                ProcessSpecificationException.class,
                () -> factory.create(
                        profile("../outside.jar"),
                        blueprint("26.1"),
                        "game-abc123",
                        instanceDirectory,
                        25571
                )
        );
    }

    @Test
    void rejectsUnknownArgumentPlaceholder() {
        PaperProcessSpecFactory factory = new PaperProcessSpecFactory(temporaryDirectory);
        SoftwareProfile profile = new SoftwareProfile(
                "paper",
                "java",
                "software/paper/{version}",
                "paper.jar",
                List.of("-Xmx{unknown}M"),
                List.of("--nogui"),
                "Done",
                180,
                "stop",
                30
        );

        assertThrows(
                ProcessSpecificationException.class,
                () -> factory.create(
                        profile,
                        blueprint("26.1"),
                        "game-abc123",
                        temporaryDirectory.resolve("instances/game-abc123"),
                        25571
                )
        );
    }

    private static SoftwareProfile profile(String serverJar) {
        return new SoftwareProfile(
                "paper",
                "java",
                "software/paper/{version}",
                serverJar,
                List.of("-Xms{memory_mib}M", "-Xmx{memory_mib}M", "-Dsls.port={port}"),
                List.of("--nogui"),
                "Done",
                180,
                "stop",
                30
        );
    }

    private static Blueprint blueprint(String version) {
        return new Blueprint(
                "game",
                "Game",
                "game",
                "paper",
                version,
                1536,
                false,
                Map.of()
        );
    }
}
