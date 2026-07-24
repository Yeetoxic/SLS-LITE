package net.slimelabs.slslite.process;

import net.slimelabs.slslite.blueprint.Blueprint;
import net.slimelabs.slslite.software.SoftwareProfile;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class PaperProcessSpecFactory {

    private static final Pattern VALID_VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern UNRESOLVED_PLACEHOLDER = Pattern.compile("\\{[^}]+}");

    private final Path dataDirectory;

    public PaperProcessSpecFactory(Path dataDirectory) {
        this.dataDirectory = dataDirectory.toAbsolutePath().normalize();
    }

    public Path resolveBaseDirectory(SoftwareProfile profile, String version)
            throws ProcessSpecificationException {
        validateVersion(version);
        String expanded = profile.baseDirectory().replace("{version}", version);
        return resolveManagedPath(expanded, "software.base_directory");
    }

    public ProcessSpec create(
            SoftwareProfile profile,
            Blueprint blueprint,
            String instanceId,
            Path instanceDirectory,
            int port
    ) throws ProcessSpecificationException {
        if (port < 1024 || port > 65535) {
            throw new ProcessSpecificationException("Port must be between 1024 and 65535");
        }
        validateVersion(blueprint.version());

        Path workingDirectory = instanceDirectory.toAbsolutePath().normalize();
        Path serverJar = resolveInside(
                workingDirectory,
                expand(profile.serverJar(), placeholders(blueprint, instanceId, port)),
                "software.server_jar"
        );
        String javaExecutable = resolveJavaExecutable(profile.javaExecutable());
        Map<String, String> placeholders = placeholders(blueprint, instanceId, port);

        List<String> command = new ArrayList<>();
        command.add(javaExecutable);
        for (String argument : profile.jvmArguments()) {
            command.add(expand(argument, placeholders));
        }
        command.add("-jar");
        command.add(serverJar.toString());
        for (String argument : profile.serverArguments()) {
            command.add(expand(argument, placeholders));
        }

        return new ProcessSpec(
                command,
                workingDirectory,
                Pattern.compile(profile.readinessPattern()),
                Duration.ofSeconds(profile.startupTimeoutSeconds()),
                profile.stopCommand(),
                Duration.ofSeconds(profile.stopTimeoutSeconds())
        );
    }

    private String resolveJavaExecutable(String configured)
            throws ProcessSpecificationException {
        if (!configured.contains("/") && !configured.contains("\\")) {
            return configured;
        }

        try {
            Path executable = Path.of(configured);
            if (executable.isAbsolute()) {
                return executable.normalize().toString();
            }
            return resolveManagedPath(configured, "launch.java").toString();
        } catch (InvalidPathException exception) {
            throw new ProcessSpecificationException(
                    "Invalid launch.java path: " + configured,
                    exception
            );
        }
    }

    private Path resolveManagedPath(String value, String key)
            throws ProcessSpecificationException {
        Path path = dataDirectory.resolve(value).normalize();
        if (!path.startsWith(dataDirectory)) {
            throw new ProcessSpecificationException(
                    key + " must stay inside " + dataDirectory
            );
        }
        return path;
    }

    private static Path resolveInside(Path root, String value, String key)
            throws ProcessSpecificationException {
        try {
            Path configured = Path.of(value);
            if (configured.isAbsolute()) {
                throw new ProcessSpecificationException(key + " must be relative");
            }
            Path resolved = root.resolve(configured).normalize();
            if (!resolved.startsWith(root)) {
                throw new ProcessSpecificationException(key + " must stay inside " + root);
            }
            return resolved;
        } catch (InvalidPathException exception) {
            throw new ProcessSpecificationException("Invalid " + key + " path", exception);
        }
    }

    private static String expand(String value, Map<String, String> placeholders)
            throws ProcessSpecificationException {
        String expanded = value;
        for (Map.Entry<String, String> placeholder : placeholders.entrySet()) {
            expanded = expanded.replace("{" + placeholder.getKey() + "}", placeholder.getValue());
        }
        if (UNRESOLVED_PLACEHOLDER.matcher(expanded).find()) {
            throw new ProcessSpecificationException(
                    "Unsupported process argument placeholder in: " + value
            );
        }
        return expanded;
    }

    private static Map<String, String> placeholders(
            Blueprint blueprint,
            String instanceId,
            int port
    ) {
        return Map.of(
                "memory_mib", Integer.toString(blueprint.memoryLimitMiB()),
                "port", Integer.toString(port),
                "instance_id", instanceId,
                "version", blueprint.version()
        );
    }

    private static void validateVersion(String version) throws ProcessSpecificationException {
        if (version == null || !VALID_VERSION.matcher(version).matches()) {
            throw new ProcessSpecificationException("Unsafe software version: " + version);
        }
    }
}
