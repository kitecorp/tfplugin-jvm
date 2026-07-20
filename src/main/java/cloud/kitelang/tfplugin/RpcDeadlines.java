package cloud.kitelang.tfplugin;

import java.time.Duration;
import java.util.function.Function;

/**
 * Per-call gRPC deadlines applied to every Terraform provider RPC by
 * {@link RpcDeadlineInterceptor}, so a wedged provider process can never block a
 * bridge thread forever (kitecorp/kite#33).
 *
 * <p>Two tiers, because provider operations have wildly different legitimate
 * durations:</p>
 *
 * <ul>
 *   <li><b>{@link #controlPlane()}</b> — schema fetch, config validation,
 *       {@code Configure}, {@code UpgradeResourceState}, {@code Stop} and the
 *       gRPC health check. These are pure local computation inside the provider
 *       (schema marshalling, config checks, JSON state re-encoding, credential
 *       setup) and never legitimately run for a minute; a short deadline surfaces
 *       a hung provider quickly (e.g. a startup schema fetch that never returns).</li>
 *   <li><b>{@link #resourceOperation()}</b> — {@code PlanResourceChange},
 *       {@code ApplyResourceChange}, {@code ReadResource},
 *       {@code ImportResourceState} and {@code ReadDataSource}: the only RPCs
 *       that call cloud APIs and can legitimately run for many minutes. This is a
 *       generous <em>safety net</em>, deliberately set far above any provider's own
 *       operation timeout — see below.</li>
 * </ul>
 *
 * <h2>Why the resource deadline must be generous (and never enforce {@code timeouts {}})</h2>
 *
 * <p>Terraform's per-resource {@code timeouts { create = "60m" ... }} block is
 * enforced <em>inside the provider</em>: the provider's SDK/framework wraps each
 * CRUD call in a context with that deadline and, when it fires, returns a
 * descriptive error <em>diagnostic</em> ("timeout while waiting for state ...").
 * Terraform core itself sets <em>no</em> gRPC deadline on these calls — it only
 * wires the context for cancellation (Ctrl-C). So the client-side deadline here is
 * NOT a re-implementation of {@code timeouts {}}: it is a backstop for a provider
 * that will <em>never</em> answer (deadlock, panic-but-alive, black-holed network).</p>
 *
 * <p>It is therefore set above the provider's own timeout on purpose. If it were
 * equal to or below the resource's configured timeout, the client would cancel the
 * call and the practitioner would see an opaque {@code DEADLINE_EXCEEDED} instead
 * of the provider's descriptive timeout diagnostic — a race the provider must
 * always win. The default ({@value #DEFAULT_RESOURCE_OPERATION_SECONDS}s = 6h)
 * clears the largest built-in provider default timeouts (low single-digit hours)
 * with margin. Operators whose {@code timeouts {}} blocks exceed the default raise
 * {@link #RESOURCE_ENV} — a single tunable ceiling is how the bridge honours long
 * timeouts, rather than fragile per-call config parsing that could pre-empt the
 * provider.</p>
 *
 * @param controlPlane      deadline for pure-compute control-plane RPCs
 * @param resourceOperation deadline for cloud-touching resource/data RPCs
 */
public record RpcDeadlines(Duration controlPlane, Duration resourceOperation) {

    /** Control-plane default: 60s — ~100x a healthy schema/validate/configure call. */
    static final long DEFAULT_CONTROL_PLANE_SECONDS = 60;

    /**
     * Resource-operation default: 6h. Exceeds the largest built-in provider default
     * timeouts with margin so a legitimate long apply can never trip it, while still
     * bounding a truly wedged process to hours rather than forever.
     */
    static final long DEFAULT_RESOURCE_OPERATION_SECONDS = 6 * 60 * 60;

    /** Env var overriding {@link #controlPlane()} (whole seconds). */
    static final String CONTROL_PLANE_ENV = "KITE_TF_BRIDGE_CONTROL_PLANE_DEADLINE_SECONDS";

    /** Env var overriding {@link #resourceOperation()} (whole seconds). */
    static final String RESOURCE_ENV = "KITE_TF_BRIDGE_RESOURCE_DEADLINE_SECONDS";

    public RpcDeadlines {
        requirePositive(controlPlane, "controlPlane");
        requirePositive(resourceOperation, "resourceOperation");
    }

    /** The built-in defaults (60s control-plane, 6h resource operations). */
    public static RpcDeadlines defaults() {
        return new RpcDeadlines(
                Duration.ofSeconds(DEFAULT_CONTROL_PLANE_SECONDS),
                Duration.ofSeconds(DEFAULT_RESOURCE_OPERATION_SECONDS));
    }

    /** Reads {@link #CONTROL_PLANE_ENV} / {@link #RESOURCE_ENV}, falling back to {@link #defaults()}. */
    public static RpcDeadlines fromEnvironment() {
        return fromEnvironment(System::getenv);
    }

    /**
     * Resolves deadlines from an environment lookup — the {@code System::getenv}
     * seam that keeps {@link #fromEnvironment()} unit-testable.
     */
    static RpcDeadlines fromEnvironment(Function<String, String> env) {
        return new RpcDeadlines(
                parseSecondsOrDefault(env.apply(CONTROL_PLANE_ENV), DEFAULT_CONTROL_PLANE_SECONDS, CONTROL_PLANE_ENV),
                parseSecondsOrDefault(env.apply(RESOURCE_ENV), DEFAULT_RESOURCE_OPERATION_SECONDS, RESOURCE_ENV));
    }

    private static Duration parseSecondsOrDefault(String raw, long fallbackSeconds, String name) {
        if (raw == null || raw.isBlank()) {
            return Duration.ofSeconds(fallbackSeconds);
        }
        long seconds;
        try {
            seconds = Long.parseLong(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "%s must be a whole number of seconds, got: %s".formatted(name, raw), e);
        }
        if (seconds <= 0) {
            throw new IllegalArgumentException(
                    "%s must be a positive number of seconds, got: %d".formatted(name, seconds));
        }
        return Duration.ofSeconds(seconds);
    }

    private static void requirePositive(Duration duration, String field) {
        if (duration == null || duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException(field + " deadline must be positive, got: " + duration);
        }
    }
}
