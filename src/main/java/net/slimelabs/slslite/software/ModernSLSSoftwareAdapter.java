package net.slimelabs.slslite.software;

import net.slimelabs.slslite.config.ConfigurationException;
import net.slimelabs.slslite.config.YamlValues;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

final class ModernSLSSoftwareAdapter {

    private static final Pattern VALID_ID = Pattern.compile("[a-z0-9][a-z0-9_-]{0,63}");
    private static final Pattern VALID_PROPERTY_KEY =
            Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]*");
    private static final Set<String> MODERN_KEYS = Set.of(
            "images", "mappings", "invocation", "stop-command",
            "online-signal", "install-script", "limits", "configs", "update"
    );

    private ModernSLSSoftwareAdapter() {
    }

    static boolean supports(Map<String, Object> software) {
        return software.keySet().stream().anyMatch(MODERN_KEYS::contains);
    }

    static SoftwareProfile adapt(
            Map<String, Object> root,
            Map<String, Object> software,
            Path path
    ) throws ConfigurationException {
        YamlValues.requireOnlyKeys(root, "", path, "software");
        YamlValues.requireOnlyKeys(
                software,
                "software",
                path,
                "id", "name", "images", "mappings", "invocation", "stop-command",
                "online-signal", "install-script", "limits", "configs", "update"
        );

        String id = YamlValues.requiredString(software, "id", path);
        if (!VALID_ID.matcher(id).matches()) {
            throw YamlValues.error(path, "software.id must match " + VALID_ID.pattern());
        }
        String name = YamlValues.requiredString(software, "name", path);
        validateImages(YamlValues.optionalMap(software, "images", path), path);
        validateMappings(software.get("mappings"), path);
        validateUpdate(YamlValues.optionalMap(software, "update", path), path);
        validateLimits(YamlValues.optionalMap(software, "limits", path), path);

        Invocation invocation = parseInvocation(
                YamlValues.requiredString(software, "invocation", path),
                path
        );
        String stopCommand = YamlValues.requiredString(software, "stop-command", path);
        if (stopCommand.contains("\n") || stopCommand.contains("\r")) {
            throw YamlValues.error(path, "software.stop-command must be one line");
        }
        String onlineSignal = YamlValues.requiredString(
                software,
                "online-signal",
                path
        );
        Map<String, String> serverProperties = parseServerProperties(
                YamlValues.optionalMap(software, "configs", path),
                path
        );
        int startupTimeout = parseInstallScript(
                YamlValues.optionalMap(software, "install-script", path),
                path
        );

        SoftwareConfigurator configurator = configurator(id);
        SoftwareSource source = source(id);
        return new SoftwareProfile(
                id,
                name,
                SoftwareRuntime.JAVA_JAR,
                configurator,
                source,
                SoftwareReleaseChannel.STABLE,
                false,
                invocation.javaExecutable(),
                Map.of(),
                "software/" + id + "/{version}",
                invocation.serverJar(),
                invocation.jvmArguments(),
                invocation.serverArguments(),
                serverProperties,
                Pattern.quote(onlineSignal),
                startupTimeout,
                stopCommand,
                30
        );
    }

    private static SoftwareConfigurator configurator(String id) {
        return switch (id) {
            case "paper" -> SoftwareConfigurator.PAPER;
            case "vanilla" -> SoftwareConfigurator.VANILLA;
            default -> SoftwareConfigurator.GENERIC;
        };
    }

    private static SoftwareSource source(String id) {
        return switch (id) {
            case "paper" -> SoftwareSource.PAPER;
            case "vanilla" -> SoftwareSource.VANILLA;
            default -> SoftwareSource.MANUAL;
        };
    }

    private static void validateImages(
            Map<String, Object> images,
            Path path
    ) throws ConfigurationException {
        if (images.isEmpty()) {
            throw YamlValues.error(path, "missing required field: software.images");
        }
        for (Map.Entry<String, Object> image : images.entrySet()) {
            if (image.getKey().isBlank()
                    || !(image.getValue() instanceof String value)
                    || value.isBlank()) {
                throw YamlValues.error(
                        path,
                        "software.images must map non-blank IDs to image references"
                );
            }
        }
    }

    private static void validateMappings(Object configured, Path path)
            throws ConfigurationException {
        if (configured == null) {
            return;
        }
        if (!(configured instanceof List<?> mappings)) {
            throw YamlValues.error(path, "'software.mappings' must be a list");
        }
        for (int index = 0; index < mappings.size(); index++) {
            Map<String, Object> mapping = YamlValues.asMap(
                    mappings.get(index),
                    "software.mappings[" + index + "]",
                    path
            );
            if (mapping.size() != 1
                    || !(mapping.values().iterator().next() instanceof String value)
                    || value.isBlank()) {
                throw YamlValues.error(
                        path,
                        "software.mappings[" + index
                                + "] must contain one non-blank string mapping"
                );
            }
        }
    }

    private static void validateUpdate(
            Map<String, Object> update,
            Path path
    ) throws ConfigurationException {
        YamlValues.requireOnlyKeys(update, "software.update", path, "enabled", "url");
        if (update.isEmpty()) {
            return;
        }
        YamlValues.optionalBoolean(update, "enabled", false, path);
        if (update.containsKey("url")) {
            YamlValues.optionalString(update, "url", "", path);
        }
    }

    private static void validateLimits(
            Map<String, Object> limits,
            Path path
    ) throws ConfigurationException {
        YamlValues.requireOnlyKeys(
                limits,
                "software.limits",
                path,
                "memory_limit", "swap", "io_weight", "cpu_limit", "disk_space",
                "threads", "oom_disabled"
        );
        YamlValues.optionalPositiveInt(limits, "memory_limit", 1024, path);
        for (String key : List.of("swap", "io_weight", "cpu_limit", "disk_space")) {
            YamlValues.optionalNonNegativeInt(limits, key, 0, path);
        }
        if (limits.containsKey("threads")) {
            Object threads = limits.get("threads");
            if (!(threads instanceof String)) {
                throw YamlValues.error(path, "'software.limits.threads' must be a string");
            }
        }
        YamlValues.optionalBoolean(limits, "oom_disabled", false, path);
    }

    private static int parseInstallScript(
            Map<String, Object> installScript,
            Path path
    ) throws ConfigurationException {
        YamlValues.requireOnlyKeys(
                installScript,
                "software.install-script",
                path,
                "entrypoint", "script", "skip-scripts", "skip_scripts", "warmup",
                "warmup-timeout", "warmup-retries", "post-warmup-script",
                "post-warmup-timeout", "warmup_failure_policy"
        );
        if (installScript.isEmpty()) {
            return 180;
        }
        YamlValues.requiredString(installScript, "entrypoint", path);
        YamlValues.requiredString(installScript, "script", path);
        YamlValues.optionalBoolean(installScript, "skip-scripts", false, path);
        YamlValues.optionalBoolean(installScript, "skip_scripts", false, path);
        YamlValues.optionalBoolean(installScript, "warmup", false, path);
        YamlValues.optionalNonNegativeInt(installScript, "warmup-retries", 0, path);
        if (installScript.containsKey("post-warmup-script")) {
            YamlValues.optionalString(installScript, "post-warmup-script", "", path);
        }
        YamlValues.optionalPositiveInt(
                installScript,
                "post-warmup-timeout",
                120,
                path
        );
        if (installScript.containsKey("warmup_failure_policy")) {
            String policy = YamlValues.optionalString(
                    installScript,
                    "warmup_failure_policy",
                    "fail",
                    path
            );
            if (!Set.of("fail", "continue", "retry").contains(policy)) {
                throw YamlValues.error(
                        path,
                        "software.install-script.warmup_failure_policy must be "
                                + "fail, continue, or retry"
                );
            }
        }
        return YamlValues.optionalPositiveInt(
                installScript,
                "warmup-timeout",
                300,
                path
        );
    }

    private static Map<String, String> parseServerProperties(
            Map<String, Object> configs,
            Path path
    ) throws ConfigurationException {
        YamlValues.requireOnlyKeys(configs, "software.configs", path, "server.properties");
        if (configs.isEmpty()) {
            return Map.of();
        }
        Map<String, Object> config = YamlValues.optionalMap(
                configs,
                "server.properties",
                path
        );
        YamlValues.requireOnlyKeys(
                config,
                "software.configs.server.properties",
                path,
                "parser", "find"
        );
        String parser = YamlValues.requiredString(config, "parser", path);
        if (!parser.equalsIgnoreCase("properties")) {
            throw YamlValues.error(
                    path,
                    "software.configs.server.properties.parser must be 'properties'"
            );
        }
        Map<String, Object> find = YamlValues.optionalMap(config, "find", path);
        Map<String, String> properties = new LinkedHashMap<>();
        for (Map.Entry<String, Object> entry : find.entrySet()) {
            if (!VALID_PROPERTY_KEY.matcher(entry.getKey()).matches()) {
                throw YamlValues.error(
                        path,
                        "invalid server.properties key '" + entry.getKey() + "'"
                );
            }
            Object value = entry.getValue();
            if (!(value instanceof String || value instanceof Number
                    || value instanceof Boolean)) {
                throw YamlValues.error(
                        path,
                        "software.configs.server.properties.find." + entry.getKey()
                                + " must be a scalar"
                );
            }
            String rendered = value.toString();
            if (rendered.contains("\n") || rendered.contains("\r")) {
                throw YamlValues.error(path, "server.properties values must be one line");
            }
            properties.put(
                    entry.getKey(),
                    rendered.replace("{{server.build.default.port}}", "{port}")
            );
        }
        return Map.copyOf(properties);
    }

    private static Invocation parseInvocation(String invocation, Path path)
            throws ConfigurationException {
        List<String> tokens = tokenize(invocation, path);
        if (tokens.isEmpty() || !tokens.getFirst().equals("java")) {
            throw YamlValues.error(
                    path,
                    "software.invocation must launch a Java jar directly"
            );
        }
        int jarIndex = tokens.indexOf("-jar");
        if (jarIndex < 1 || jarIndex + 1 >= tokens.size()
                || tokens.lastIndexOf("-jar") != jarIndex) {
            throw YamlValues.error(
                    path,
                    "software.invocation must contain exactly one '-jar <file>'"
            );
        }
        String serverJar = tokens.get(jarIndex + 1);
        try {
            Path jarPath = Path.of(serverJar);
            if (jarPath.isAbsolute() || jarPath.normalize().startsWith("..")) {
                throw YamlValues.error(
                        path,
                        "software.invocation server jar must be a contained relative path"
                );
            }
        } catch (InvalidPathException exception) {
            throw YamlValues.error(path, "software.invocation contains an invalid jar path");
        }

        List<String> jvmArguments = new ArrayList<>();
        for (String argument : tokens.subList(1, jarIndex)) {
            if (!argument.startsWith("-Xmx")
                    && !argument.startsWith("-XX:MaxRAMPercentage=")) {
                jvmArguments.add(argument);
            }
        }
        jvmArguments.add("-Xmx{memory_mib}M");
        return new Invocation(
                "java",
                serverJar,
                List.copyOf(jvmArguments),
                List.copyOf(tokens.subList(jarIndex + 2, tokens.size()))
        );
    }

    private static List<String> tokenize(String command, Path path)
            throws ConfigurationException {
        List<String> tokens = new ArrayList<>();
        StringBuilder token = new StringBuilder();
        char quote = 0;
        boolean escaped = false;
        for (int index = 0; index < command.length(); index++) {
            char current = command.charAt(index);
            if (escaped) {
                token.append(current);
                escaped = false;
                continue;
            }
            if (current == '\\') {
                escaped = true;
                continue;
            }
            if (quote != 0) {
                if (current == quote) {
                    quote = 0;
                } else {
                    token.append(current);
                }
                continue;
            }
            if (current == '\'' || current == '"') {
                quote = current;
                continue;
            }
            if (Character.isWhitespace(current)) {
                addToken(tokens, token);
                continue;
            }
            if ("|&;<>`".indexOf(current) >= 0
                    || current == '$' && index + 1 < command.length()
                    && command.charAt(index + 1) == '(') {
                throw YamlValues.error(
                        path,
                        "software.invocation contains unsupported shell syntax"
                );
            }
            token.append(current);
        }
        if (escaped || quote != 0) {
            throw YamlValues.error(path, "software.invocation has an incomplete escape or quote");
        }
        addToken(tokens, token);
        return List.copyOf(tokens);
    }

    private static void addToken(List<String> tokens, StringBuilder token) {
        if (!token.isEmpty()) {
            tokens.add(token.toString());
            token.setLength(0);
        }
    }

    private record Invocation(
            String javaExecutable,
            String serverJar,
            List<String> jvmArguments,
            List<String> serverArguments
    ) {
    }
}
