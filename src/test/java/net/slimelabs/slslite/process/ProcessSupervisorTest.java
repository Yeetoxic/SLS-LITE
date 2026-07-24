package net.slimelabs.slslite.process;

import net.slimelabs.slslite.instance.InstanceLifecycle;
import net.slimelabs.slslite.instance.InstanceState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.regex.Pattern;

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
