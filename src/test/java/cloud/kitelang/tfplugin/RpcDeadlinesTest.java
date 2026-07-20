package cloud.kitelang.tfplugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * TDD tests for {@link RpcDeadlines}: the built-in defaults, environment parsing,
 * and validation of the per-call gRPC deadline configuration (kitecorp/kite#33).
 */
class RpcDeadlinesTest {

    @Test
    @DisplayName("defaults() should be 60s control-plane and 6h resource operations")
    void defaultsShouldBeSixtySecondsAndSixHours() {
        var deadlines = RpcDeadlines.defaults();

        assertEquals(Duration.ofSeconds(60), deadlines.controlPlane());
        assertEquals(Duration.ofHours(6), deadlines.resourceOperation());
    }

    @Test
    @DisplayName("fromEnvironment() should fall back to defaults when the vars are unset")
    void fromEnvironmentShouldUseDefaultsWhenUnset() {
        var deadlines = RpcDeadlines.fromEnvironment(name -> null);

        assertEquals(RpcDeadlines.defaults(), deadlines);
    }

    @Test
    @DisplayName("fromEnvironment() should fall back to defaults when the vars are blank")
    void fromEnvironmentShouldUseDefaultsWhenBlank() {
        var deadlines = RpcDeadlines.fromEnvironment(Map.of(
                RpcDeadlines.CONTROL_PLANE_ENV, "   ",
                RpcDeadlines.RESOURCE_ENV, "")::get);

        assertEquals(RpcDeadlines.defaults(), deadlines);
    }

    @Test
    @DisplayName("fromEnvironment() should parse whole-second overrides")
    void fromEnvironmentShouldParseOverrides() {
        var deadlines = RpcDeadlines.fromEnvironment(Map.of(
                RpcDeadlines.CONTROL_PLANE_ENV, "120",
                RpcDeadlines.RESOURCE_ENV, "43200")::get);

        assertEquals(Duration.ofSeconds(120), deadlines.controlPlane());
        assertEquals(Duration.ofSeconds(43200), deadlines.resourceOperation());
    }

    @Test
    @DisplayName("fromEnvironment() should reject a non-numeric value")
    void fromEnvironmentShouldRejectNonNumeric() {
        var exception = assertThrows(IllegalArgumentException.class,
                () -> RpcDeadlines.fromEnvironment(Map.of(RpcDeadlines.RESOURCE_ENV, "ten")::get));

        assertEquals("KITE_TF_BRIDGE_RESOURCE_DEADLINE_SECONDS must be a whole number of seconds, got: ten",
                exception.getMessage());
    }

    @Test
    @DisplayName("fromEnvironment() should reject a non-positive value")
    void fromEnvironmentShouldRejectNonPositive() {
        var exception = assertThrows(IllegalArgumentException.class,
                () -> RpcDeadlines.fromEnvironment(Map.of(RpcDeadlines.CONTROL_PLANE_ENV, "0")::get));

        assertEquals("KITE_TF_BRIDGE_CONTROL_PLANE_DEADLINE_SECONDS must be a positive number of seconds, got: 0",
                exception.getMessage());
    }

    @Test
    @DisplayName("constructor should reject a zero duration")
    void constructorShouldRejectZeroDuration() {
        var exception = assertThrows(IllegalArgumentException.class,
                () -> new RpcDeadlines(Duration.ZERO, Duration.ofHours(6)));

        assertEquals("controlPlane deadline must be positive, got: PT0S", exception.getMessage());
    }

    @Test
    @DisplayName("constructor should reject a negative duration")
    void constructorShouldRejectNegativeDuration() {
        var exception = assertThrows(IllegalArgumentException.class,
                () -> new RpcDeadlines(Duration.ofSeconds(60), Duration.ofSeconds(-1)));

        assertEquals("resourceOperation deadline must be positive, got: PT-1S", exception.getMessage());
    }
}
