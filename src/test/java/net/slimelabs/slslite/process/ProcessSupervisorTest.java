package net.slimelabs.slslite.process;

import net.slimelabs.slslite.instance.InstanceLifecycle;
import net.slimelabs.slslite.instance.InstanceState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ProcessSupervisorTest {

    @TempDir
    Path temporaryDirectory;

    private ProcessSupervisor supervisor;

    @AfterEach
    void stopSupervisor() {
        if (supervisor != null) {
            supervisor.shutdown(Duration.ofSeconds(3));
        }
    }

    @Test
    void detectsReadinessAndStopsGracefully() throws Exception {
        supervisor = new ProcessSupervisor(2);
        InstanceLifecycle lifecycle = preparingLifecycle("test-ready");
        List<String> output = new CopyOnWriteArrayList<>();

        SupervisedProcess process = supervisor.start(
                "test-ready",
                spec("ready-stop", Duration.ofSeconds(5), Duration.ofSeconds(2)),
                lifecycle,
                output::add
        );

        process.readyFuture().get(5, TimeUnit.SECONDS);
        assertEquals(InstanceState.READY, process.state());
        assertTrue(output.contains("FIXTURE READY"));
        assertEquals(0, process.stop().get(5, TimeUnit.SECONDS));
        assertEquals(InstanceState.STOPPED, process.state());
    }

    @Test
    void passesConfiguredEnvironmentToChildProcess() throws Exception {
        supervisor = new ProcessSupervisor(1);
        InstanceLifecycle lifecycle = preparingLifecycle("test-environment");
        List<String> output = new CopyOnWriteArrayList<>();
        ProcessSpec base = spec(
                "environment",
                Duration.ofSeconds(5),
                Duration.ofSeconds(2)
        );
        ProcessSpec configured = new ProcessSpec(
                base.command(),
                base.workingDirectory(),
                base.readinessPattern(),
                base.startupTimeout(),
                base.stopCommand(),
                base.stopTimeout(),
                Map.of("SLS_LITE_TEST_VALUE", "propagated")
        );

        SupervisedProcess process = supervisor.start(
                "test-environment",
                configured,
                lifecycle,
                output::add
        );

        process.readyFuture().get(5, TimeUnit.SECONDS);
        assertTrue(output.contains("ENV:propagated"));
        assertEquals(0, process.exitFuture().get(5, TimeUnit.SECONDS));
    }

    @Test
    void cancelsStartupWithoutWaitingForTheGracefulStopDeadline() throws Exception {
        supervisor = new ProcessSupervisor(1);
        InstanceLifecycle lifecycle = preparingLifecycle("test-cancel");
        SupervisedProcess process = supervisor.start(
                "test-cancel",
                spec("silent", Duration.ofSeconds(30), Duration.ofSeconds(30)),
                lifecycle,
                line -> {
                }
        );

        long started = System.nanoTime();
        int exitCode = process.cancelStartup().get(5, TimeUnit.SECONDS);

        assertTrue(exitCode != 0);
        assertTrue(System.nanoTime() - started < TimeUnit.SECONDS.toNanos(5));
        assertThrows(CancellationException.class, process.readyFuture()::join);
        assertEquals(InstanceState.STOPPED, process.state());
    }

    @Test
    void writesOneConsoleCommandToAReadyProcess() throws Exception {
        supervisor = new ProcessSupervisor(1);
        InstanceLifecycle lifecycle = preparingLifecycle("test-command");
        List<String> output = new CopyOnWriteArrayList<>();
        SupervisedProcess process = supervisor.start(
                "test-command",
                spec("ready-stop", Duration.ofSeconds(5), Duration.ofSeconds(2)),
                lifecycle,
                output::add
        );
        process.readyFuture().get(5, TimeUnit.SECONDS);

        process.sendCommand("say hello world");
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (!output.contains("RECEIVED:say hello world")
                && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }

        assertTrue(output.contains("RECEIVED:say hello world"));
        assertThrows(IllegalArgumentException.class, () -> process.sendCommand("one\ntwo"));
        process.stop().get(5, TimeUnit.SECONDS);
    }

    @Test
    void failsAndTerminatesWhenReadinessTimesOut() throws Exception {
        supervisor = new ProcessSupervisor(1);
        InstanceLifecycle lifecycle = preparingLifecycle("test-timeout");

        SupervisedProcess process = supervisor.start(
                "test-timeout",
                spec("silent", Duration.ofMillis(250), Duration.ofSeconds(1)),
                lifecycle,
                line -> {
                }
        );

        assertThrows(
                ExecutionException.class,
                () -> process.readyFuture().get(5, TimeUnit.SECONDS)
        );
        process.exitFuture().get(5, TimeUnit.SECONDS);
        assertEquals(InstanceState.FAILED, process.state());
    }

    @Test
    void reportsCrashExitCodeBeforeReadiness() throws Exception {
        supervisor = new ProcessSupervisor(1);
        InstanceLifecycle lifecycle = preparingLifecycle("test-crash");

        SupervisedProcess process = supervisor.start(
                "test-crash",
                spec("crash", Duration.ofSeconds(5), Duration.ofSeconds(1)),
                lifecycle,
                line -> {
                }
        );

        assertEquals(7, process.exitFuture().get(5, TimeUnit.SECONDS));
        assertThrows(ExecutionException.class, process.readyFuture()::get);
        assertEquals(InstanceState.FAILED, process.state());
    }

    @Test
    void forceTerminatesProcessAfterStopTimeout() throws Exception {
        supervisor = new ProcessSupervisor(1);
        InstanceLifecycle lifecycle = preparingLifecycle("test-force");

        SupervisedProcess process = supervisor.start(
                "test-force",
                spec("ignore-stop", Duration.ofSeconds(5), Duration.ofMillis(200)),
                lifecycle,
                line -> {
                }
        );

        process.readyFuture().get(5, TimeUnit.SECONDS);
        int exitCode = process.stop().get(5, TimeUnit.SECONDS);

        assertTrue(exitCode != 0);
        assertEquals(InstanceState.STOPPED, process.state());
    }

    @Test
    void rejectsDuplicateActiveInstanceId() throws Exception {
        supervisor = new ProcessSupervisor(2);
        SupervisedProcess first = supervisor.start(
                "duplicate",
                spec("ready-stop", Duration.ofSeconds(5), Duration.ofSeconds(1)),
                preparingLifecycle("duplicate"),
                line -> {
                }
        );
        first.readyFuture().get(5, TimeUnit.SECONDS);

        assertThrows(
                ProcessStartException.class,
                () -> supervisor.start(
                        "duplicate",
                        spec("ready-stop", Duration.ofSeconds(5), Duration.ofSeconds(1)),
                        preparingLifecycle("duplicate"),
                        line -> {
                        }
                )
        );
    }

    @Test
    void releasesIdleOutputThreadsAfterProcessesExit() throws Exception {
        supervisor = new ProcessSupervisor(2, Duration.ofMillis(100));
        SupervisedProcess process = supervisor.start(
                "test-output-thread-retirement",
                spec("ready-stop", Duration.ofSeconds(5), Duration.ofSeconds(1)),
                preparingLifecycle("test-output-thread-retirement"),
                line -> {
                }
        );
        process.readyFuture().get(5, TimeUnit.SECONDS);
        assertTrue(supervisor.outputWorkerCount() > 0);

        process.stop().get(5, TimeUnit.SECONDS);
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
        while (supervisor.outputWorkerCount() != 0 && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }

        assertEquals(0, supervisor.outputWorkerCount());
    }

    @Test
    void queuesReplacementReaderDuringOutputThreadHandoff() throws Exception {
        supervisor = new ProcessSupervisor(1, Duration.ofSeconds(1));
        CountDownLatch firstStarted = new CountDownLatch(1);
        CountDownLatch releaseFirst = new CountDownLatch(1);
        CountDownLatch replacementCompleted = new CountDownLatch(1);
        supervisor.executeOutputReader(() -> {
            firstStarted.countDown();
            try {
                releaseFirst.await();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
        });
        assertTrue(firstStarted.await(1, TimeUnit.SECONDS));

        assertDoesNotThrow(() -> supervisor.executeOutputReader(
                replacementCompleted::countDown
        ));
        releaseFirst.countDown();

        assertTrue(replacementCompleted.await(1, TimeUnit.SECONDS));
    }

    private ProcessSpec spec(String mode, Duration startupTimeout, Duration stopTimeout) {
        String executable = Path.of(
                System.getProperty("java.home"),
                "bin",
                isWindows() ? "java.exe" : "java"
        ).toString();
        return new ProcessSpec(
                List.of(
                        executable,
                        "-cp",
                        System.getProperty("java.class.path"),
                        FixtureProcessMain.class.getName(),
                        mode
                ),
                temporaryDirectory,
                Pattern.compile("FIXTURE READY"),
                startupTimeout,
                "stop",
                stopTimeout
        );
    }

    private static InstanceLifecycle preparingLifecycle(String instanceId) {
        InstanceLifecycle lifecycle = new InstanceLifecycle(instanceId);
        lifecycle.transitionTo(InstanceState.PREPARING);
        return lifecycle;
    }

    private static boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT)
                .contains("windows");
    }
}
