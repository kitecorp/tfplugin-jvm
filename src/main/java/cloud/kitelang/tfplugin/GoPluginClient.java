package cloud.kitelang.tfplugin;

import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.health.v1.HealthCheckRequest;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.health.v1.HealthGrpc;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import lombok.extern.slf4j.Slf4j;

import java.io.BufferedReader;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Client that manages a Terraform provider Go binary via the HashiCorp go-plugin protocol.
 *
 * <p>Lifecycle:
 * <ol>
 *   <li>Launch the provider binary as a subprocess with the magic cookie env var</li>
 *   <li>Read the handshake line from stdout: {@code CORE_PROTO|APP_PROTO|NETWORK_TYPE|NETWORK_ADDR|PROTOCOL}</li>
 *   <li>Establish a gRPC channel to the address from the handshake</li>
 *   <li>Verify health via gRPC Health Check (service {@code "plugin"})</li>
 * </ol>
 *
 * <p>On {@link #close()}: sends a {@code Stop} RPC, shuts down the gRPC channel,
 * and destroys the subprocess.</p>
 *
 * @see <a href="https://github.com/hashicorp/go-plugin/blob/main/docs/guide-plugin-write-non-go.md">go-plugin non-Go guide</a>
 */
@Slf4j
public class GoPluginClient implements AutoCloseable {

    /** Magic cookie key required by all Terraform providers. */
    static final String MAGIC_COOKIE_KEY = "TF_PLUGIN_MAGIC_COOKIE";

    /**
     * Magic cookie value for providers built with the terraform-plugin-framework.
     * This is the default value used since most modern providers have migrated.
     */
    static final String MAGIC_COOKIE_VALUE =
            "d602bf8f470bc67ca7faa0386276bbdd4330efaf76d1a219cb4d6991ca9872b2";

    /**
     * Legacy magic cookie value for providers built with the older terraform-plugin-sdk-v2.
     * Used as a fallback when the framework value does not match.
     */
    static final String LEGACY_MAGIC_COOKIE_VALUE = "d602bf8f470bc67ca7faa0945738d352";

    /** Default maximum time to wait for the handshake line from the provider process. */
    private static final Duration DEFAULT_HANDSHAKE_TIMEOUT = Duration.ofSeconds(30);

    /**
     * How long a fresh process is given to reveal an immediate cookie-mismatch exit
     * before it is treated as accepted — see {@link #launchWithFallback(Path)}.
     */
    private static final Duration COOKIE_PROBE_TIMEOUT = Duration.ofMillis(500);

    /** Grace period for the provider process to exit after a destroy signal. */
    private static final Duration PROCESS_EXIT_GRACE = Duration.ofSeconds(5);

    /** Expected core protocol version. */
    private static final int SUPPORTED_CORE_PROTOCOL = 1;

    /** Expected wire protocol. */
    private static final String SUPPORTED_PROTOCOL = "grpc";

    /** Number of fields in the handshake line. */
    private static final int HANDSHAKE_FIELD_COUNT = 5;

    private final Process process;
    private final Thread stderrPump;
    private final ManagedChannel channel;
    private final tfplugin5.ProviderGrpc.ProviderBlockingStub stub;
    private final TerraformProviderRpc rpc;
    private final HandshakeResult handshake;

    /**
     * Parsed go-plugin handshake fields.
     *
     * @param coreProtocol core protocol version (must be 1)
     * @param appProtocol  application protocol version (5 or 6)
     * @param networkType  network transport type ({@code "tcp"} or {@code "unix"})
     * @param networkAddr  address to connect to (e.g. {@code "127.0.0.1:54321"})
     * @param protocol     wire protocol (must be {@code "grpc"})
     */
    record HandshakeResult(int coreProtocol, int appProtocol, String networkType, String networkAddr, String protocol) {}

    /**
     * Launch a Terraform provider binary and establish a gRPC connection.
     *
     * <p>Tries the terraform-plugin-framework magic cookie first (modern providers),
     * then falls back to the legacy terraform-plugin-sdk-v2 value if the process
     * exits immediately (cookie mismatch).</p>
     *
     * @param providerBinaryPath path to the provider binary executable
     * @throws GoPluginException if the binary cannot be started, the handshake fails,
     *                           or the health check does not return SERVING
     */
    public GoPluginClient(Path providerBinaryPath) {
        this(providerBinaryPath, DEFAULT_HANDSHAKE_TIMEOUT, RpcDeadlines.fromEnvironment());
    }

    /**
     * Package-private seam: launches the binary, then wires the connection with the
     * given handshake timeout and per-call deadlines. Used by tests to drive a fast
     * handshake timeout.
     */
    GoPluginClient(Path providerBinaryPath, Duration handshakeTimeout, RpcDeadlines deadlines) {
        this(launchWithFallback(providerBinaryPath), handshakeTimeout, deadlines);
    }

    /**
     * Package-private seam: wires up an already-launched process. Any failure during
     * wiring (handshake timeout, unsupported protocol, unhealthy provider) reaps the
     * half-started process and stderr pump before rethrowing, so a failed startup
     * never leaks a subprocess or a thread (kitecorp/kite#28).
     */
    GoPluginClient(Process launchedProcess, Duration handshakeTimeout, RpcDeadlines deadlines) {
        this.process = launchedProcess;
        this.stderrPump = startStderrCapture(launchedProcess);

        ManagedChannel openedChannel = null;
        try {
            var handshakeLine = readHandshakeLine(launchedProcess, handshakeTimeout);
            this.handshake = parseHandshake(handshakeLine);
            log.info("Handshake successful: appProtocol={}, networkType={}, addr={}",
                    handshake.appProtocol(), handshake.networkType(), handshake.networkAddr());

            openedChannel = buildChannel(handshake, new RpcDeadlineInterceptor(deadlines));
            this.channel = openedChannel;
            this.stub = tfplugin5.ProviderGrpc.newBlockingStub(openedChannel);
            this.rpc = createRpc(handshake.appProtocol(), openedChannel);

            verifyHealth();
        } catch (RuntimeException | Error e) {
            reapFailedStart(launchedProcess, stderrPump, openedChannel);
            throw e;
        }
        log.info("Provider is healthy and ready");
    }

    /**
     * Returns the blocking stub for tfplugin5 RPCs.
     *
     * <p>Only valid when the handshake negotiated app protocol 5 — kept for
     * direct protocol-5 access (e.g. raw-RPC tests). Bridge code must use the
     * version-agnostic {@link #rpc()} facade instead.</p>
     *
     * @return the tfplugin5 provider blocking stub
     */
    public tfplugin5.ProviderGrpc.ProviderBlockingStub getStub() {
        return stub;
    }

    /**
     * Returns the version-agnostic RPC facade, selected from the app protocol
     * version the go-plugin handshake negotiated (tfplugin5 or tfplugin6).
     *
     * @return the protocol-appropriate {@link TerraformProviderRpc} implementation
     */
    public TerraformProviderRpc rpc() {
        return rpc;
    }

    /**
     * Checks whether the provider process is healthy via gRPC Health Check.
     *
     * @return {@code true} if the health check returns SERVING, {@code false} otherwise
     */
    public boolean isHealthy() {
        try {
            var healthStub = HealthGrpc.newBlockingStub(channel);
            var response = healthStub.check(
                    HealthCheckRequest.newBuilder().setService("plugin").build());
            return response.getStatus() == ServingStatus.SERVING;
        } catch (Exception e) {
            log.warn("Health check failed: {}", e.getMessage());
            return false;
        }
    }

    /**
     * Returns the application protocol version from the handshake (5 or 6).
     *
     * @return the app protocol version
     */
    public int getAppProtocolVersion() {
        return handshake.appProtocol();
    }

    /**
     * Gracefully shuts down the provider: sends Stop RPC, closes the gRPC channel,
     * and destroys the subprocess.
     */
    @Override
    public void close() {
        log.info("Shutting down go-plugin provider");

        // 1. Send Stop RPC (best-effort; tfplugin5 Stop / tfplugin6 StopProvider)
        try {
            rpc.stop();
        } catch (Exception e) {
            log.warn("Stop RPC failed (process may have already exited): {}", e.getMessage());
        }

        // 2. Shutdown gRPC channel
        try {
            channel.shutdown();
            if (!channel.awaitTermination(5, TimeUnit.SECONDS)) {
                log.warn("gRPC channel did not terminate in time, forcing shutdown");
                channel.shutdownNow();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            channel.shutdownNow();
        }

        // 3. Destroy the process and let the stderr pump terminate (destroying the
        //    process closes its stderr, so the pump's readLine returns and exits).
        terminateProcess(process);
        joinStderrPump(stderrPump);

        log.info("Provider shutdown complete");
    }

    /**
     * Reaps a half-started provider after the constructor failed: shuts the channel
     * (if one was opened), destroys the process, and joins the stderr pump — so a
     * failed startup leaks neither the subprocess nor the pump thread (kitecorp/kite#28).
     */
    static void reapFailedStart(Process process, Thread stderrPump, ManagedChannel channel) {
        log.warn("Provider startup failed; reaping half-started process and threads");
        if (channel != null) {
            channel.shutdownNow();
        }
        terminateProcess(process);
        joinStderrPump(stderrPump);
    }

    /**
     * Destroys the provider process and waits for it, escalating to a forcible
     * destroy if it does not exit within the grace period. No-op if already dead.
     */
    static void terminateProcess(Process process) {
        if (!process.isAlive()) {
            return;
        }
        process.destroy();
        try {
            if (!process.waitFor(PROCESS_EXIT_GRACE.toMillis(), TimeUnit.MILLISECONDS)) {
                log.warn("Provider process did not exit gracefully, forcing destruction");
                process.destroyForcibly();
                process.waitFor(PROCESS_EXIT_GRACE.toMillis(), TimeUnit.MILLISECONDS);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
        }
    }

    /**
     * Joins the stderr pump thread briefly to confirm it terminated. The pump exits
     * on its own once the process dies (its {@code readLine} returns at stream EOF);
     * the join is a guard that it is not left blocked on a dead stream.
     */
    static void joinStderrPump(Thread stderrPump) {
        if (stderrPump == null) {
            return;
        }
        try {
            stderrPump.join(PROCESS_EXIT_GRACE);
            if (stderrPump.isAlive()) {
                log.warn("stderr pump thread did not terminate after process teardown");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    // ---------------------------------------------------------------
    // Internal / package-private methods (testable)
    // ---------------------------------------------------------------

    /**
     * Parses a go-plugin handshake line into its component fields.
     *
     * <p>Format: {@code CORE_PROTO|APP_PROTO|NETWORK_TYPE|NETWORK_ADDR|PROTOCOL}
     * <br>Example: {@code 1|5|tcp|127.0.0.1:54321|grpc}</p>
     *
     * @param line the raw handshake line from the provider's stdout
     * @return the parsed handshake result
     * @throws GoPluginException if the line is null, empty, malformed, or contains unsupported values
     */
    static HandshakeResult parseHandshake(String line) {
        if (line == null || line.isBlank()) {
            throw new GoPluginException("Handshake line is null or empty");
        }

        var trimmed = line.trim();
        var parts = trimmed.split("\\|");

        if (parts.length != HANDSHAKE_FIELD_COUNT) {
            throw new GoPluginException(
                    "Invalid handshake format: expected %d fields separated by '|', got %d in line: %s"
                            .formatted(HANDSHAKE_FIELD_COUNT, parts.length, trimmed));
        }

        int coreProtocol;
        try {
            coreProtocol = Integer.parseInt(parts[0]);
        } catch (NumberFormatException e) {
            throw new GoPluginException("Invalid core protocol version (not a number): " + parts[0], e);
        }

        if (coreProtocol != SUPPORTED_CORE_PROTOCOL) {
            throw new GoPluginException(
                    "Unsupported core protocol version: %d (expected %d)"
                            .formatted(coreProtocol, SUPPORTED_CORE_PROTOCOL));
        }

        int appProtocol;
        try {
            appProtocol = Integer.parseInt(parts[1]);
        } catch (NumberFormatException e) {
            throw new GoPluginException("Invalid app protocol version (not a number): " + parts[1], e);
        }

        var networkType = parts[2];
        var networkAddr = parts[3];
        var protocol = parts[4];

        if (!SUPPORTED_PROTOCOL.equals(protocol)) {
            throw new GoPluginException(
                    "Unsupported protocol: '%s' (only grpc is supported)".formatted(protocol));
        }

        return new HandshakeResult(coreProtocol, appProtocol, networkType, networkAddr, protocol);
    }

    /**
     * Builds the environment variable map using the default (framework) magic cookie.
     *
     * @return an unmodifiable map containing the required go-plugin environment entries
     */
    static Map<String, String> buildEnvironment() {
        return buildEnvironment(MAGIC_COOKIE_VALUE);
    }

    /**
     * Builds the environment variable map for the provider subprocess.
     *
     * <p>Includes the magic cookie and protocol version negotiation env vars required
     * by the go-plugin framework. Without {@code PLUGIN_PROTOCOL_VERSIONS}, newer
     * providers reject the handshake with "This binary is a plugin" and exit.</p>
     *
     * <p>Forces TCP transport by unsetting {@code PLUGIN_UNIX_SOCKET_DIR}. Without this,
     * newer go-plugin versions default to Unix domain sockets on Unix-like systems,
     * which requires platform-specific native transport (kqueue on macOS, epoll on Linux).</p>
     *
     * @param cookieValue the magic cookie value to use (framework or legacy)
     * @return a mutable map containing the required go-plugin environment entries
     */
    static Map<String, String> buildEnvironment(String cookieValue) {
        // Use a mutable map because ProcessBuilder.environment().putAll() needs it
        // and we need to be able to set empty values to suppress Unix socket mode
        var env = new java.util.HashMap<String, String>();
        env.put(MAGIC_COOKIE_KEY, cookieValue);
        env.put("PLUGIN_PROTOCOL_VERSIONS", "5,6");
        return java.util.Collections.unmodifiableMap(env);
    }

    /**
     * Selects the protocol-appropriate RPC implementation for the negotiated
     * app protocol version. {@code PLUGIN_PROTOCOL_VERSIONS=5,6} is offered in
     * the handshake, so the provider only ever answers with 5 or 6; anything
     * else indicates a protocol the bridge cannot speak.
     *
     * @param appProtocol the app protocol version from the handshake
     * @param channel     the established gRPC channel
     * @return the matching {@link TerraformProviderRpc} implementation
     * @throws GoPluginException if the version is neither 5 nor 6
     */
    static TerraformProviderRpc createRpc(int appProtocol, io.grpc.Channel channel) {
        return switch (appProtocol) {
            case 5 -> new Tfplugin5Rpc(tfplugin5.ProviderGrpc.newBlockingStub(channel));
            case 6 -> new Tfplugin6Rpc(tfplugin6.ProviderGrpc.newBlockingStub(channel));
            default -> throw new GoPluginException(
                    "Unsupported app protocol version: %d (supported: 5 and 6)".formatted(appProtocol));
        };
    }

    /**
     * Constructs the gRPC channel target string from the handshake result.
     *
     * <p>For TCP, returns the address as-is. For Unix sockets, prepends {@code "unix:"}.</p>
     *
     * @param handshake the parsed handshake result
     * @return the gRPC target string
     */
    static String buildGrpcTarget(HandshakeResult handshake) {
        return switch (handshake.networkType()) {
            case "unix" -> "unix:" + handshake.networkAddr();
            default -> handshake.networkAddr();
        };
    }

    /**
     * Builds a gRPC ManagedChannel from the handshake result.
     *
     * <p>For TCP connections, uses the standard {@link ManagedChannelBuilder}.
     * For Unix domain sockets, uses Netty's {@link NettyChannelBuilder} with
     * {@link java.net.UnixDomainSocketAddress} (Java 16+).</p>
     *
     * <p>The {@code deadlineInterceptor} is attached to the channel, so every stub
     * built from it (protocol facade, raw stub, health stub) carries a per-call
     * deadline (kitecorp/kite#33).</p>
     *
     * @param handshake           the parsed handshake result
     * @param deadlineInterceptor the per-call deadline interceptor
     * @return a configured ManagedChannel ready for RPC calls
     */
    private static ManagedChannel buildChannel(HandshakeResult handshake, RpcDeadlineInterceptor deadlineInterceptor) {
        return switch (handshake.networkType()) {
            case "unix" -> {
                var socketAddress = java.net.UnixDomainSocketAddress.of(handshake.networkAddr());
                var eventLoopGroup = new io.grpc.netty.shaded.io.netty.channel.nio.NioEventLoopGroup();
                yield NettyChannelBuilder
                        .forAddress(socketAddress)
                        .eventLoopGroup(eventLoopGroup)
                        .channelType(io.grpc.netty.shaded.io.netty.channel.socket.nio.NioDomainSocketChannel.class)
                        .intercept(deadlineInterceptor)
                        .usePlaintext()
                        .build();
            }
            default -> ManagedChannelBuilder
                    .forTarget(handshake.networkAddr())
                    .intercept(deadlineInterceptor)
                    .usePlaintext()
                    .build();
        };
    }

    // ---------------------------------------------------------------
    // Private helpers
    // ---------------------------------------------------------------

    /**
     * Attempts to launch the provider with the framework magic cookie value.
     * If the process exits immediately (cookie mismatch), retries with the
     * legacy SDK cookie value.
     */
    private static Process launchWithFallback(Path binaryPath) {
        var process = launchProcess(binaryPath, MAGIC_COOKIE_VALUE);

        // Detect an immediate cookie-mismatch exit: waitFor returns the instant the
        // process dies (so a rejected cookie is retried without delay) and otherwise
        // caps the wait, unlike a fixed sleep that always burned the full interval.
        try {
            if (!process.waitFor(COOKIE_PROBE_TIMEOUT.toMillis(), TimeUnit.MILLISECONDS)) {
                return process; // still alive -> cookie accepted
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            process.destroyForcibly();
            throw new GoPluginException("Interrupted while waiting for provider startup", e);
        }

        log.info("Framework cookie rejected (exit {}), retrying with legacy SDK cookie",
                process.exitValue());
        return launchProcess(binaryPath, LEGACY_MAGIC_COOKIE_VALUE);
    }

    /**
     * Launches the provider binary as a subprocess with the specified magic cookie value.
     */
    private static Process launchProcess(Path binaryPath, String cookieValue) {
        var builder = new ProcessBuilder(binaryPath.toAbsolutePath().toString());
        builder.environment().putAll(buildEnvironment(cookieValue));
        builder.redirectErrorStream(false);

        try {
            return builder.start();
        } catch (IOException e) {
            throw new GoPluginException(
                    "Failed to launch provider binary: " + binaryPath + " — " + e.getMessage(), e);
        }
    }

    /**
     * Reads a single handshake line from the provider process stdout, bounded by
     * {@code timeout}.
     *
     * <p>{@code readLine()} has no timeout, so it runs on a virtual thread and the
     * caller waits on the future. On timeout the reader is stuck in a native read
     * that ignores interruption, so the stdout stream is closed to force it to
     * return — otherwise the reader thread (and the per-task executor) would leak,
     * holding the process's stdout open forever (kitecorp/kite#28).</p>
     */
    private static String readHandshakeLine(Process process, Duration timeout) {
        var reader = new BufferedReader(new InputStreamReader(process.getInputStream()));
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        try {
            var future = CompletableFuture.supplyAsync(() -> {
                try {
                    return reader.readLine();
                } catch (IOException e) {
                    throw new GoPluginException("Failed to read handshake line from provider stdout", e);
                }
            }, executor);

            try {
                var line = future.get(timeout.toMillis(), TimeUnit.MILLISECONDS);
                if (line == null) {
                    var stderr = captureStderr(process);
                    throw new GoPluginException(
                            "Provider process exited before sending handshake line. Stderr: " + stderr);
                }
                return line;
            } catch (TimeoutException e) {
                // Unblock the reader so its virtual thread (and the executor) can
                // terminate; the process itself is reaped by the constructor's
                // failure handler.
                closeQuietly(process.getInputStream());
                future.cancel(true);
                throw new GoPluginException(
                        "Handshake timeout: provider did not send handshake within %d ms"
                                .formatted(timeout.toMillis()));
            }
        } catch (GoPluginException e) {
            throw e;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new GoPluginException("Interrupted while reading handshake line", e);
        } catch (ExecutionException e) {
            var cause = e.getCause();
            if (cause instanceof GoPluginException goPluginException) {
                throw goPluginException;
            }
            throw new GoPluginException("Failed to read handshake line: " + e.getMessage(), e);
        } finally {
            // The reader task has finished (success) or been unblocked (timeout, via
            // the stream close above), so this does not block; without it the
            // per-task executor leaks (kitecorp/kite#28).
            executor.shutdownNow();
        }
    }

    /**
     * Starts a background virtual thread that drains stderr and logs it, returning
     * the thread so the lifecycle can confirm it terminates on teardown.
     */
    static Thread startStderrCapture(Process process) {
        return Thread.ofVirtual().name("go-plugin-stderr").start(() -> {
            try (var reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    log.debug("[provider stderr] {}", line);
                }
            } catch (IOException e) {
                log.warn("Error reading provider stderr: {}", e.getMessage());
            }
        });
    }

    /** Closes a stream, swallowing any {@link IOException} — best-effort unblock. */
    private static void closeQuietly(Closeable closeable) {
        try {
            closeable.close();
        } catch (IOException ignored) {
            // Best-effort: closing only serves to unblock the stuck reader.
        }
    }

    /**
     * Captures available stderr content from the provider process (best-effort).
     */
    private static String captureStderr(Process process) {
        try (var reader = new BufferedReader(new InputStreamReader(process.getErrorStream()))) {
            var sb = new StringBuilder();
            String line;
            while (reader.ready() && (line = reader.readLine()) != null) {
                sb.append(line).append('\n');
            }
            return sb.isEmpty() ? "<no stderr output>" : sb.toString().trim();
        } catch (IOException e) {
            return "<failed to read stderr: " + e.getMessage() + ">";
        }
    }

    /**
     * Verifies that the provider is healthy via gRPC Health Check.
     */
    private void verifyHealth() {
        try {
            var healthStub = HealthGrpc.newBlockingStub(channel);
            var response = healthStub.check(
                    HealthCheckRequest.newBuilder().setService("plugin").build());

            if (response.getStatus() != ServingStatus.SERVING) {
                throw new GoPluginException(
                        "Provider health check returned status: " + response.getStatus());
            }
        } catch (GoPluginException e) {
            throw e;
        } catch (Exception e) {
            throw new GoPluginException("Provider health check failed: " + e.getMessage(), e);
        }
    }
}
