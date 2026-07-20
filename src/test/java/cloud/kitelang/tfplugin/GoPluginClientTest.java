package cloud.kitelang.tfplugin;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * TDD tests for {@link GoPluginClient}.
 *
 * <p>Since launching a real Terraform provider binary is not feasible in unit tests,
 * these tests focus on the handshake parsing logic and environment setup validation
 * extracted into testable static/package-private methods.</p>
 */
class GoPluginClientTest {

    // ---------------------------------------------------------------
    // 1. Valid handshake parsing
    // ---------------------------------------------------------------
    @Nested
    @DisplayName("Valid handshake parsing")
    class ValidHandshakes {

        @Test
        @DisplayName("should parse valid TCP handshake with protocol version 5")
        void shouldParseValidTcpHandshake() {
            var result = GoPluginClient.parseHandshake("1|5|tcp|127.0.0.1:54321|grpc");

            assertEquals(1, result.coreProtocol());
            assertEquals(5, result.appProtocol());
            assertEquals("tcp", result.networkType());
            assertEquals("127.0.0.1:54321", result.networkAddr());
            assertEquals("grpc", result.protocol());
        }

        @Test
        @DisplayName("should parse valid TCP handshake with protocol version 6")
        void shouldParseValidTcpHandshakeV6() {
            var result = GoPluginClient.parseHandshake("1|6|tcp|127.0.0.1:9999|grpc");

            assertEquals(1, result.coreProtocol());
            assertEquals(6, result.appProtocol());
            assertEquals("tcp", result.networkType());
            assertEquals("127.0.0.1:9999", result.networkAddr());
            assertEquals("grpc", result.protocol());
        }

        @Test
        @DisplayName("should parse valid Unix socket handshake")
        void shouldParseValidUnixHandshake() {
            var result = GoPluginClient.parseHandshake("1|6|unix|/tmp/plugin.sock|grpc");

            assertEquals(1, result.coreProtocol());
            assertEquals(6, result.appProtocol());
            assertEquals("unix", result.networkType());
            assertEquals("/tmp/plugin.sock", result.networkAddr());
            assertEquals("grpc", result.protocol());
        }

        @Test
        @DisplayName("should trim whitespace from handshake line")
        void shouldTrimWhitespace() {
            var result = GoPluginClient.parseHandshake("  1|5|tcp|127.0.0.1:54321|grpc  \n");

            assertEquals(1, result.coreProtocol());
            assertEquals(5, result.appProtocol());
            assertEquals("tcp", result.networkType());
            assertEquals("127.0.0.1:54321", result.networkAddr());
            assertEquals("grpc", result.protocol());
        }
    }

    // ---------------------------------------------------------------
    // 2. Invalid handshake — error cases
    // ---------------------------------------------------------------
    @Nested
    @DisplayName("Invalid handshake parsing")
    class InvalidHandshakes {

        @Test
        @DisplayName("should reject unsupported core protocol version")
        void shouldRejectInvalidCoreProtocol() {
            var exception = assertThrows(GoPluginException.class,
                    () -> GoPluginClient.parseHandshake("2|5|tcp|127.0.0.1:54321|grpc"));

            assertTrue(exception.getMessage().contains("core protocol"),
                    "Exception message should mention core protocol: " + exception.getMessage());
        }

        @Test
        @DisplayName("should reject non-grpc protocol")
        void shouldRejectNonGrpcProtocol() {
            var exception = assertThrows(GoPluginException.class,
                    () -> GoPluginClient.parseHandshake("1|5|tcp|127.0.0.1:54321|netrpc"));

            assertTrue(exception.getMessage().contains("grpc") || exception.getMessage().contains("protocol"),
                    "Exception message should mention protocol: " + exception.getMessage());
        }

        @Test
        @DisplayName("should reject malformed handshake line")
        void shouldRejectMalformedLine() {
            assertThrows(GoPluginException.class,
                    () -> GoPluginClient.parseHandshake("not a handshake"));
        }

        @Test
        @DisplayName("should reject empty handshake line")
        void shouldRejectEmptyLine() {
            assertThrows(GoPluginException.class,
                    () -> GoPluginClient.parseHandshake(""));
        }

        @Test
        @DisplayName("should reject null handshake line")
        void shouldRejectNullLine() {
            assertThrows(GoPluginException.class,
                    () -> GoPluginClient.parseHandshake(null));
        }

        @Test
        @DisplayName("should reject handshake with missing fields")
        void shouldRejectMissingFields() {
            assertThrows(GoPluginException.class,
                    () -> GoPluginClient.parseHandshake("1|5|tcp"));
        }

