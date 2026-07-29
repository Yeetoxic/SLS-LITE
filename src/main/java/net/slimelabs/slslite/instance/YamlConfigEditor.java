package net.slimelabs.slslite.instance;

import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import java.io.IOException;
import java.io.InputStream;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.util.LinkedHashMap;
import java.util.Map;

public final class YamlConfigEditor {

    private YamlConfigEditor() {
    }

    public static void apply(
            Path instanceDirectory,
            Map<String, Map<String, Object>> patches
    ) throws IOException {
        Path root = instanceDirectory.toAbsolutePath().normalize();
        for (Map.Entry<String, Map<String, Object>> patch : patches.entrySet()) {
            apply(root, patch.getKey(), patch.getValue());
        }
    }

    private static void apply(
            Path root,
            String configuredTarget,
            Map<String, Object> patch
    ) throws IOException {
        Path relative = Path.of(configuredTarget).normalize();
        if (relative.isAbsolute() || relative.startsWith("..")) {
            throw new IOException("YAML config target must stay inside the instance");
        }
        Path target = root.resolve(relative).normalize();
        if (!target.startsWith(root)) {
            throw new IOException("YAML config target must stay inside the instance");
        }
        rejectSymbolicLinks(root, relative);
        Files.createDirectories(target.getParent());

        Map<String, Object> values = new LinkedHashMap<>();
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS)) {
            if (!Files.isRegularFile(target, LinkOption.NOFOLLOW_LINKS)) {
                throw new IOException("YAML config target is not a regular file: " + target);
            }
            LoaderOptions options = new LoaderOptions();
            options.setAllowDuplicateKeys(false);
            Yaml yaml = new Yaml(new SafeConstructor(options));
            try (InputStream input = Files.newInputStream(target)) {
                Object loaded = yaml.load(input);
                if (loaded != null) {
                    values.putAll(stringMap(loaded, target));
                }
            }
        }
        merge(values, patch);

        Path temporary = target.resolveSibling(target.getFileName() + ".tmp");
        if (Files.exists(temporary, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("YAML config temporary path already exists: " + temporary);
        }
        try {
            DumperOptions options = new DumperOptions();
            options.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
            options.setPrettyFlow(true);
            options.setIndent(2);
            try (Writer output = Files.newBufferedWriter(
                    temporary,
                    StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE_NEW,
                    StandardOpenOption.WRITE
            )) {
                new Yaml(options).dump(values, output);
            }
            try {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.ATOMIC_MOVE,
                        StandardCopyOption.REPLACE_EXISTING
                );
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(
                        temporary,
                        target,
                        StandardCopyOption.REPLACE_EXISTING
                );
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    private static void rejectSymbolicLinks(Path root, Path relative) throws IOException {
        Path current = root;
        for (Path segment : relative) {
            current = current.resolve(segment);
            if (Files.isSymbolicLink(current)) {
                throw new IOException("YAML config path contains a symbolic link: " + current);
            }
        }
    }

    private static Map<String, Object> stringMap(Object configured, Path path)
            throws IOException {
        if (!(configured instanceof Map<?, ?> map)) {
            throw new IOException("YAML config root must be an object: " + path);
        }
        Map<String, Object> result = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getKey() instanceof String key)) {
                throw new IOException("YAML config contains a non-string key: " + path);
            }
            result.put(key, normalize(entry.getValue(), path));
        }
        return result;
    }

    private static Object normalize(Object value, Path path) throws IOException {
        if (value == null || value instanceof String || value instanceof Number
                || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Map<?, ?>) {
            return stringMap(value, path);
        }
        if (value instanceof java.util.List<?> list) {
            java.util.ArrayList<Object> result = new java.util.ArrayList<>();
            for (Object item : list) {
                result.add(normalize(item, path));
            }
            return result;
        }
        throw new IOException("YAML config contains an unsupported value: " + path);
    }

    @SuppressWarnings("unchecked")
    private static void merge(Map<String, Object> target, Map<String, Object> patch) {
        patch.forEach((key, value) -> {
            Object existing = target.get(key);
            if (existing instanceof Map<?, ?> existingMap
                    && value instanceof Map<?, ?> patchMap) {
                Map<String, Object> nested = new LinkedHashMap<>(
                        (Map<String, Object>) existingMap
                );
                merge(nested, (Map<String, Object>) patchMap);
                target.put(key, nested);
            } else {
                target.put(key, value);
            }
        });
    }
}
