package net.slimelabs.slslite.install;

import java.nio.file.Path;
import java.util.function.Consumer;
import net.slimelabs.slslite.software.SoftwareProfile;
import net.slimelabs.slslite.software.SoftwareSource;

public interface SoftwareInstallationProvider {

  SoftwareSource source();

  InstallationArtifact install(
      SoftwareProfile profile, String version, Path stagingDirectory, Consumer<String> log)
      throws Exception;
}
