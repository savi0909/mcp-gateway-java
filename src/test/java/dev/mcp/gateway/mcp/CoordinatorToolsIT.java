package dev.mcp.gateway.mcp;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import dev.mcp.gateway.McpGatewayApplication;
import dev.mcp.gateway.support.IndependentHttpMock;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.json.JsonMapper;

import static org.assertj.core.api.Assertions.*;

/** Real SDK -> running Boot -> two independent HTTP listeners; never uses live services. */
class CoordinatorToolsIT {
    private final JsonMapper json = JsonMapper.builder().build();
    private IndependentHttpMock coordinator;
    private IndependentHttpMock payments;
    private ConfigurableApplicationContext application;
    private McpAsyncClient client;
    private String origin;

    @BeforeEach
    void start() throws Exception {
        coordinator = new IndependentHttpMock();
        payments = new IndependentHttpMock();
        application = new SpringApplicationBuilder(McpGatewayApplication.class).run(
                "--spring.profiles.active=worker-payments", "--server.port=0",
                "--gateway.backends.worker-coordinator.base-url=" + coordinator.origin(),
                "--gateway.backends.payment-api.base-url=" + payments.origin(),
                "--gateway.backends.worker-coordinator.bearer-token=fixture-coordinator",
                "--gateway.backends.payment-api.bearer-token=fixture-payment",
                "--gateway.upstream.connect-timeout=200ms", "--gateway.upstream.deadline=1s");
        origin = "http://127.0.0.1:" + ((WebServerApplicationContext) application).getWebServer().getPort();
        client = McpClient.async(HttpClientStreamableHttpTransport.builder(origin)
                .endpoint("/worker-coordinator/mcp").build()).requestTimeout(Duration.ofSeconds(4)).build();
        client.initialize().toFuture().get(5, TimeUnit.SECONDS);
    }

    @AfterEach
    void stop() throws Exception {
        try {
            if (client != null) {
                client.closeGracefully().toFuture().get(5, TimeUnit.SECONDS);
            }
        }
        finally {
            if (application != null) application.close();
            if (coordinator != null) coordinator.close();
            if (payments != null) payments.close();
        }
    }

    @Test
    void discoveryExposesTenPublicDefinitionsWithoutCallingEitherBackend() throws Exception {
        var tools = client.listTools().toFuture().get(4, TimeUnit.SECONDS).tools();
        assertThat(tools).extracting(McpSchema.Tool::name).containsExactlyInAnyOrder("register_product", "get_product",
                "register_service", "get_service", "register_worker_type", "get_worker_type", "acquire_worker",
                "renew_worker_lease", "release_worker", "create_sample_payment");
        assertThat(json.writeValueAsString(tools)).doesNotContain("backendRef", "bodyArguments", "pathArguments",
                "fixture-coordinator", "fixture-payment", coordinator.origin().toString(), payments.origin().toString());
        assertThat(coordinator.count()).isZero();
        assertThat(payments.count()).isZero();
    }

    @Test
    void mapsEveryCoordinatorApiAndKeepsLeaseEpochExact() throws Exception {
        var product = Map.<String, Object>of("productId", "p", "productName", "Product");
        var service = Map.<String, Object>of("productId", "p", "serviceId", "s", "serviceName", "Service");
        var workerType = Map.<String, Object>of("serviceId", "s", "workerTypeId", "w", "workerTypeName", "Worker");
        var productResponse = new LinkedHashMap<>(product); productResponse.put("status", "ACTIVE");
        var serviceResponse = new LinkedHashMap<>(service); serviceResponse.put("status", "ACTIVE");
        var typeResponse = new LinkedHashMap<>(workerType); typeResponse.put("status", "ACTIVE");
        check("register_product", product, "POST", "/api/v1/products", product, productResponse);
        check("get_product", Map.of("productId", "p"), "GET", "/api/v1/products/p", null, productResponse);
        check("register_service", service, "POST", "/api/v1/products/p/services",
                Map.of("serviceId", "s", "serviceName", "Service"), serviceResponse);
        check("get_service", Map.of("serviceId", "s"), "GET", "/api/v1/services/s", null, serviceResponse);
        check("register_worker_type", workerType, "POST", "/api/v1/services/s/worker-types",
                Map.of("workerTypeId", "w", "workerTypeName", "Worker"), typeResponse);
        check("get_worker_type", Map.of("workerTypeId", "w"), "GET", "/api/v1/worker-types/w", null, typeResponse);
        var owner = owner();
        var lease = new LinkedHashMap<>(owner);
        lease.remove("registrationId");
        lease.put("workerId", 42); lease.put("epoch", 9007199254740993L);
        lease.put("leaseExpiry", "2026-10-07T10:00:00Z");
        lease.put("leaseDuration", "PT30S");
        check("acquire_worker", owner, "POST", "/api/v1/workers/acquire", owner, lease);
        lease.put("leaseDuration", 30);
        var key = new LinkedHashMap<>(owner); key.put("workerId", 42); key.put("epoch", 9007199254740993L);
        check("renew_worker_lease", key, "POST", "/api/v1/workers/renew", key, lease);
        check("release_worker", key, "POST", "/api/v1/workers/release", key,
                Map.of("released", true, "epoch", 9007199254740993L));
        assertThat(coordinator.count()).isEqualTo(9);
        assertThat(payments.count()).isZero();
    }