        @Test
        @DisplayName("should reject handshake with non-numeric core protocol")
        void shouldRejectNonNumericCoreProtocol() {
            assertThrows(GoPluginException.class,
                    () -> GoPluginClient.parseHandshake("x|5|tcp|127.0.0.1:54321|grpc"));
        }

        @Test
        @DisplayName("should reject handshake with non-numeric app protocol")
        void shouldRejectNonNumericAppProtocol() {
            assertThrows(GoPluginException.class,
                    () -> GoPluginClient.parseHandshake("1|x|tcp|127.0.0.1:54321|grpc"));
        }
    }

    // ---------------------------------------------------------------
    // 3. Environment variable setup
    // ---------------------------------------------------------------
    @Nested
    @DisplayName("Environment variable setup")
    class EnvironmentSetup {

        @Test
        @DisplayName("should set the magic cookie environment variable (framework value)")
        void shouldSetMagicCookieEnvVar() {
            var env = GoPluginClient.buildEnvironment();

            assertEquals(
                    "d602bf8f470bc67ca7faa0386276bbdd4330efaf76d1a219cb4d6991ca9872b2",
                    env.get("TF_PLUGIN_MAGIC_COOKIE")
            );
        }

        @Test
        @DisplayName("should include the magic cookie key in the environment")
        void shouldContainMagicCookieKey() {
            var env = GoPluginClient.buildEnvironment();

            assertTrue(env.containsKey("TF_PLUGIN_MAGIC_COOKIE"),
                    "Environment must contain TF_PLUGIN_MAGIC_COOKIE");
        }

        @Test
        @DisplayName("should set PLUGIN_PROTOCOL_VERSIONS for go-plugin handshake")
        void shouldSetPluginProtocolVersions() {
            var env = GoPluginClient.buildEnvironment();

            assertEquals("5,6", env.get("PLUGIN_PROTOCOL_VERSIONS"));
        }
    }

    // ---------------------------------------------------------------
    // 4. Protocol-version-based RPC selection
    // ---------------------------------------------------------------
    @Nested
    @DisplayName("RPC implementation selection")
    class RpcSelection {

        @Test
        @DisplayName("should select the tfplugin5 implementation for app protocol 5")
        void shouldSelectTfplugin5ForProtocol5() {
            withIdleChannel(channel ->
                    assertInstanceOf(Tfplugin5Rpc.class, GoPluginClient.createRpc(5, channel)));
        }

        @Test
        @DisplayName("should select the tfplugin6 implementation for app protocol 6")
        void shouldSelectTfplugin6ForProtocol6() {
            withIdleChannel(channel ->
                    assertInstanceOf(Tfplugin6Rpc.class, GoPluginClient.createRpc(6, channel)));
        }

        @Test
        @DisplayName("should reject app protocol versions other than 5 and 6")
        void shouldRejectUnsupportedProtocolVersion() {
            withIdleChannel(channel -> {
                var exception = assertThrows(GoPluginException.class,
                        () -> GoPluginClient.createRpc(7, channel));
                assertEquals("Unsupported app protocol version: 7 (supported: 5 and 6)",
                        exception.getMessage());
            });
        }

        /**
         * Runs the assertion against a lazily-connecting channel (gRPC channels
         * only dial on the first RPC, so no listener is needed) and always shuts
         * it down to avoid leaking resources.
         */
        private void withIdleChannel(java.util.function.Consumer<io.grpc.ManagedChannel> assertion) {
            var channel = io.grpc.ManagedChannelBuilder.forTarget("localhost:1")
                    .usePlaintext()
                    .build();
            try {
                assertion.accept(channel);
            } finally {
                channel.shutdownNow();
            }
        }
    }

    // ---------------------------------------------------------------
    // 5. gRPC target construction
    // ---------------------------------------------------------------
    @Nested
    @DisplayName("gRPC target construction")
    class GrpcTarget {

        @Test
        @DisplayName("should construct TCP target as-is")
        void shouldConstructTcpTarget() {
            var handshake = GoPluginClient.parseHandshake("1|5|tcp|127.0.0.1:54321|grpc");

            var target = GoPluginClient.buildGrpcTarget(handshake);

            assertEquals("127.0.0.1:54321", target);
        }

        @Test
        @DisplayName("should construct Unix socket target with unix: prefix")
        void shouldConstructUnixTarget() {
            var handshake = GoPluginClient.parseHandshake("1|6|unix|/tmp/plugin.sock|grpc");

            var target = GoPluginClient.buildGrpcTarget(handshake);

            assertEquals("unix:/tmp/plugin.sock", target);
        }
    }
}
