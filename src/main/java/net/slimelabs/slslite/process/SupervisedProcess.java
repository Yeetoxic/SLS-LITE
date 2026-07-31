package net.slimelabs.slslite.process;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import net.slimelabs.slslite.instance.lifecycle.InstanceLifecycle;
import net.slimelabs.slslite.instance.model.InstanceState;

public final class SupervisedProcess {

  private final ProcessSupervisor owner;
  private final String instanceId;
  private final ProcessSpec spec;
  private final InstanceLifecycle lifecycle;
  private final Consumer<String> outputConsumer;
  private final CompletableFuture<Void> ready = new CompletableFuture<>();
  private final CompletableFuture<Integer> exit = new CompletableFuture<>();

  private Process process;
  private BufferedWriter input;
  private volatile Instant startedAt;
  private ScheduledFuture<?> startupDeadline;
  private ScheduledFuture<?> stopDeadline;

  SupervisedProcess(
      ProcessSupervisor owner,
      String instanceId,
      ProcessSpec spec,
      InstanceLifecycle lifecycle,
      Consumer<String> outputConsumer) {
    this.owner = owner;
    this.instanceId = instanceId;
    this.spec = spec;
    this.lifecycle = lifecycle;
    this.outputConsumer = outputConsumer;
  }

  synchronized void start() throws ProcessStartException {
    if (lifecycle.state() != InstanceState.PREPARING) {
      throw new ProcessStartException(
          "Instance " + instanceId + " must be PREPARING before process start");
    }

    lifecycle.transitionTo(InstanceState.STARTING);
    ProcessBuilder builder =
        new ProcessBuilder(spec.command())
            .directory(spec.workingDirectory().toFile())
            .redirectErrorStream(true);
    builder.environment().putAll(spec.environment());

    try {
      process = builder.start();
      input =
          new BufferedWriter(
              new OutputStreamWriter(process.getOutputStream(), StandardCharsets.UTF_8));
      startedAt = Instant.now();
      owner.executeOutputReader(this::readOutput);
      process.onExit().thenAccept(completed -> handleExit(completed.exitValue()));
      startupDeadline =
          owner.schedule(
              this::handleStartupTimeout, spec.startupTimeout().toMillis(), TimeUnit.MILLISECONDS);
    } catch (IOException | RuntimeException exception) {
      transitionToFailed();
      ready.completeExceptionally(exception);
      if (process != null) {
        process.destroyForcibly();
      }
      throw new ProcessStartException(
          "Unable to start process for instance " + instanceId, exception);
    }
  }

  public String instanceId() {
    return instanceId;
  }

  public InstanceState state() {
    return lifecycle.state();
  }

  public Instant startedAt() {
    return startedAt;
  }

  public synchronized long processId() {
    if (process == null) {
      throw new IllegalStateException("Instance process has not started: " + instanceId);
    }
    return process.pid();
  }

  public synchronized Optional<Instant> processStartedAt() {
    if (process == null) {
      return Optional.empty();
    }
    return process.info().startInstant();
  }

  public CompletableFuture<Void> readyFuture() {
    return ready;
  }

  public CompletableFuture<Integer> exitFuture() {
    return exit;
  }

  public synchronized void sendCommand(String command) throws IOException {
    if (command == null || command.isBlank()) {
      throw new IllegalArgumentException("command must not be blank");
    }
    if (command.contains("\n") || command.contains("\r")) {
      throw new IllegalArgumentException("command must be one line");
    }
    if (lifecycle.state() != InstanceState.READY || process == null || !process.isAlive()) {
      throw new IllegalStateException(
          "Instance " + instanceId + " is not ready for console commands");
    }
    input.write(command);
    input.newLine();
    input.flush();
  }

  public synchronized CompletableFuture<Integer> stop() {
    if (process == null) {
      return exit;
    }
    if (!process.isAlive()) {
      return exit;
    }

    InstanceState state = lifecycle.state();
    if (state == InstanceState.STARTING
        || state == InstanceState.READY
        || state == InstanceState.FAILED) {
      lifecycle.transitionTo(InstanceState.STOPPING);
    } else if (state == InstanceState.STOPPING) {
      return exit;
    } else {
      throw new IllegalStateException("Cannot stop instance " + instanceId + " while " + state);
    }

    cancel(startupDeadline);
    try {
      input.write(spec.stopCommand());
      input.newLine();
      input.flush();
    } catch (IOException exception) {
      outputConsumer.accept(
          "Unable to send stop command to " + instanceId + ": " + exception.getMessage());
    }

    stopDeadline =
        owner.schedule(
            this::forceStopAfterTimeout, spec.stopTimeout().toMillis(), TimeUnit.MILLISECONDS);
    return exit;
  }