    @Test
    void keepsPaymentBackendAndCredentialsSeparate() throws Exception {
        payments.enqueue(IndependentHttpMock.Reply.json("{\"id\":9007199254740993}"));
        var result = call("create_sample_payment", Map.of());
        assertThat(result.isError()).isFalse();
        assertThat(json.valueToTree(result.structuredContent()).path("paymentId").stringValue())
                .isEqualTo("9007199254740993");
        var request = payments.takeRequest();
        assertThat(request.path()).isEqualTo("/api/v1/payments");
        assertThat(request.header("Authorization")).isEqualTo("Bearer fixture-payment");
        assertThat(json.readTree(request.body()).path("clientIdempotencyKey").stringValue())
                .matches("[0-9a-f]{8}-[0-9a-f]{4}-7[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}");
        assertThat(coordinator.count()).isZero();
        assertThat(payments.count()).isEqualTo(1);
    }

    @Test
    void invalidArgumentsAndUnknownToolsDoNotCallRest() throws Exception {
        for (var change : Map.<String, Object>of("regionId", 16, "instanceId", "bad-uuid", "extra", true).entrySet()) {
            var arguments = new LinkedHashMap<>(owner()); arguments.put(change.getKey(), change.getValue());
            var result = call("acquire_worker", arguments);
            assertThat(result.isError()).isTrue();
            assertThat(text(result)).contains("INVALID_ARGUMENTS", "not_attempted");
            assertThat(result.structuredContent()).isNull();
        }
        assertThat(call("get_product", Map.of("productId", "../unsafe")).isError()).isTrue();
        assertThat(call("acquire_worker", Map.of()).isError()).isTrue();
        try {
            var result = call("not_a_tool", Map.of());
            assertThat(result.isError()).isTrue();
        }
        catch (java.util.concurrent.ExecutionException sdkProtocolError) {
            assertThat(sdkProtocolError.getCause()).isInstanceOf(io.modelcontextprotocol.spec.McpError.class);
        }
        assertThat(coordinator.count()).isZero();
        assertThat(payments.count()).isZero();
    }

    @Test
    void sanitizesFailuresAndRejectsUnexpectedResponseTypes() throws Exception {
        coordinator.enqueue(new IndependentHttpMock.Reply(400, "canary-private-response", null,
                IndependentHttpMock.Mode.NORMAL));
        var result = call("acquire_worker", owner());
        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("UPSTREAM_HTTP_ERROR", "unknown").doesNotContain("canary-private-response");
        assertThat(result.structuredContent()).isNull();
        coordinator.enqueue(IndependentHttpMock.Reply.json("{\"released\":\"true\",\"epoch\":1}"));
        var key = new LinkedHashMap<>(owner()); key.put("workerId", 42); key.put("epoch", 1);
        result = call("release_worker", key);
        assertThat(result.isError()).isTrue();
        assertThat(text(result)).contains("UPSTREAM_INVALID_RESPONSE");
        assertThat(result.structuredContent()).isNull();
        assertThat(coordinator.count()).isEqualTo(2);
    }

    @Test
    void rejectsUnapprovedOriginBeforeExecution() throws Exception {
        var request = HttpRequest.newBuilder(URI.create(origin + "/worker-coordinator/mcp"))
                .timeout(Duration.ofSeconds(2)).header("Origin", "http://evil.example").GET().build();
        var response = HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.discarding());
        assertThat(response.statusCode()).isEqualTo(403);
        assertThat(coordinator.count()).isZero();
        assertThat(payments.count()).isZero();
    }

    private void check(String name, Map<String, Object> arguments, String method, String path,
            Map<String, Object> body, Map<String, Object> output) throws Exception {
        var upstream = new LinkedHashMap<>(output); upstream.put("privateBackendField", "canary-private-response");
        upstream.put("unusedNullableField", null);
        coordinator.enqueue(IndependentHttpMock.Reply.json(json.writeValueAsString(upstream)));
        var result = call(name, arguments);
        assertThat(result.isError()).isFalse();
        assertThat(json.readTree(text(result))).isEqualTo(json.valueToTree(output));
        assertThat((tools.jackson.databind.JsonNode) json.valueToTree(result.structuredContent()))
                .isEqualTo(json.valueToTree(output));
        assertThat(text(result)).doesNotContain("canary-private-response");
        var request = coordinator.takeRequest();
        assertThat(request.method()).isEqualTo(method); assertThat(request.path()).isEqualTo(path);
        assertThat(request.header("Authorization")).isEqualTo("Bearer fixture-coordinator");
        if (body == null) assertThat(request.body()).isEmpty();
        else assertThat(json.readTree(request.body())).isEqualTo(json.valueToTree(body));
    }

    private McpSchema.CallToolResult call(String name, Map<String, Object> arguments) throws Exception {
        return client.callTool(new McpSchema.CallToolRequest(name, arguments, null)).toFuture().get(4, TimeUnit.SECONDS);
    }

    private String text(McpSchema.CallToolResult result) {
        return ((McpSchema.TextContent) result.content().getFirst()).text();
    }

    private Map<String, Object> owner() {
        return Map.of("productId", "p", "serviceId", "s", "workerTypeId", "w", "regionId", 0,
                "instanceId", "00000000-0000-0000-0000-000000000001",
                "registrationId", "00000000-0000-0000-0000-000000000002");
    }
}
