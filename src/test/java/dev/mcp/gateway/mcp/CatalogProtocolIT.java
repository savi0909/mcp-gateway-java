package dev.mcp.gateway.mcp;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import dev.mcp.gateway.McpGatewayApplication;
import dev.mcp.gateway.support.IndependentHttpMock;
import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.McpAsyncClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.*;

class CatalogProtocolIT {
    private final JsonMapper json = JsonMapper.builder().build();

    @Test
    void legacyDiscoveryExactIdsDuplicateProjectionAndBoundedErrors() throws Exception {
        try (var backend = new IndependentHttpMock(); var app = start(backend, "classpath:catalog/worker-catalog.json")) {
            var client = connect(app);
            try {
                var tools = client.listTools().toFuture().get(4, TimeUnit.SECONDS).tools();
                assertThat(tools).hasSize(1);
                assertThat(tools.getFirst().name()).isEqualTo("allocate_worker_id");
                assertThat(tools.getFirst().annotations().readOnlyHint()).isFalse();
                assertThat(tools.getFirst().annotations().destructiveHint()).isFalse();
                assertThat(tools.getFirst().annotations().idempotentHint()).isFalse();
                assertThat(tools.getFirst().annotations().openWorldHint()).isTrue();
                var definition = json.readTree(Files.readString(Path.of("examples/worker-catalog.json")))
                        .path("servers").get(0).path("tools").get(0);
                assertThat(tools.getFirst().description()).isEqualTo(definition.path("description").stringValue());
                assertThat((tools.jackson.databind.JsonNode) json.valueToTree(tools.getFirst().inputSchema()))
                        .isEqualTo(definition.path("inputSchema"));
                assertThat((tools.jackson.databind.JsonNode) json.valueToTree(tools.getFirst().outputSchema()))
                        .isEqualTo(definition.path("outputSchema"));
                assertThat(json.writeValueAsString(tools)).doesNotContain("binding", "backendRef", backend.origin().toString());
                assertThat(backend.count()).isZero();
                for (String id : new String[]{"000123", "000123", "9007199254740993"}) {
                    backend.enqueue(IndependentHttpMock.Reply.json("{\"workerId\":\"" + id + "\",\"private\":\"canary\"}"));
                    var result = call(client, "allocate_worker_id");
                    assertThat(result.isError()).isFalse();
                    assertThat(json.readTree(text(result))).isEqualTo(json.valueToTree(Map.of("workerId", id)));
                    assertThat((tools.jackson.databind.JsonNode) json.valueToTree(result.structuredContent()))
                            .isEqualTo(json.readTree(text(result)));
                    var request = backend.takeRequest();
                    assertThat(request.method()).isEqualTo("POST");
                    assertThat(request.path()).isEqualTo("/workers/ids");
                    assertThat(request.body()).isEqualTo("{}");
                }
                int before = backend.count();
                for (var reply : new IndependentHttpMock.Reply[]{
                        new IndependentHttpMock.Reply(302, "canary", backend.origin() + "/redirect", IndependentHttpMock.Mode.NORMAL),
                        new IndependentHttpMock.Reply(400, "canary", null, IndependentHttpMock.Mode.NORMAL),
                        IndependentHttpMock.Reply.json("{\"workerId\":1.2}"),
                        IndependentHttpMock.Reply.json("malformed-canary"),
                        IndependentHttpMock.Reply.json("{\"workerId\":\"" + "x".repeat(70000) + "\"}")}) {
                    backend.enqueue(reply);
                    var result = call(client, "allocate_worker_id");
                    assertThat(result.isError()).isTrue();
                    assertThat(result.structuredContent()).isNull();
                    assertThat(text(result)).contains("unknown").doesNotContain("canary", backend.origin().toString());
                    assertThat(backend.count()).isEqualTo(++before);
                }
            }
            finally { client.closeGracefully().toFuture().get(5, TimeUnit.SECONDS); }
        }
    }

    @Test
    void externalCatalogChangesRequireFreshContextAndSupportGetAndNestedInteger() throws Exception {
        Path directory = Files.createDirectories(Path.of("target", "catalog-lifecycle"));
        Path file = Files.createTempFile(directory, "catalog-", ".json"); // retained for inspection; never deleted
        ObjectNode catalog = (ObjectNode) json.readTree(Files.readString(Path.of("examples/worker-catalog.json")));
        var tool = (ObjectNode) catalog.path("servers").get(0).path("tools").get(0);
        tool.put("name", "first_name");
        Files.writeString(file, json.writeValueAsString(catalog));
        try (var backend = new IndependentHttpMock()) {
            try (var app = start(backend, file.toUri().toString())) {
                var client = connect(app);
                try {
                    tool.put("name", "second_name").put("description", "Changed at restart");
                    var binding = (ObjectNode) tool.path("binding");
                    binding.put("method", "GET").put("path", "/nested").put("responseIdPointer", "/data/id");
                    binding.remove("requestBody");
                    Files.writeString(file, json.writeValueAsString(catalog));
                    assertThat(client.listTools().toFuture().get(4, TimeUnit.SECONDS).tools().getFirst().name()).isEqualTo("first_name");
                    assertThat(backend.count()).isZero();
                }
                finally { client.closeGracefully().toFuture().get(5, TimeUnit.SECONDS); }
            }
            try (var app = start(backend, file.toUri().toString())) {
                var client = connect(app);
                try {
                    assertThat(client.listTools().toFuture().get(4, TimeUnit.SECONDS).tools().getFirst().description())
                            .isEqualTo("Changed at restart");
                    backend.enqueue(IndependentHttpMock.Reply.json("{\"data\":{\"id\":9007199254740993}}"));
                    assertThat(text(call(client, "second_name"))).contains("9007199254740993");
                    var request = backend.takeRequest();
                    assertThat(request.method()).isEqualTo("GET");
                    assertThat(request.path()).isEqualTo("/nested");
                    assertThat(request.body()).isEmpty();
                }
                finally { client.closeGracefully().toFuture().get(5, TimeUnit.SECONDS); }
            }
        }
    }

