package cloud.kitelang.tfplugin;

import io.grpc.CallOptions;
import io.grpc.Channel;
import io.grpc.ClientCall;
import io.grpc.ClientInterceptor;
import io.grpc.Deadline;
import io.grpc.MethodDescriptor;

import java.util.Set;
import java.util.concurrent.TimeUnit;

/**
 * Attaches a per-call gRPC deadline to every Terraform provider RPC so a wedged
 * provider process cannot block a bridge thread forever (kitecorp/kite#33).
 *
 * <p>Installed on the {@link GoPluginClient} channel, so <em>every</em> stub built
 * from that channel (the protocol facade, the raw tfplugin5 stub, and the gRPC
 * health stub) inherits the deadline — no per-call-site code, and the near-identical
 * {@link Tfplugin5Rpc}/{@link Tfplugin6Rpc} adapters stay untouched.</p>
 *
 * <p>The deadline tier is chosen from the method name. Only the known pure-compute
 * control-plane RPCs get the short {@link RpcDeadlines#controlPlane()} deadline;
 * <b>everything else defaults to the generous {@link RpcDeadlines#resourceOperation()}
 * deadline</b>. This bias is deliberate: a too-aggressive deadline on a legitimately
 * long cloud call is a worse bug than no deadline, so any resource-lifecycle RPC —
 * or any future cloud-touching RPC the bridge has not catalogued — is protected by
 * the generous tier rather than the short one.</p>
 */
final class RpcDeadlineInterceptor implements ClientInterceptor {

    /**
     * Bare method names that are pure local computation inside the provider and so
     * take the short control-plane deadline. Covers both protocol dialects
     * (tfplugin5 / tfplugin6 name several of these differently) plus the gRPC
     * health {@code Check} used during startup, so a hung health probe is bounded
     * quickly instead of by the 6h resource default. Anything not listed here falls
     * through to the generous resource-operation deadline.
     */
    private static final Set<String> CONTROL_PLANE_METHODS = Set.of(
            "GetMetadata",
            "GetSchema", "GetProviderSchema",
            "PrepareProviderConfig", "ValidateProviderConfig",
            "ValidateResourceTypeConfig", "ValidateResourceConfig",
            "ValidateDataSourceConfig", "ValidateDataResourceConfig",
            "UpgradeResourceState",
            "Configure", "ConfigureProvider",
            "Stop", "StopProvider",
            "Check");

    private final RpcDeadlines deadlines;

    RpcDeadlineInterceptor(RpcDeadlines deadlines) {
        this.deadlines = deadlines;
    }

    @Override
    public <ReqT, RespT> ClientCall<ReqT, RespT> interceptCall(
            MethodDescriptor<ReqT, RespT> method, CallOptions callOptions, Channel next) {
        // Respect a deadline a caller already set (defensive; the bridge never does),
        // so an explicit override is never silently shortened.
        if (callOptions.getDeadline() == null) {
            callOptions = callOptions.withDeadline(deadlineFor(method.getBareMethodName()));
        }
        return next.newCall(method, callOptions);
    }

    private Deadline deadlineFor(String bareMethodName) {
        var duration = CONTROL_PLANE_METHODS.contains(bareMethodName)
                ? deadlines.controlPlane()
                : deadlines.resourceOperation();
        return Deadline.after(duration.toNanos(), TimeUnit.NANOSECONDS);
    }
}
