package dev.mcp.gateway.mcp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import dev.mcp.gateway.McpGatewayApplication;
import dev.mcp.gateway.support.IndependentHttpMock;
import dev.mcp.gateway.support.LocalTokenIssuer;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class SecuredGatewayIT {
    private static final String RESOURCE = "http://127.0.0.1:18080/worker-coordinator/mcp";
    private final JsonMapper json = JsonMapper.builder().build();
    private final List<McpAsyncClient> clients = new ArrayList<>();
    private IndependentHttpMock backend, payments;
    private LocalTokenIssuer issuer;
    private ConfigurableApplicationContext app;
    private String origin;
    private final AtomicReference<String> lastSession = new AtomicReference<>();

    @BeforeEach void start() throws Exception {
        backend = new IndependentHttpMock(); payments = new IndependentHttpMock(); issuer = new LocalTokenIssuer();
        app = new SpringApplicationBuilder(McpGatewayApplication.class).run("--spring.profiles.active=worker-payments,secured",
                "--server.port=0", "--gateway.backends.worker-coordinator.base-url=" + backend.origin(),
                "--gateway.backends.payment-api.base-url=" + payments.origin(),
                "--gateway.backends.worker-coordinator.bearer-token=backend-only-canary",
                "--gateway-security.issuer=" + issuer.origin(), "--gateway-security.jwk-set-uri=" + issuer.origin() + "/jwks",
                "--gateway-security.resource=" + RESOURCE, "--gateway-security.allow-loopback-http=true",
                "--gateway-security.policy-location=file:./examples/tenant-policy.json",
                "--gateway.upstream.connect-timeout=200ms", "--gateway.upstream.deadline=900ms");
        origin = "http://127.0.0.1:" + ((WebServerApplicationContext) app).getWebServer().getPort();
    }
    @AfterEach void stop() throws Exception {
        try {
            for (var client : clients) { try { client.closeGracefully().toFuture().get(3, TimeUnit.SECONDS); } catch (Exception ignored) { } }
        }
        finally { if (app != null) app.close(); if (backend != null) backend.close();
            if (payments != null) payments.close(); if (issuer != null) issuer.close(); }
    }
    private McpAsyncClient connect(String token) throws Exception {
        var transport = HttpClientStreamableHttpTransport.builder(origin).endpoint("/worker-coordinator/mcp")
                .requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer " + token))
                .httpRequestCustomizer((builder, method, uri, body, context) -> {
                    builder.build().headers().firstValue("Mcp-Session-Id").ifPresent(lastSession::set);
                }).build();
        var client = McpClient.async(transport).requestTimeout(Duration.ofSeconds(3)).build(); clients.add(client);
        client.initialize().toFuture().get(4, TimeUnit.SECONDS);
        client.listTools().toFuture().get(3, TimeUnit.SECONDS);
        return client;
    }
    private McpSchema.CallToolResult call(McpAsyncClient client, String name, Map<String, Object> args) throws Exception {
        return client.callTool(new McpSchema.CallToolRequest(name, args, null)).toFuture().get(4, TimeUnit.SECONDS);
    }
    private void denied(McpSchema.CallToolResult result) {
        assertThat(result.isError()).isTrue(); assertThat(result.structuredContent()).isNull();
        assertThat(((McpSchema.TextContent) result.content().getFirst()).text()).contains("ACCESS_DENIED", "not_attempted")
                .doesNotContain("tenant", "alice", "bob", "backend-only-canary");
    }
    private HttpResponse<String> http(String method, String token, String session, String path) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(origin + path)).timeout(Duration.ofSeconds(4));
        if (token != null) request.header("Authorization", "Bearer " + token);
        if (session != null) request.header("Mcp-Session-Id", session);
        request.header("Accept", "application/json, text/event-stream");
        request.method(method, HttpRequest.BodyPublishers.noBody());
        return HttpClient.newHttpClient().send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void rejectsInvalidTokensForEveryMcpMethodAndAdvertisesPublicMetadata(CapturedOutput output) throws Exception {
        var invalid = List.of(
                issuer.token("alice", "tenant-a", "gateway:read", "wrong-audience"),
                issuer.token("alice", "tenant-a", "gateway:read", RESOURCE, Map.of("iss", "https://wrong-issuer.example"), false),
                issuer.token("alice", "tenant-a", "gateway:read", RESOURCE, Map.of("exp", Date.from(Instant.now().minusSeconds(120))), false),
                issuer.token("alice", "tenant-a", "gateway:read", RESOURCE, Map.of("nbf", Date.from(Instant.now().plusSeconds(120))), false),
                issuer.token("alice", "tenant-a", "gateway:read", RESOURCE, Map.of("iat", Date.from(Instant.now().plusSeconds(60))), false),
                issuer.token("alice", "tenant-a", "gateway:read", RESOURCE, Map.of(), true));
        for (String method : List.of("POST", "GET", "DELETE")) {
            assertThat(http(method, null, null, "/worker-coordinator/mcp").statusCode()).isEqualTo(401);
            for (String token : invalid) {
                var response = http(method, token, null, "/worker-coordinator/mcp");
                assertThat(response.statusCode()).isEqualTo(401);
                assertThat(response.headers().firstValue("WWW-Authenticate").orElseThrow()).contains("resource_metadata", "gateway:read");
                assertThat(response.body()).doesNotContain(token, "alice", "tenant-a");
                assertThat(output.getAll()).doesNotContain(token);
            }
        }
        var metadata = http("GET", null, null, "/.well-known/oauth-protected-resource/worker-coordinator/mcp");
        assertThat(metadata.statusCode()).isEqualTo(200);
        assertThat(json.readTree(metadata.body()).path("resource").stringValue()).isEqualTo(RESOURCE);
        assertThat(metadata.body()).doesNotContain("policy", "backend", "alice", "tenant-a");
        assertThat(backend.count()).isZero(); assertThat(payments.count()).isZero(); assertThat(issuer.jwksCount()).isPositive();
    }

    @Test void authorizedReadAndDeniedWritesHaveSeparateScopesAndNoCredentialLeak(CapturedOutput output) throws Exception {
        String token = issuer.token("alice", "tenant-a", "gateway:read", RESOURCE);
        var reader = connect(token);
        backend.enqueue(IndependentHttpMock.Reply.json("{\"productId\":\"p\",\"productName\":\"Product\",\"status\":\"ACTIVE\"}"));
        assertThat(call(reader, "get_product", Map.of("productId", "p")).isError()).isFalse();
        assertThat(backend.takeRequest().header("Authorization")).isEqualTo("Bearer backend-only-canary");
        denied(call(reader, "register_product", Map.of("productId", "p", "productName", "Product")));
        denied(call(reader, "acquire_worker", owner()));
        denied(call(reader, "create_sample_payment", Map.of()));
        assertThat(backend.count()).isEqualTo(1); assertThat(payments.count()).isZero();
        assertThat(output.getAll()).doesNotContain(token, "backend-only-canary", "tenant-a", "alice");
    }

    @Test void enforcesCompleteTenantNamespaceAndOwnerRelationships() throws Exception {
        var writer = connect(issuer.token("alice", "tenant-a", "gateway:read gateway:write", RESOURCE));
        denied(call(writer, "get_product", Map.of("productId", "bp")));
        denied(call(writer, "get_service", Map.of("serviceId", "bs")));
        denied(call(writer, "get_worker_type", Map.of("workerTypeId", "bw")));
        denied(call(writer, "register_product", Map.of("productId", "unapproved", "productName", "New")));
        denied(call(writer, "register_service", Map.of("productId", "p2", "serviceId", "s", "serviceName", "Mixed")));
        denied(call(writer, "register_worker_type", Map.of("serviceId", "s2", "workerTypeId", "w", "workerTypeName", "Mixed")));
        for (var change : Map.<String, Object>of("productId", "p2", "serviceId", "bs", "workerTypeId", "w2", "regionId", 1,
                "instanceId", "00000000-0000-0000-0000-000000000003",
                "registrationId", "00000000-0000-0000-0000-000000000004").entrySet()) {
            var arguments = new LinkedHashMap<>(owner()); arguments.put(change.getKey(), change.getValue());
            denied(call(writer, "acquire_worker", arguments));
            arguments.put("workerId", 1); arguments.put("epoch", 9007199254740993L);
            denied(call(writer, "renew_worker_lease", arguments)); denied(call(writer, "release_worker", arguments));
        }
        assertThat(backend.count()).isZero(); assertThat(payments.count()).isZero();
        backend.enqueue(IndependentHttpMock.Reply.json("{\"productId\":\"p\",\"productName\":\"Product\",\"status\":\"ACTIVE\"}"));
        assertThat(call(writer, "register_product", Map.of("productId", "p", "productName", "Product")).isError()).isFalse();
        assertThat(backend.count()).isEqualTo(1);
        var bob = connect(issuer.token("bob", "tenant-b", "gateway:write", RESOURCE));
        denied(call(bob, "create_sample_payment", Map.of())); assertThat(payments.count()).isZero();
    }

    @Test void sessionCannotBeReusedByAnotherSubjectTenantOrExpiredCredential() throws Exception {
        var alice = connect(issuer.token("alice", "tenant-a", "gateway:read", RESOURCE));
        String session = lastSession.get(); assertThat(session).isNotBlank();
        for (String token : List.of(issuer.token("bob", "tenant-b", "gateway:read", RESOURCE),
                issuer.token("amy", "tenant-a", "gateway:read", RESOURCE))) {
            for (String method : List.of("POST", "GET", "DELETE")) {
                assertThat(http(method, token, session, "/worker-coordinator/mcp").statusCode()).isEqualTo(403);
            }
        }
        String expired = issuer.token("alice", "tenant-a", "gateway:read", RESOURCE,
                Map.of("exp", Date.from(Instant.now().minusSeconds(120))), false);
        assertThat(http("POST", expired, session, "/worker-coordinator/mcp").statusCode()).isEqualTo(401);
        assertThat(alice.listTools().toFuture().get(3, TimeUnit.SECONDS).tools()).hasSize(10);
        assertThat(backend.count()).isZero();
    }

    @Test void concurrentClientsCannotLeakAuthorizationContext() throws Exception {
        var alice = connect(issuer.token("alice", "tenant-a", "gateway:read", RESOURCE));
        var bob = connect(issuer.token("bob", "tenant-b", "gateway:read", RESOURCE));
        for (int i = 0; i < 5; i++) {
            backend.enqueue(IndependentHttpMock.Reply.json("{\"productId\":\"p\",\"productName\":\"Product\",\"status\":\"ACTIVE\"}"));
            backend.enqueue(IndependentHttpMock.Reply.json("{\"productId\":\"bp\",\"productName\":\"Product\",\"status\":\"ACTIVE\"}"));
            // Both replies share a schema; authorization, exact paths and per-client denials are asserted separately.
            var calls = List.of(alice.callTool(new McpSchema.CallToolRequest("get_product", Map.of("productId", "p"), null)).toFuture(),
                    bob.callTool(new McpSchema.CallToolRequest("get_product", Map.of("productId", "bp"), null)).toFuture(),
                    alice.callTool(new McpSchema.CallToolRequest("get_product", Map.of("productId", "bp"), null)).toFuture(),
                    bob.callTool(new McpSchema.CallToolRequest("get_product", Map.of("productId", "p"), null)).toFuture());
            assertThat(calls.get(0).get(4, TimeUnit.SECONDS).isError()).isFalse();
            assertThat(calls.get(1).get(4, TimeUnit.SECONDS).isError()).isFalse();
            denied(calls.get(2).get(4, TimeUnit.SECONDS)); denied(calls.get(3).get(4, TimeUnit.SECONDS));
            assertThat(List.of(backend.takeRequest().path(), backend.takeRequest().path()))
                    .containsExactlyInAnyOrder("/api/v1/products/p", "/api/v1/products/bp");
        }
        assertThat(backend.count()).isEqualTo(10);
    }

    @Test void securedProfileFailsStartupWithoutRequiredSettingsOrWithExplicitDisable() {
        assertThatThrownBy(() -> new SpringApplicationBuilder(McpGatewayApplication.class).run(
                "--spring.profiles.active=worker-payments,secured", "--server.port=0"))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> new SpringApplicationBuilder(McpGatewayApplication.class).run(
                "--spring.profiles.active=worker-payments,secured", "--server.port=0", "--gateway-security.enabled=false",
                "--gateway-security.issuer=" + issuer.origin(), "--gateway-security.jwk-set-uri=" + issuer.origin() + "/jwks",
                "--gateway-security.resource=" + RESOURCE, "--gateway-security.allow-loopback-http=true",
                "--gateway-security.policy-location=file:./examples/tenant-policy.json"))
                .isInstanceOf(RuntimeException.class).hasRootCauseMessage("secured profile cannot disable caller security");
        assertThat(backend.count()).isZero(); assertThat(payments.count()).isZero();
    }

    @Test void clientMetadataCannotForgeIdentityAndInvalidArgumentsStillMakeNoBackendCalls() throws Exception {
        var reader = connect(issuer.token("alice", "tenant-a", "gateway:read", RESOURCE));
        var metadata = Map.<String, Object>of("tenant", "tenant-b", "subject", "bob", "scope", "gateway:write");
        denied(reader.callTool(new McpSchema.CallToolRequest("create_sample_payment", Map.of(), metadata))
                .toFuture().get(4, TimeUnit.SECONDS));
        var invalid = call(reader, "get_product", Map.of("productId", "p", "tenant", "tenant-a"));
        assertThat(invalid.isError()).isTrue(); assertThat(invalid.structuredContent()).isNull();
        assertThat(((McpSchema.TextContent) invalid.content().getFirst()).text()).contains("INVALID_ARGUMENTS", "not_attempted");
        var tools = reader.listTools().toFuture().get(3, TimeUnit.SECONDS).tools();
        assertThat(json.writeValueAsString(tools)).doesNotContain("backendRef", "tenant-a", "alice", "policy", "backend-only-canary");
        assertThat(backend.count()).isZero(); assertThat(payments.count()).isZero();
        var request = HttpRequest.newBuilder(URI.create(origin + "/worker-coordinator/mcp"))
                .header("Authorization", "Bearer " + issuer.token("alice", "tenant-a", "gateway:read", RESOURCE))
                .header("Origin", "http://evil.example").timeout(Duration.ofSeconds(2)).GET().build();
        assertThat(HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding()).statusCode()).isEqualTo(403);
        assertThat(backend.count()).isZero();
    }
    private Map<String, Object> owner() { return Map.of("productId", "p", "serviceId", "s", "workerTypeId", "w", "regionId", 0,
            "instanceId", "00000000-0000-0000-0000-000000000001", "registrationId", "00000000-0000-0000-0000-000000000002"); }
}
