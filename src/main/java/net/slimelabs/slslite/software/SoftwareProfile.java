package net.slimelabs.slslite.software;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.regex.Pattern;

public record SoftwareProfile(
        String id,
        String name,
        SoftwareRuntime runtime,
        SoftwareConfigurator configurator,
        SoftwareSource source,
        SoftwareReleaseChannel channel,
        boolean acceptEula,
        String javaExecutable,
        Map<Integer, String> javaExecutables,
        String baseDirectory,
        String serverJar,
        List<String> jvmArguments,
        List<String> serverArguments,
        Map<String, String> serverProperties,
        String readinessPattern,
        int startupTimeoutSeconds,
        String stopCommand,
        int stopTimeoutSeconds,
        int defaultMemoryLimitMiB,
        Map<String, String> images,
        List<SoftwareVersionMapping> versionMappings,
        String defaultImage
) {

    public SoftwareProfile {
        name = name == null || name.isBlank() ? id : name;
        runtime = runtime == null ? SoftwareRuntime.JAVA_JAR : runtime;
        configurator = configurator == null ? SoftwareConfigurator.PAPER : configurator;
        source = source == null ? SoftwareSource.MANUAL : source;
        channel = channel == null ? SoftwareReleaseChannel.STABLE : channel;
        javaExecutables = Map.copyOf(javaExecutables);
        jvmArguments = List.copyOf(jvmArguments);
        serverArguments = List.copyOf(serverArguments);
        serverProperties = Map.copyOf(serverProperties);
        if (defaultMemoryLimitMiB < 0) {
            throw new IllegalArgumentException(
                    "defaultMemoryLimitMiB must not be negative"
            );
        }
        images = Map.copyOf(images);
        versionMappings = List.copyOf(versionMappings);
        defaultImage = defaultImage == null || defaultImage.isBlank()
                ? null
                : defaultImage.trim();
        Pattern.compile(readinessPattern);
    }

    public SoftwareProfile(
            String id,
            String name,
            SoftwareRuntime runtime,
            SoftwareConfigurator configurator,
            SoftwareSource source,
            SoftwareReleaseChannel channel,
            boolean acceptEula,
            String javaExecutable,
            Map<Integer, String> javaExecutables,
            String baseDirectory,
            String serverJar,
            List<String> jvmArguments,
            List<String> serverArguments,
            Map<String, String> serverProperties,
            String readinessPattern,
            int startupTimeoutSeconds,
            String stopCommand,
            int stopTimeoutSeconds
    ) {
        this(
                id,
                name,
                runtime,
                configurator,
                source,
                channel,
                acceptEula,
                javaExecutable,
                javaExecutables,
                baseDirectory,
                serverJar,
                jvmArguments,
                serverArguments,
                serverProperties,
                readinessPattern,
                startupTimeoutSeconds,
                stopCommand,
                stopTimeoutSeconds,
                0,
                Map.of(),
                List.of(),
                null
        );
    }

    public Optional<String> imageForVersion(String version) {
        for (SoftwareVersionMapping mapping : versionMappings) {
            if (mapping.matches(version)) {
                return Optional.of(mapping.image());
            }
        }
        if (defaultImage != null) {
            return Optional.of(defaultImage);
        }
        if (versionMappings.isEmpty() && images.size() == 1) {
            return Optional.of(images.keySet().iterator().next());
        }
        return Optional.empty();
    }

    public SoftwareProfile(
            String id,
            SoftwareRuntime runtime,
            SoftwareConfigurator configurator,
            SoftwareSource source,
            SoftwareReleaseChannel channel,
            boolean acceptEula,
            String javaExecutable,
            Map<Integer, String> javaExecutables,
            String baseDirectory,
            String serverJar,
            List<String> jvmArguments,
            List<String> serverArguments,
            String readinessPattern,
            int startupTimeoutSeconds,
            String stopCommand,
            int stopTimeoutSeconds
    ) {
        this(
                id,
                id,
                runtime,
                configurator,
                source,
                channel,
                acceptEula,
                javaExecutable,
                javaExecutables,
                baseDirectory,
                serverJar,
                jvmArguments,
                serverArguments,
                Map.of(),
                readinessPattern,
                startupTimeoutSeconds,
                stopCommand,
                stopTimeoutSeconds,
                0,
                Map.of(),
                List.of(),
                null
        );
    }

    public SoftwareProfile(
            String id,
            String javaExecutable,
            String baseDirectory,
            String serverJar,
            List<String> jvmArguments,
            List<String> serverArguments,
            String readinessPattern,
            int startupTimeoutSeconds,
            String stopCommand,
            int stopTimeoutSeconds
    ) {
        this(
                id,
                id,
                SoftwareRuntime.JAVA_JAR,
                SoftwareConfigurator.PAPER,
                SoftwareSource.MANUAL,
                SoftwareReleaseChannel.STABLE,
                false,
                javaExecutable,
                Map.of(),
                baseDirectory,
                serverJar,
                jvmArguments,
                serverArguments,
                Map.of(),
                readinessPattern,
                startupTimeoutSeconds,
                stopCommand,
                stopTimeoutSeconds,
                0,
                Map.of(),
                List.of(),
                null
        );
    }
}
