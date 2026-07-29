package net.slimelabs.slslite.instance;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class TextFileConfigEditorTest {

    @TempDir
    Path temporaryDirectory;

    @Test
    void replacesMatchingLinePrefixesAndPreservesOtherLines() throws Exception {
        Files.writeString(
                temporaryDirectory.resolve("whitelist.json"),
                "[]\r\nkeep=this\r\n",
                StandardCharsets.UTF_8
        );

        TextFileConfigEditor.apply(
                temporaryDirectory,
                Map.of("whitelist.json", Map.of(
                        "[]",
                        "[{\"name\":\"protoxon\"}]"
                ))
        );

        assertEquals(
                "[{\"name\":\"protoxon\"}]\nkeep=this\n",
                Files.readString(temporaryDirectory.resolve("whitelist.json"))
        );
        assertFalse(Files.exists(temporaryDirectory.resolve("whitelist.json.tmp")));
    }

    @Test
    void doesNotInsertReplacementWhenPrefixIsAbsent() throws Exception {
        Files.writeString(temporaryDirectory.resolve("ops.json"), "{}");

        TextFileConfigEditor.apply(
                temporaryDirectory,
                Map.of("ops.json", Map.of("[]", "[{\"name\":\"admin\"}]"))
        );

        assertEquals("{}\n", Files.readString(temporaryDirectory.resolve("ops.json")));
    }

    @Test
    void createsMissingTargetAsEmptyFileLikeUpstream() throws Exception {
        TextFileConfigEditor.apply(
                temporaryDirectory,
                Map.of("whitelist.json", Map.of("[]", "[{\"name\":\"admin\"}]"))
        );

        assertEquals("", Files.readString(temporaryDirectory.resolve("whitelist.json")));
    }

    @Test
    void rejectsTraversalWithoutWritingOutsideInstance() {
        assertThrows(
                java.io.IOException.class,
                () -> TextFileConfigEditor.apply(
                        temporaryDirectory,
                        Map.of("../outside.txt", Map.of("old", "new"))
                )
        );
        assertFalse(Files.exists(temporaryDirectory.getParent().resolve("outside.txt")));
    }

    @Test
    void malformedUtf8LeavesOriginalFileUnchanged() throws Exception {
        Path target = temporaryDirectory.resolve("config.txt");
        byte[] malformed = {(byte) 0xC3, (byte) 0x28};
        Files.write(target, malformed);

        assertThrows(
                java.io.IOException.class,
                () -> TextFileConfigEditor.apply(
                        temporaryDirectory,
                        Map.of("config.txt", Map.of("old", "new"))
                )
        );

        assertArrayEquals(malformed, Files.readAllBytes(target));
        assertFalse(Files.exists(temporaryDirectory.resolve("config.txt.tmp")));
    }

    @Test
    void preexistingTemporaryPathPreservesOriginalFile() throws Exception {
        Files.writeString(temporaryDirectory.resolve("config.txt"), "original");
        Files.writeString(temporaryDirectory.resolve("config.txt.tmp"), "occupied");

        assertThrows(
                java.io.IOException.class,
                () -> TextFileConfigEditor.apply(
                        temporaryDirectory,
                        Map.of("config.txt", Map.of("original", "changed"))
                )
        );

        assertEquals("original", Files.readString(temporaryDirectory.resolve("config.txt")));
        assertEquals(
                "occupied",
                Files.readString(temporaryDirectory.resolve("config.txt.tmp"))
        );
    }

    @Test
    void overlappingPrefixesAreRejectedBeforeWriting() throws Exception {
        Path target = temporaryDirectory.resolve("config.txt");
        Files.writeString(target, "server-port=25565\n");
        Map<String, String> replacements = new LinkedHashMap<>();
        replacements.put("server-", "first");
        replacements.put("server-port=", "second");

        java.io.IOException exception = assertThrows(
                java.io.IOException.class,
                () -> TextFileConfigEditor.apply(
                        temporaryDirectory,
                        Map.of("config.txt", replacements)
                )
        );

        assertTrue(exception.getMessage().contains("prefixes overlap"));
        assertEquals("server-port=25565\n", Files.readString(target));
        assertFalse(Files.exists(temporaryDirectory.resolve("config.txt.tmp")));
    }
}
