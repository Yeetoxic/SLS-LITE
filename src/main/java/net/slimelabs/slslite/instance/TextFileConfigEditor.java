package net.slimelabs.slslite.instance;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.Map;

public final class TextFileConfigEditor {

    private static final long MAX_FILE_BYTES = 8L * 1024 * 1024;

    private TextFileConfigEditor() {
    }

    public static void apply(
            Path instanceDirectory,
            Map<String, Map<String, String>> patches
    ) throws IOException {
        Path root = instanceDirectory.toAbsolutePath().normalize();
        for (Map.Entry<String, Map<String, String>> patch : patches.entrySet()) {
            apply(root, patch.getKey(), patch.getValue());
        }
    }

    private static void apply(
            Path root,
            String configuredTarget,
            Map<String, String> replacements
    ) throws IOException {
        validatePrefixes(replacements);
        if (configuredTarget == null || configuredTarget.isBlank()) {
            throw new IOException("Text config target must not be blank");
        }
        Path relative = Path.of(configuredTarget).normalize();
        if (relative.toString().isBlank()
                || relative.isAbsolute()
                || relative.startsWith("..")) {
            throw new IOException("Text config target must stay inside the instance");
        }
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) {
            throw new IOException("Text config target must stay inside the instance");
        }
        rejectSymbolicLinks(root, relative);
        Files.createDirectories(target.getParent());
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)
                && !Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Text config target is not a regular file: " + target);
        }
        if (Files.exists(target) && Files.size(target) > MAX_FILE_BYTES) {
            throw new IOException(
                    "Text config target exceeds " + MAX_FILE_BYTES + " bytes: " + target
            );
        }

        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("Text config temporary path already exists: " + temporary);
        }
        try {
            try (Writer output = Files.newBufferedWriter(
                    temporary,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
            )) {
                if (Files.exists(target)) {
                    try (BufferedReader input = Files.newBufferedReader(
                            target,
                            StandardCharsets.UTF_8
                    )) {
                        String line;
                        while ((line = input.readLine()) != null) {
                            boolean replaced = false;
                            for (Map.Entry<String, String> replacement
                                    : replacements.entrySet()) {
                                if (line.startsWith(replacement.getKey())) {
                                    output.write(replacement.getValue());
                                    replaced = true;
                                }
                            }
                            if (!replaced) {
                                output.write(line);
                            }
                            output.write('\n');
                        }
                    }
                }
            }
            if (Files.size(temporary) > MAX_FILE_BYTES) {
                throw new IOException(
                        "Patched text config exceeds " + MAX_FILE_BYTES
                                + " bytes: " + target
                );
            }
            moveAtomically(temporary, target);
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void validatePrefixes(Map<String, String> replacements)
            throws IOException {
        java.util.List<String> prefixes = java.util.List.copyOf(
                replacements.keySet()
        );
        for (int left = 0; left < prefixes.size(); left++) {
            for (int right = left + 1; right < prefixes.size(); right++) {
                String first = prefixes.get(left);
                String second = prefixes.get(right);
                if (first.startsWith(second) || second.startsWith(first)) {
                    throw new IOException(
                            "Text config replacement prefixes overlap: '"
                                    + first + "' and '" + second + "'"
                    );
                }
            }
        }
    }

    private static void rejectSymbolicLinks(Path root, Path relative) throws IOException {
        Path current = root;
        for (Path segment : relative) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("Text config path contains a symbolic link: " + current);
            }
        }
    }

    private static void moveAtomically(Path source, Path target) throws IOException {
        try {
            Files.move(
                    source,
                    target,
                    StandardCopyOption.ATOMIC_MOVE,
                    StandardCopyOption.REPLACE_EXISTING
            );
        } catch (AtomicMoveNotSupportedException ignored) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