  public synchronized CompletableFuture<Integer> cancelStartup() {
    if (process == null || !process.isAlive()) {
      return exit;
    }
    if (lifecycle.state() == InstanceState.STOPPING) {
      return exit;
    }
    if (lifecycle.state() != InstanceState.STARTING) {
      throw new IllegalStateException(
          "Cannot cancel startup for instance " + instanceId + " while " + lifecycle.state());
    }

    lifecycle.transitionTo(InstanceState.STOPPING);
    cancel(startupDeadline);
    ready.completeExceptionally(
        new CancellationException("Instance startup was cancelled: " + instanceId));
    process.destroyForcibly();
    return exit;
  }

  public synchronized void forceStop() {
    if (process != null && process.isAlive()) {
      InstanceState state = lifecycle.state();
      if (state == InstanceState.STARTING
          || state == InstanceState.READY
          || state == InstanceState.FAILED) {
        lifecycle.transitionTo(InstanceState.STOPPING);
      }
      process.destroyForcibly();
    }
  }

  public synchronized CompletableFuture<Integer> kill() {
    if (process == null || !process.isAlive()) {
      return exit;
    }
    InstanceState state = lifecycle.state();
    if (state == InstanceState.STARTING) {
      cancel(startupDeadline);
      ready.completeExceptionally(
          new CancellationException("Instance was force-terminated: " + instanceId));
      lifecycle.transitionTo(InstanceState.STOPPING);
    } else if (state == InstanceState.READY || state == InstanceState.FAILED) {
      lifecycle.transitionTo(InstanceState.STOPPING);
    } else if (state != InstanceState.STOPPING) {
      throw new IllegalStateException("Cannot kill instance " + instanceId + " while " + state);
    }
    cancel(stopDeadline);
    process.destroyForcibly();
    return exit;
  }

  private void readOutput() {
    try (BufferedReader reader =
        new BufferedReader(
            new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8))) {
      String line;
      while ((line = reader.readLine()) != null) {
        outputConsumer.accept(line);
        handleOutputLine(line);
      }
    } catch (IOException exception) {
      synchronized (this) {
        if (process != null && process.isAlive()) {
          transitionToFailed();
          ready.completeExceptionally(exception);
          process.destroyForcibly();
        }
      }
    }
  }

  private synchronized void handleOutputLine(String line) {
    if (lifecycle.state() == InstanceState.STARTING
        && spec.readinessPattern().matcher(line).find()) {
      cancel(startupDeadline);
      lifecycle.transitionTo(InstanceState.READY);
      ready.complete(null);
    }
  }

  private synchronized void handleStartupTimeout() {
    if (lifecycle.state() != InstanceState.STARTING) {
      return;
    }

    TimeoutException exception =
        new TimeoutException(
            "Instance "
                + instanceId
                + " did not become ready within "
                + spec.startupTimeout().toSeconds()
                + " seconds");
    transitionToFailed();
    ready.completeExceptionally(exception);
    if (process != null && process.isAlive()) {
      process.destroyForcibly();
    }
  }

  private synchronized void forceStopAfterTimeout() {
    if (process != null && process.isAlive()) {
      outputConsumer.accept(
          "Instance "
              + instanceId
              + " did not stop within "
              + spec.stopTimeout().toSeconds()
              + " seconds; forcing termination");
      process.destroyForcibly();
    }
  }

  private synchronized void handleExit(int exitCode) {
    cancel(startupDeadline);
    cancel(stopDeadline);

    InstanceState state = lifecycle.state();
    if (state == InstanceState.STOPPING) {
      lifecycle.transitionTo(InstanceState.STOPPED);
    } else if (state == InstanceState.STARTING || state == InstanceState.READY) {
      transitionToFailed();
    }

    if (!ready.isDone()) {
      ready.completeExceptionally(
          new ProcessStartException(
              "Instance "
                  + instanceId
                  + " exited with code "
                  + exitCode
                  + " before becoming ready"));
    }
    owner.processExited(instanceId, this);
    exit.complete(exitCode);
  }

  private void transitionToFailed() {
    InstanceState state = lifecycle.state();
    if (state != InstanceState.FAILED && state != InstanceState.STOPPED) {
      lifecycle.transitionTo(InstanceState.FAILED);
    }
  }

  private static void cancel(ScheduledFuture<?> future) {
    if (future != null) {
      future.cancel(false);
    }
  }
}
