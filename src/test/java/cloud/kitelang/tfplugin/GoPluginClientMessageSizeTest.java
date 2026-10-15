package cloud.kitelang.tfplugin;

import io.grpc.Server;
import io.grpc.netty.shaded.io.grpc.netty.NettyServerBuilder;
import io.grpc.netty.shaded.io.netty.channel.nio.NioEventLoopGroup;
import io.grpc.netty.shaded.io.netty.channel.socket.nio.NioServerDomainSocketChannel;
import io.grpc.protobuf.services.HealthStatusManager;
import io.grpc.stub.StreamObserver;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.io.TempDir;
import tfplugin5.ProviderGrpc;
import tfplugin5.Tfplugin5;

import java.io.IOException;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.UnixDomainSocketAddress;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * A real provider's schema is larger than gRPC's default 4 MiB message limit:
 * hashicorp/aws 6.x answers GetSchema with about 7.5 MB, which the bridge refused
 * with RESOURCE_EXHAUSTED, so tf/aws never loaded (kitecorp/kite#109). Terraform's
 * go-plugin client lifts the limit; this client must too, on both transports a
 * provider can announce in its handshake.
 *
 * <p>The "provider" is a shell that prints a go-plugin handshake line pointing at an
 * in-test gRPC server and then blocks, so the real constructor, channel and stubs run.</p>
 */
@Timeout(60)
class GoPluginClientMessageSizeTest {

    /** Resource schemas in the oversized response; each carries a 1 KiB description. */
    private static final int RESOURCE_COUNT = 6_000;

    @TempDir
    Path socketDir;

    private final List<AutoCloseable> cleanups = new ArrayList<>();

    @AfterEach
    void cleanUp() throws Exception {
        for (var cleanup : cleanups.reversed()) {
            cleanup.close();
        }
    }

    @Test
    void schemaLargerThanFourMebibytes_overUnixSocket_isReceived() throws Exception {
        var socket = socketDir.resolve("provider.sock");
        var group = new NioEventLoopGroup(1);
        cleanups.add(group::shutdownGracefully);
        startServer(NettyServerBuilder.forAddress(UnixDomainSocketAddress.of(socket))
                .channelType(NioServerDomainSocketChannel.class)
                .bossEventLoopGroup(group)
                .workerEventLoopGroup(group));

        var schema = connect("1|5|unix|" + socket + "|grpc").rpc().getProviderSchema();

        assertEquals(RESOURCE_COUNT, schema.resourceSchemas().size());
    }

    @Test
    void schemaLargerThanFourMebibytes_overTcp_isReceived() throws Exception {
        var server = startServer(NettyServerBuilder.forAddress(new InetSocketAddress(InetAddress.ofLiteral("127.0.0.1"), 0)));

        var schema = connect("1|5|tcp|127.0.0.1:" + server.getPort() + "|grpc").rpc().getProviderSchema();

        assertEquals(RESOURCE_COUNT, schema.resourceSchemas().size());
    }

    private Server startServer(NettyServerBuilder builder) throws IOException {
        var health = new HealthStatusManager();
        health.setStatus("plugin", io.grpc.health.v1.HealthCheckResponse.ServingStatus.SERVING);
        var server = builder
                .addService(health.getHealthService())
                .addService(new OversizedSchemaProvider())
                .build()
                .start();
        cleanups.add(server::shutdownNow);
        return server;
    }

    /** Wires a client to a shell that announces the server in its handshake line, then blocks. */
    private GoPluginClient connect(String handshakeLine) throws IOException {
        var process = new ProcessBuilder("/bin/sh", "-c", "echo '" + handshakeLine + "'; exec cat").start();
        var client = new GoPluginClient(process, Duration.ofSeconds(10), RpcDeadlines.defaults());
        cleanups.add(client::close);
        return client;
    }

    /** Answers GetSchema with {@link #RESOURCE_COUNT} resource schemas: about 6 MiB on the wire. */
    private static class OversizedSchemaProvider extends ProviderGrpc.ProviderImplBase {
        @Override
        public void getSchema(Tfplugin5.GetProviderSchema.Request request,
                              StreamObserver<Tfplugin5.GetProviderSchema.Response> observer) {
            var description = "d".repeat(1024);
            var response = Tfplugin5.GetProviderSchema.Response.newBuilder();
            for (var i = 0; i < RESOURCE_COUNT; i++) {
                response.putResourceSchemas("test_resource_" + i, Tfplugin5.Schema.newBuilder()
                        .setBlock(Tfplugin5.Schema.Block.newBuilder().setDescription(description))
                        .build());
            }
            observer.onNext(response.build());
            observer.onCompleted();
        }

        @Override
        public void stop(Tfplugin5.Stop.Request request, StreamObserver<Tfplugin5.Stop.Response> observer) {
            observer.onNext(Tfplugin5.Stop.Response.getDefaultInstance());
            observer.onCompleted();
        }
    }
}
