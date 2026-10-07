package dev.mcp.gateway.mcp;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import dev.mcp.gateway.McpGatewayApplication;
import dev.mcp.gateway.support.CommittingPaymentMock;
import dev.mcp.gateway.support.LocalTokenIssuer;
import dev.mcp.gateway.support.DisconnectingTcpBridge;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class ResponseLossIT {
    private static final String RESOURCE = "http://127.0.0.1:18080/worker-coordinator/mcp";
    private final JsonMapper json = JsonMapper.builder().build();
    @Test void retryRecoversOriginalCommittedResultButNewInvocationCreatesAnotherKey() throws Exception {
        try (var backend = new CommittingPaymentMock(CommittingPaymentMock.Mode.DROP_FIRST); var issuer = new LocalTokenIssuer();
                var app = start(backend, issuer)) {
            var client = connect(app, issuer);
            try {
                assertThat(backend.count()).isZero();
                var result = call(client).toFuture().get(4, TimeUnit.SECONDS);
                assertThat(result.isError()).isFalse();
                assertThat(backend.count()).isEqualTo(2); assertThat(backend.commits()).isEqualTo(1);
                var first = backend.take(); var retry = backend.take(); assertThat(retry).isEqualTo(first);
                assertThat(json.valueToTree(result.structuredContent()).path("paymentId").stringValue())
                        .isEqualTo(backend.result(first.key()).toString());
                assertThat(json.readTree(((McpSchema.TextContent) result.content().getFirst()).text()))
                        .isEqualTo(json.valueToTree(result.structuredContent()));
                assertThat(call(client).toFuture().get(4, TimeUnit.SECONDS).isError()).isFalse();
                assertThat(backend.take().key()).isNotEqualTo(first.key());
                assertThat(backend.count()).isEqualTo(3); assertThat(backend.commits()).isEqualTo(2);
            }
            finally { client.closeGracefully().toFuture().get(3, TimeUnit.SECONDS); }
        }
    }
    @Test void exhaustedDeadlineReportsUnknownAfterOneLogicalCommitAndStopsAttempts() throws Exception {
        try (var backend = new CommittingPaymentMock(CommittingPaymentMock.Mode.DROP_ALWAYS); var issuer = new LocalTokenIssuer();
                var app = start(backend, issuer)) {
            var client = connect(app, issuer);
            try {
                long started = System.nanoTime();
                var result = call(client).toFuture().get(4, TimeUnit.SECONDS);
                assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
                assertThat(result.isError()).isTrue(); assertThat(result.structuredContent()).isNull();
                assertThat(((McpSchema.TextContent) result.content().getFirst()).text()).contains("UPSTREAM_TIMEOUT", "unknown");
                int attempts = backend.count(); assertThat(attempts).isBetween(2, 6); assertThat(backend.commits()).isEqualTo(1);
                var first = backend.take();
                for (int i = 1; i < attempts; i++) assertThat(backend.take()).isEqualTo(first);
                assertThat(backend.poll(400)).isNull(); assertThat(backend.count()).isEqualTo(attempts);
            }
            finally { client.closeGracefully().toFuture().get(3, TimeUnit.SECONDS); }
        }
    }
    @Test void disconnectedSdkHttpStreamCancelsCommittedInvocationAndFurtherLocalRetries(CapturedOutput output) throws Exception {
        for (var mode : java.util.List.of(CommittingPaymentMock.Mode.DROP_ALWAYS, CommittingPaymentMock.Mode.STALL_AFTER_COMMIT)) {
            try (var backend = new CommittingPaymentMock(mode); var issuer = new LocalTokenIssuer(); var app = start(backend, issuer)) {
                int offset = output.getAll().length();
                var bridge = new DisconnectingTcpBridge(((WebServerApplicationContext) app).getWebServer().getPort());
                var client = connectAt(bridge.origin(), issuer);
                var future = call(client).toFuture();
                backend.take();
                bridge.close();
                long until = System.nanoTime() + Duration.ofMillis(600).toNanos();
                while (!output.getAll().substring(offset).contains("outcome=cancelled") && System.nanoTime() < until) Thread.sleep(10);
                assertThat(output.getAll().substring(offset)).contains("outcome=cancelled");
                assertThat(backend.poll(450)).isNull();
                assertThat(backend.count()).isEqualTo(1); assertThat(backend.commits()).isEqualTo(1);
                future.cancel(true);
                try { client.closeGracefully().toFuture().get(2, TimeUnit.SECONDS); } catch (Exception disconnected) { }
            }
        }
    }
    private ConfigurableApplicationContext start(CommittingPaymentMock backend, LocalTokenIssuer issuer) {
        return new SpringApplicationBuilder(McpGatewayApplication.class).run("--spring.profiles.active=payments,secured", "--server.port=0",
                "--gateway.backends.worker-coordinator.base-url=" + backend.origin(), "--gateway.upstream.connect-timeout=200ms",
                "--gateway.upstream.deadline=900ms", "--gateway-security.allow-loopback-http=true",
                "--gateway-security.issuer=" + issuer.origin(), "--gateway-security.jwk-set-uri=" + issuer.origin() + "/jwks",
                "--gateway-security.resource=" + RESOURCE, "--gateway-security.policy-location=file:./examples/tenant-policy.json");
    }
    private McpAsyncClient connect(ConfigurableApplicationContext app, LocalTokenIssuer issuer) throws Exception {
        String origin = "http://127.0.0.1:" + ((WebServerApplicationContext) app).getWebServer().getPort();
        return connectAt(origin, issuer);
    }
    private McpAsyncClient connectAt(String origin, LocalTokenIssuer issuer) throws Exception {
        var transport = HttpClientStreamableHttpTransport.builder(origin).endpoint("/worker-coordinator/mcp")
                .requestBuilder(java.net.http.HttpRequest.newBuilder().header("Authorization", "Bearer "
                        + issuer.token("alice", "tenant-a", "gateway:write", RESOURCE))).build();
        var client = McpClient.async(transport).requestTimeout(Duration.ofSeconds(3)).build();
        client.initialize().toFuture().get(4, TimeUnit.SECONDS);
        client.listTools().toFuture().get(3, TimeUnit.SECONDS);
        return client;
    }
    private reactor.core.publisher.Mono<McpSchema.CallToolResult> call(McpAsyncClient client) {
        return client.callTool(new McpSchema.CallToolRequest("create_sample_payment", Map.of(), null));
    }
}