    @Test
    void invalidAndMissingCatalogsPreventBootStartup() throws Exception {
        try (var backend = new IndependentHttpMock()) {
            Path directory = Files.createDirectories(Path.of("target", "invalid-catalogs"));
            var invalidCatalogs = new java.util.ArrayList<>(java.util.List.of("{", "{\"schemaVersion\":1,\"schemaVersion\":1,\"servers\":[]}",
                    "{\"schemaVersion\":99,\"servers\":[]}"));
            for (String mutation : java.util.List.of("native", "extra-server", "extra-tool", "duplicate-name", "schema")) {
                var root = (ObjectNode) json.readTree(Files.readString(Path.of("examples/worker-catalog.json")));
                var servers = (tools.jackson.databind.node.ArrayNode) root.path("servers");
                var tools = (tools.jackson.databind.node.ArrayNode) servers.get(0).path("tools");
                var tool = (ObjectNode) tools.get(0);
                switch (mutation) {
                    case "native" -> ((ObjectNode) tool.path("binding")).put("type", "MCP");
                    case "extra-server" -> servers.add(servers.get(0).deepCopy());
                    case "extra-tool" -> { var extra = (ObjectNode) tool.deepCopy(); extra.put("name", "other_tool"); tools.add(extra); }
                    case "duplicate-name" -> { root.put("schemaVersion", 3); tools.add(tool.deepCopy()); }
                    case "schema" -> ((ObjectNode) tool.path("outputSchema").path("properties").path("workerId")).put("type", "number");
                }
                invalidCatalogs.add(json.writeValueAsString(root));
            }
            for (String content : invalidCatalogs) {
                Path file = Files.createTempFile(directory, "invalid-", ".json");
                Files.writeString(file, content);
                assertThatThrownBy(() -> start(backend, file.toUri().toString())).isInstanceOf(RuntimeException.class);
            }
            assertThatThrownBy(() -> start(backend, "file:target/no-such-catalog.json")).isInstanceOf(RuntimeException.class);
            assertThat(backend.count()).isZero();
        }
    }

    @Test void backendOutageDoesNotPreventStartupOrSdkDiscovery() throws Exception {
        try (var backend = new IndependentHttpMock()) {
            backend.close();
            try (var app = start(backend, "classpath:catalog/worker-catalog.json")) {
                var client = connect(app);
                try {
                    assertThat(client.listTools().toFuture().get(4, TimeUnit.SECONDS).tools()).hasSize(1);
                    assertThat(backend.count()).isZero();
                }
                finally { client.closeGracefully().toFuture().get(3, TimeUnit.SECONDS); }
            }
        }
    }

    private ConfigurableApplicationContext start(IndependentHttpMock backend, String catalog) {
        return new SpringApplicationBuilder(McpGatewayApplication.class).run("--spring.profiles.active=mock", "--server.port=0",
                "--gateway.catalog-location=" + catalog, "--gateway.backends.worker-coordinator.base-url=" + backend.origin(),
                "--gateway.upstream.connect-timeout=200ms", "--gateway.upstream.deadline=800ms");
    }
    private McpAsyncClient connect(ConfigurableApplicationContext app) throws Exception {
        String origin = "http://127.0.0.1:" + ((WebServerApplicationContext) app).getWebServer().getPort();
        var client = McpClient.async(HttpClientStreamableHttpTransport.builder(origin).endpoint("/worker-coordinator/mcp").build())
                .requestTimeout(Duration.ofSeconds(4)).build();
        client.initialize().toFuture().get(5, TimeUnit.SECONDS);
        return client;
    }
    private McpSchema.CallToolResult call(McpAsyncClient client, String name) throws Exception {
        return client.callTool(new McpSchema.CallToolRequest(name, Map.of(), null)).toFuture().get(4, TimeUnit.SECONDS);
    }
    private String text(McpSchema.CallToolResult result) { return ((McpSchema.TextContent) result.content().getFirst()).text(); }
}
