package cloud.kitelang.tfplugin;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.Deadline;
import io.grpc.ManagedChannel;
import io.grpc.MethodDescriptor;
import io.grpc.Server;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.inprocess.InProcessChannelBuilder;
import io.grpc.inprocess.InProcessServerBuilder;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import tfplugin5.ProviderGrpc;
import tfplugin5.Tfplugin5.ApplyResourceChange;
import tfplugin5.Tfplugin5.GetProviderSchema;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * TDD tests for {@link RpcDeadlineInterceptor} (kitecorp/kite#33).
 *
 * <p>Two layers: deterministic assertions on which deadline the interceptor
 * <em>selects</em> per method, and an end-to-end proof that a hung call against a
 * never-responding provider is actually bounded by that deadline (rather than
 * hanging forever) — driven over a real in-process gRPC transport.</p>
 */
class RpcDeadlineInterceptorTest {

    private Server server;
    private ManagedChannel channel;

    @AfterEach
    void tearDown() {
        if (channel != null) {
            channel.shutdownNow();
        }
        if (server != null) {
            server.shutdownNow();
        }
    }

    // ---------------------------------------------------------------
    // Deterministic deadline selection (no timing dependence)
    // ---------------------------------------------------------------

    @Test
    @DisplayName("should apply the resource-operation deadline to ApplyResourceChange")
    void shouldApplyResourceDeadlineToApply() {
        var captured = interceptedOptions(
                new RpcDeadlines(Duration.ofSeconds(60), Duration.ofHours(6)),
                ProviderGrpc.getApplyResourceChangeMethod(),
                CallOptions.DEFAULT);

        var remainingSeconds = captured.getDeadline().timeRemaining(TimeUnit.SECONDS);
        assertTrue(remainingSeconds > TimeUnit.HOURS.toSeconds(1),
                "ApplyResourceChange should get the 6h resource deadline, remaining was " + remainingSeconds + "s");
    }

    @Test
    @DisplayName("should apply the short control-plane deadline to GetSchema")
    void shouldApplyControlPlaneDeadlineToGetSchema() {
        var captured = interceptedOptions(
                new RpcDeadlines(Duration.ofSeconds(60), Duration.ofHours(6)),
                ProviderGrpc.getGetSchemaMethod(),
                CallOptions.DEFAULT);

        var remainingSeconds = captured.getDeadline().timeRemaining(TimeUnit.SECONDS);
        assertTrue(remainingSeconds > 30 && remainingSeconds <= 60,
                "GetSchema should get the 60s control-plane deadline, remaining was " + remainingSeconds + "s");
    }

    @Test
    @DisplayName("should not override a deadline the caller already set")
    void shouldNotOverrideExistingDeadline() {
        var existing = Deadline.after(7, TimeUnit.SECONDS);

        var captured = interceptedOptions(
                RpcDeadlines.defaults(),
                ProviderGrpc.getApplyResourceChangeMethod(),
                CallOptions.DEFAULT.withDeadline(existing));

        assertSame(existing, captured.getDeadline());
    }

    // ---------------------------------------------------------------
    // End-to-end: a hung call is bounded, not infinite
    // ---------------------------------------------------------------

    @Test
    @Timeout(15)
    @DisplayName("a hung resource-operation call should fail with DEADLINE_EXCEEDED at the resource deadline")
    void hungResourceCallShouldBeBoundedByResourceDeadline() throws IOException {
        startNeverRespondingProvider(new RpcDeadlines(Duration.ofMillis(300), Duration.ofMillis(1200)));
        var stub = ProviderGrpc.newBlockingStub(channel);

        var start = System.nanoTime();
        var exception = assertThrows(StatusRuntimeException.class,
                () -> stub.applyResourceChange(ApplyResourceChange.Request.getDefaultInstance()));
        var elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(Status.Code.DEADLINE_EXCEEDED, exception.getStatus().getCode());
        // Bounded by the ~1200ms resource tier, not the 300ms control tier.
        assertTrue(elapsedMs >= 900,
                "ApplyResourceChange should use the longer resource deadline, elapsed was " + elapsedMs + "ms");
    }

    @Test
    @Timeout(15)
    @DisplayName("a hung control-plane call should fail with DEADLINE_EXCEEDED at the shorter deadline")
    void hungControlPlaneCallShouldBeBoundedByShorterDeadline() throws IOException {
        startNeverRespondingProvider(new RpcDeadlines(Duration.ofMillis(300), Duration.ofMillis(1200)));
        var stub = ProviderGrpc.newBlockingStub(channel);

        var start = System.nanoTime();
        var exception = assertThrows(StatusRuntimeException.class,
                () -> stub.getSchema(GetProviderSchema.Request.getDefaultInstance()));
        var elapsedMs = (System.nanoTime() - start) / 1_000_000;

        assertEquals(Status.Code.DEADLINE_EXCEEDED, exception.getStatus().getCode());
        // Cut off near the 300ms control tier, well below the 1200ms resource tier.
        assertTrue(elapsedMs < 900,
                "GetSchema should use the short control-plane deadline, elapsed was " + elapsedMs + "ms");
    }

    // ---------------------------------------------------------------
    // Helpers
    // ---------------------------------------------------------------

    /** Runs the interceptor over a capturing channel and returns the call options it produced. */
    private static CallOptions interceptedOptions(RpcDeadlines deadlines,
                                                  MethodDescriptor<?, ?> method,
                                                  CallOptions callOptions) {
        var captured = new AtomicReference<CallOptions>();
        var capturingChannel = new Channel() {
            @Override
            public <ReqT, RespT> ClientCall<ReqT, RespT> newCall(
                    MethodDescriptor<ReqT, RespT> methodDescriptor, CallOptions options) {
                captured.set(options);
                return null;
            }

            @Override
            public String authority() {
                return "test-authority";
            }
        };

        new RpcDeadlineInterceptor(deadlines).interceptCall(cast(method), callOptions, capturingChannel);
        return captured.get();
    }

    @SuppressWarnings("unchecked")
    private static MethodDescriptor<Object, Object> cast(MethodDescriptor<?, ?> method) {
        return (MethodDescriptor<Object, Object>) method;
    }

    private void startNeverRespondingProvider(RpcDeadlines deadlines) throws IOException {
        var serverName = InProcessServerBuilder.generateName();
        server = InProcessServerBuilder.forName(serverName)
                .addService(new NeverRespondingProvider())
                .build()
                .start();
        channel = InProcessChannelBuilder.forName(serverName)
                .intercept(new RpcDeadlineInterceptor(deadlines))
                .build();
    }

    /**
     * A provider that accepts calls but never completes the response observer, so
     * every RPC hangs until the client-side deadline fires — the wedged-provider
     * condition kitecorp/kite#33 guards against.
     */
    private static final class NeverRespondingProvider extends ProviderGrpc.ProviderImplBase {
        @Override
        public void getSchema(GetProviderSchema.Request request,
                              StreamObserver<GetProviderSchema.Response> responseObserver) {
            // never responds
        }

        @Override
        public void applyResourceChange(ApplyResourceChange.Request request,
                                        StreamObserver<ApplyResourceChange.Response> responseObserver) {
            // never responds
        }
    }
}
