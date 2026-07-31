package net.slimelabs.slslite.instance;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CancellationException;
import net.slimelabs.slslite.config.ManagedOutputConfig;
import net.slimelabs.slslite.instance.configuration.InstanceLaunchConfigurator;
import net.slimelabs.slslite.instance.diagnostics.InstanceTimingReporter;
import net.slimelabs.slslite.instance.lifecycle.InstancePhaseTimings;
import net.slimelabs.slslite.instance.metadata.InstanceMetadataService;
import net.slimelabs.slslite.instance.model.InstanceState;
import net.slimelabs.slslite.instance.storage.InstanceDirectoryPreparer;
import net.slimelabs.slslite.process.ProcessSpec;
import net.slimelabs.slslite.process.ProcessSupervisor;
import net.slimelabs.slslite.process.SupervisedProcess;
import net.slimelabs.slslite.software.SoftwareProfile;
import org.slf4j.Logger;

final class InstancePreparationPipeline {

  private final InstanceManager manager;
  private final SoftwareBaseDirectoryResolver softwareDirectories;
  private final InstanceDirectoryPreparer directories;
  private final ManagedOutputConfig outputConfig;
  private final InstanceMetadataService metadata;
  private final InstanceLaunchConfigurator launchConfigurator;
  private final ProcessSupervisor processSupervisor;
  private final InstanceTimingReporter timingReporter;
  private final Logger logger;

  InstancePreparationPipeline(
      InstanceManager manager,
      SoftwareBaseDirectoryResolver softwareDirectories,
      InstanceDirectoryPreparer directories,
      ManagedOutputConfig outputConfig,
      InstanceMetadataService metadata,
      InstanceLaunchConfigurator launchConfigurator,
      ProcessSupervisor processSupervisor,
      InstanceTimingReporter timingReporter,
      Logger logger) {
    this.manager = manager;
    this.softwareDirectories = softwareDirectories;
    this.directories = directories;
    this.outputConfig = outputConfig;
    this.metadata = metadata;
    this.launchConfigurator = launchConfigurator;
    this.processSupervisor = processSupervisor;
    this.timingReporter = timingReporter;
    this.logger = logger;
  }

  void run(ManagedInstance instance, SoftwareProfile profile, boolean reuseDirectory) {
    InstancePhaseTimings timings = instance.timings();
    timings.finish(InstancePhaseTimings.Phase.DISPATCH_QUEUE);
    instance.preparationStarted();
    try {
      logger.info(
          "Preparing instance {} with {} volume(s) and {} copy entry(s)",
          instance.id(),
          instance.blueprint().volumes().size(),
          instance.blueprint().copies().size());
      timings.begin(InstancePhaseTimings.Phase.SOFTWARE_RESOLUTION);
      Path baseDirectory =
          softwareDirectories.resolve(
              profile,
              instance.blueprint().version(),
              instance.blueprint().softwarePath(),
              instance::stopRequested);
      timings.finish(InstancePhaseTimings.Phase.SOFTWARE_RESOLUTION);
      logger.info(
          "Instance software ready: {} ({} {})",
          instance.id(),
          profile.id(),
          instance.blueprint().version());
      timings.begin(InstancePhaseTimings.Phase.FILE_PREPARATION);
      Path prepared = prepareFiles(instance, baseDirectory, reuseDirectory);
      timings.finish(InstancePhaseTimings.Phase.FILE_PREPARATION);
      logger.info(
          "Instance files ready: {} ({} volume(s), {} copy entry(s))",
          instance.id(),
          instance.blueprint().volumes().size(),
          instance.blueprint().copies().size());
      if (!prepared.equals(instance.directory())) {
        throw new InstanceOperationException("Prepared instance path changed unexpectedly");
      }
      timings.begin(InstancePhaseTimings.Phase.CONFIGURATION);
      instance.configureOutput(outputConfig);
      metadata.write(instance, InstanceState.PREPARING, null);
      ProcessSpec spec =
          launchConfigurator.configure(
              profile, instance.blueprint(), instance.id(), prepared, instance.port());
      timings.finish(InstancePhaseTimings.Phase.CONFIGURATION);
      SupervisedProcess process = startProcess(instance, profile, spec, timings);
      if (process == null) {
        return;
      }
      process
          .exitFuture()
          .whenComplete(
              (exitCode, failure) ->
                  manager.dispatchExitFinalization(instance, timings, exitCode, failure));
      metadata.write(instance, InstanceState.STARTING, process);
      watchReadiness(instance, process, timings);
    } catch (Exception exception) {
      manager.failPreparation(instance, exception);
    } finally {
      instance.preparationFinished();
    }
  }

  private Path prepareFiles(ManagedInstance instance, Path baseDirectory, boolean reuseDirectory)
      throws Exception {
    if (reuseDirectory) {
      Path prepared = instance.directory();
      if (!Files.isDirectory(prepared)) {
        throw new InstancePreparationException(
            "Persistent instance directory does not exist: " + prepared);
      }
      directories.resume(instance.id());
      return prepared;
    }
    return directories.prepare(
        instance.id(),
        baseDirectory,
        instance.blueprint().volumes(),
        instance.blueprint().copies(),
        instance::stopRequested);
  }

  private SupervisedProcess startProcess(
      ManagedInstance instance,
      SoftwareProfile profile,
      ProcessSpec spec,
      InstancePhaseTimings timings)
      throws Exception {
    synchronized (instance) {
      if (instance.stopRequested()) {
        manager.finishCancelledPreparation(instance);
        return null;
      }
      metadata.write(instance, InstanceState.STARTING, null);
      timings.begin(InstancePhaseTimings.Phase.PROCESS_LAUNCH);
      SupervisedProcess process =
          processSupervisor.start(
              instance.id(), spec, instance.lifecycle(), line -> recordOutput(instance, line));
      instance.attachProcess(process);
      timings.finish(InstancePhaseTimings.Phase.PROCESS_LAUNCH);
      timings.begin(InstancePhaseTimings.Phase.READINESS);
      logger.info(
          "Instance process started: {} (PID {}, readiness timeout {} seconds)",
          instance.id(),
          process.processId(),
          profile.startupTimeoutSeconds());
      return process;
    }
  }

  private void recordOutput(ManagedInstance instance, String line) {
    instance.appendLog(line);
    if (instance.mirrorsOutputToProxyConsole()) {
      logger.info("[{}] {}", instance.id(), line);
    }
    instance
        .takeOutputFailure()
        .ifPresent(
            failure ->
                logger.warn(
                    "Temporary console log disabled for {}: {}",
                    instance.id(),
                    failure.getMessage()));
  }

  private void watchReadiness(
      ManagedInstance instance, SupervisedProcess process, InstancePhaseTimings timings) {
    process
        .readyFuture()
        .whenComplete(
            (ignored, failure) -> {
              timings.finish(InstancePhaseTimings.Phase.READINESS);
              if (failure == null) {
                manager.registerReady(instance);
                return;
              }
              Throwable cause = InstanceManager.rootCause(failure);
              if (cause instanceof CancellationException) {
                logger.info("Instance readiness cancelled: {}", instance.id());
              } else {
                logger.warn(
                    "Instance readiness failed: {}: {}. Last output: {}",
                    instance.id(),
                    InstanceManager.rootMessage(cause),
                    InstanceManager.lastOutput(instance));
                manager.recordFailedStart(instance, "readiness", cause);
              }
              timingReporter.logProvisioning(instance.id(), instance.timings(), "readiness-failed");
              instance.readyFuture().completeExceptionally(failure);
            });
  }
}
