package net.slimelabs.slslite.install;

import net.slimelabs.slslite.software.SoftwareProfile;
import net.slimelabs.slslite.software.SoftwareSource;

import java.nio.file.Path;
import java.util.function.Consumer;

public interface SoftwareInstallationProvider {

    SoftwareSource source();

    InstallationArtifact install(
            SoftwareProfile profile,
            String version,
            Path stagingDirectory,
            Consumer<String> log
    ) throws Exception;
}
