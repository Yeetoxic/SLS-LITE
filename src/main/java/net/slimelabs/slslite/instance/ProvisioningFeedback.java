package net.slimelabs.slslite.instance;

import net.slimelabs.slslite.instance.diagnostics.FailurePhase;
import net.slimelabs.slslite.instance.lifecycle.InstancePhaseTimings;

/** Safe, path-free labels shared by player and operator presentation. */
public final class ProvisioningFeedback {

  private ProvisioningFeedback() {}

  public static String progress(ManagedInstance instance) {
    return instance
        .provisioningPhase()
        .map(ProvisioningFeedback::progress)
        .orElseGet(
            () ->
                switch (instance.state()) {
                  case READY -> "Transferring";
                  case STARTING -> "Backend readiness";
                  default -> "Queueing";
                });
  }

  public static String progress(InstancePhaseTimings.Phase phase) {
    return switch (phase) {
      case DISPATCH_QUEUE -> "Queueing";
      case SOFTWARE_RESOLUTION -> "Software installation";
      case FILE_PREPARATION, CONFIGURATION -> "Instance assembly";
      case PROCESS_LAUNCH -> "Process startup";
      case READINESS, REGISTRATION -> "Backend readiness";
      case SHUTDOWN, CLEANUP -> "Stopping";
    };
  }

  public static String failure(ManagedInstance instance) {
    return instance
        .failurePhase()
        .map(ProvisioningFeedback::failure)
        .orElseGet(
            () ->
                instance
                    .provisioningPhase()
                    .map(phase -> progress(phase).toLowerCase(java.util.Locale.ROOT))
                    .orElse("startup"));
  }

  public static String failure(FailurePhase phase) {
    return switch (phase) {
      case INSTALLATION -> "software installation";
      case PREPARATION, CONFIGURATION -> "instance assembly";
      case STARTUP -> "process startup";
      case READINESS, REGISTRATION -> "backend readiness";
      case CONNECTION -> "transfer";
      case RUNTIME -> "runtime";
      case SHUTDOWN, CLEANUP -> "shutdown";
    };
  }
}
