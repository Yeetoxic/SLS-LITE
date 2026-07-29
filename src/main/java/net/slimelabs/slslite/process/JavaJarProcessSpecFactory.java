package net.slimelabs.slslite.process;

import net.slimelabs.slslite.blueprint.Blueprint;
import net.slimelabs.slslite.software.SoftwareProfile;
import net.slimelabs.slslite.software.MinecraftJavaVersion;

import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public final class JavaJarProcessSpecFactory {

    private static final Pattern VALID_VERSION = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final Pattern JAVA_IMAGE = Pattern.compile("java[_-](\\d+)");
    private static final Pattern UNRESOLVED_PLACEHOLDER = Pattern.compile("\\{[^}]+}");

    private final Path dataDirectory;

    public JavaJarProcessSpecFactory(Path dataDirectory) {
        this.dataDirectory = dataDirectory.toAbsolutePath().normalize();
    }

    public Path resolveBaseDirectory(SoftwareProfile profile, String version)
            throws ProcessSpecificationException {
        validateVersion(version);
        String expanded = profile.baseDirectory()
                .replace("{version}", version)
                .replace(
                        "{channel}",
                        profile.channel().name().toLowerCase(java.util.Locale.ROOT)
                )
                .replace(
                        "{source}",
                        profile.source().name().toLowerCase(java.util.Locale.ROOT)
                );
        if (UNRESOLVED_PLACEHOLDER.matcher(expanded).find()) {
            throw new ProcessSpecificationException(
                    "Unsupported software.base_directory placeholder: "
                            + profile.baseDirectory()
            );
        }
        return resolveManagedPath(expanded, "software.base_directory");
    }

    public Path resolveSoftwareOverridePath(String configured)
            throws ProcessSpecificationException {
        if (configured == null || configured.isBlank()) {
            throw new ProcessSpecificationException("server.path must not be blank");
        }
        try {
            Path relative = Path.of(configured);
            if (relative.isAbsolute() || relative.normalize().startsWith("..")) {
                throw new ProcessSpecificationException(
                        "server.path must stay inside the software cache"
                );
            }
            Path softwareRoot = dataDirectory.resolve("software").normalize();
            Path resolved = softwareRoot.resolve(relative).normalize();
            if (!resolved.startsWith(softwareRoot)) {
                throw new ProcessSpecificationException(
                        "server.path must stay inside the software cache"
                );
            }
            return resolved;
        } catch (InvalidPathException exception) {
            throw new ProcessSpecificationException(
                    "Invalid server.path: " + configured,
                    exception
            );
        }
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
        String javaExecutable = resolveJavaExecutable(
                profile,
                blueprint.version(),
                blueprint.image()
        );
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

    public String resolveJavaExecutable(SoftwareProfile profile)
            throws ProcessSpecificationException {
        return resolveJavaExecutable(profile, null);
    }

    public String resolveJavaExecutable(
            SoftwareProfile profile,
            String minecraftVersion
    ) throws ProcessSpecificationException {
        return resolveJavaExecutable(profile, minecraftVersion, null);
    }

    public String resolveJavaExecutable(
            SoftwareProfile profile,
            String minecraftVersion,
            String image
    ) throws ProcessSpecificationException {
        String configured = profile.javaExecutable();
        Integer required = imageJavaMajor(image);
        if (required == null && minecraftVersion != null
                && !profile.javaExecutables().isEmpty()) {
            required = MinecraftJavaVersion.requiredMajor(
                    profile.configurator(),
                    minecraftVersion
            );
        }
        if (required != null) {
            String selected = profile.javaExecutables().get(required);
            if (selected != null) {
                configured = selected;
            } else if (image != null
                    && configured.equals("java")
                    && Runtime.version().feature() != required) {
                throw new ProcessSpecificationException(
                        "Blueprint image '" + image + "' requires Java " + required
                                + "; configure launch.java_versions.\"" + required
                                + "\" for software '" + profile.id() + "'"
                );
            }
        }
        return resolveJavaExecutable(configured);
    }

    private static Integer imageJavaMajor(String image)
            throws ProcessSpecificationException {
        if (image == null || image.isBlank()) {
            return null;
        }
        java.util.regex.Matcher matcher = JAVA_IMAGE.matcher(image);
        if (!matcher.matches()) {
            throw new ProcessSpecificationException(
                    "Blueprint image '" + image + "' is a Docker selector with no "
                            + "safe local Java mapping; use java_<major> or omit it"
            );
        }
        try {
            int major = Integer.parseInt(matcher.group(1));
            if (major < 8) {
                throw new NumberFormatException();
            }
            return major;
        } catch (NumberFormatException exception) {
            throw new ProcessSpecificationException(
                    "Blueprint image '" + image + "' has an invalid Java major"
            );
        }
    }

    public List<String> configuredJavaExecutables(SoftwareProfile profile)
            throws ProcessSpecificationException {
        List<String> configured = new ArrayList<>();
        configured.add(resolveJavaExecutable(profile.javaExecutable()));
        for (String executable : profile.javaExecutables().values()) {
            String resolved = resolveJavaExecutable(executable);
            if (!configured.contains(resolved)) {
                configured.add(resolved);
            }
        }
        return List.copyOf(configured);
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
