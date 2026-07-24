package net.slimelabs.slslite.software;

import java.util.List;
import java.util.regex.Pattern;

public record SoftwareProfile(
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

    public SoftwareProfile {
        jvmArguments = List.copyOf(jvmArguments);
        serverArguments = List.copyOf(serverArguments);
        Pattern.compile(readinessPattern);
    }
}
