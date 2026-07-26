package net.slimelabs.slslite.software;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

public record SoftwareProfile(
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

    public SoftwareProfile {
        runtime = runtime == null ? SoftwareRuntime.JAVA_JAR : runtime;
        configurator = configurator == null ? SoftwareConfigurator.PAPER : configurator;
        source = source == null ? SoftwareSource.MANUAL : source;
        channel = channel == null ? SoftwareReleaseChannel.STABLE : channel;
        javaExecutables = Map.copyOf(javaExecutables);
        jvmArguments = List.copyOf(jvmArguments);
        serverArguments = List.copyOf(serverArguments);
        Pattern.compile(readinessPattern);
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
                readinessPattern,
                startupTimeoutSeconds,
                stopCommand,
                stopTimeoutSeconds
        );
    }
}
