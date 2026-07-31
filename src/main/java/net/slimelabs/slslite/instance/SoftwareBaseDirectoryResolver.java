package net.slimelabs.slslite.instance;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.BooleanSupplier;
import net.slimelabs.slslite.install.SoftwareInstallationService;
import net.slimelabs.slslite.process.JavaJarProcessSpecFactory;
import net.slimelabs.slslite.process.ProcessSpecificationException;
import net.slimelabs.slslite.software.SoftwareProfile;

/**
 * Resolves configured software storage while keeping cancellation local to the
 * requesting instance rather than cancelling a shared installation.
 */
final class SoftwareBaseDirectoryResolver {

  private static final long INSTALLATION_POLL_MILLISECONDS = 100;

  private final JavaJarProcessSpecFactory paths;
  private final InstallationRequest installations;

  SoftwareBaseDirectoryResolver(
      JavaJarProcessSpecFactory paths, SoftwareInstallationService installations) {
    this(paths, installations == null ? null : installations::ensureInstalled);
  }

  SoftwareBaseDirectoryResolver(
      JavaJarProcessSpecFactory paths, InstallationRequest installations) {
    this.paths = java.util.Objects.requireNonNull(paths, "paths");
    this.installations = installations;
  }

  Path resolve(
      SoftwareProfile profile,
      String version,
      String softwarePath,
      BooleanSupplier cancellationRequested)
      throws ProcessSpecificationException {
    if (softwarePath != null) {
      if (cancellationRequested.getAsBoolean()) {
        throw new ProcessSpecificationException("Software path resolution was cancelled");
      }
      return paths.resolveSoftwareOverridePath(softwarePath);
    }
    if (installations == null) {
      return paths.resolveBaseDirectory(profile, version);
    }
    CompletableFuture<Path> installation = installations.ensureInstalled(profile, version);
    try {
      while (true) {
        if (cancellationRequested.getAsBoolean()) {
          throw new ProcessSpecificationException("Software installation wait was cancelled");
        }
        try {
          return installation.get(INSTALLATION_POLL_MILLISECONDS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ignored) {
          // Cancel only this wait, never the shared installation.
        }
      }
    } catch (InterruptedException exception) {
      Thread.currentThread().interrupt();
      throw new ProcessSpecificationException(
          "Interrupted while waiting for software installation", exception);
    } catch (ExecutionException exception) {
      Throwable cause = exception.getCause() == null ? exception : exception.getCause();
      throw new ProcessSpecificationException(
          cause.getMessage() == null ? "Software installation failed" : cause.getMessage(), cause);
    }
  }

  @FunctionalInterface
  interface InstallationRequest {
    CompletableFuture<Path> ensureInstalled(SoftwareProfile profile, String version);
  }
}
