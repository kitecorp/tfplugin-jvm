package cloud.kitelang.tfplugin;

import io.grpc.ManagedChannel;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TDD tests for the observable {@link GoPluginClient} lifecycle (kitecorp/kite#28):
 * the stderr pump thread and the provider subprocess must be torn down cleanly on
 * teardown and on a <em>failed</em> startup, never left leaked.
 *
 * <p>A real provider binary is not available in a unit test, so these use a
 * long-lived {@code cat} subprocess — it blocks on stdin (stays alive), never
 * writes stdout (so the handshake read hangs) and never writes stderr (so the pump
 * blocks) — which reproduces exactly the "process alive but not handshaking" and
 * "pump blocked on a live stream" conditions the teardown code must handle.</p>
 */
@Timeout(30)
class GoPluginClientLifecycleTest {

    private static final Path CAT = Path.of("/bin/cat");

    @Test
    @DisplayName("stderr pump should terminate once the process dies (not stay blocked on a dead stream)")
    void stderrPumpShouldTerminateWhenProcessDies() throws Exception {
        var process = startCat();
        var pump = GoPluginClient.startStderrCapture(process);
        assertTrue(pump.isAlive(), "pump should be blocked reading the live stderr stream");

        process.destroy();
        assertTrue(process.waitFor(5, TimeUnit.SECONDS), "cat should exit on destroy");

        pump.join(Duration.ofSeconds(2));
        assertFalse(pump.isAlive(), "pump must exit at stream EOF after the process dies");
    }

    @Test
    @DisplayName("reapFailedStart should destroy the process and join the stderr pump")
    void reapFailedStartShouldDestroyProcessAndJoinPump() throws Exception {
        var process = startCat();
        var pump = GoPluginClient.startStderrCapture(process);

        GoPluginClient.reapFailedStart(process, pump, (ManagedChannel) null);

        assertFalse(process.isAlive(), "process must be destroyed");
        assertFalse(pump.isAlive(), "stderr pump must have terminated");
    }

    @Test
    @DisplayName("terminateProcess should force-kill and be a no-op on an already-dead process")
    void terminateProcessShouldKillAndBeIdempotent() throws Exception {
        var process = startCat();

        GoPluginClient.terminateProcess(process);
        assertFalse(process.isAlive(), "process must be dead after terminateProcess");

        // Idempotent: a second call on a dead process must not throw.
        GoPluginClient.terminateProcess(process);
        assertFalse(process.isAlive());
    }

    @Test
    @DisplayName("constructor should reap the subprocess when startup fails on a hung handshake")
    void constructorShouldReapProcessOnFailedStartup() throws Exception {
        var process = startCat();

        // cat never emits a handshake line, so wiring fails at the (short) handshake timeout.
        assertThrows(GoPluginException.class,
                () -> new GoPluginClient(process, Duration.ofMillis(500), RpcDeadlines.defaults()));

        assertFalse(process.isAlive(), "a failed startup must not leak the half-started subprocess");
    }

    private static Process startCat() throws IOException {
        assertTrue(Files.isExecutable(CAT), "test requires an executable " + CAT);
        return new ProcessBuilder(CAT.toString()).redirectErrorStream(false).start();
    }
}
