package dev.mcp.gateway.catalog;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import dev.mcp.gateway.config.GatewayProperties;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import org.springframework.mock.env.MockEnvironment;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.*;

class FileCatalogLoaderTest {
    private final JsonMapper json = JsonMapper.builder().build();
    private final MockEnvironment environment = new MockEnvironment()
            .withProperty("spring.ai.mcp.server.name", "worker-coordinator")
            .withProperty("spring.ai.mcp.server.streamable-http.mcp-endpoint", "/worker-coordinator/mcp");

    @Test
    void supportsLegacyAndNewCatalogsWithImmutableMetadata() {
        for (var entry : Map.of("worker-catalog.json", 1, "payment-catalog.json", 1,
                "coordinator-catalog.json", 9, "coordinator-payment-catalog.json", 10).entrySet()) {
            var definitions = load("classpath:catalog/" + entry.getKey(), new DefaultResourceLoader());
            assertThat(definitions).hasSize(entry.getValue());
            assertThatThrownBy(definitions::clear).isInstanceOf(UnsupportedOperationException.class);
            assertThatThrownBy(() -> definitions.getFirst().inputSchema().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
        }
    }

    @Test
    void rejectsDuplicateNamesUnsupportedSchemaAndUnmappedArguments() throws Exception {
        tools.jackson.databind.JsonNode original;
        try (var input = new DefaultResourceLoader()
                .getResource("classpath:catalog/coordinator-catalog.json").getInputStream()) {
            original = json.readTree(input);
        }
        for (String mutation : List.of("duplicate", "schema", "mapping", "path", "server")) {
            var root = original.deepCopy();
            var tools = root.path("servers").get(0).path("tools");
            var tool = (ObjectNode) tools.get(0);
            switch (mutation) {
                case "duplicate" -> ((ObjectNode) tools.get(1)).put("name", tool.path("name").stringValue());
                case "schema" -> ((ObjectNode) tool.path("inputSchema").path("properties").path("productId"))
                        .put("type", "array");
                case "mapping" -> ((ObjectNode) tool.path("binding").path("bodyArguments")).remove("productId");
                case "path" -> ((ObjectNode) tool.path("binding")).put("path", "//evil.example/api");
                case "server" -> ((ObjectNode) root.path("servers").get(0)).put("id", "other");
            }
            var resource = new ByteArrayResource(json.writeValueAsBytes(root));
            assertThatThrownBy(() -> load("file:fixture", new DefaultResourceLoader() {
                @Override
                public Resource getResource(String location) {
                    return resource;
                }
            })).isInstanceOf(IllegalArgumentException.class);
        }
    }

    @Test
    void checksTypesUuidBoundsAndUnknownArgumentsWithoutLosingLargeEpochs() {
        var definition = load("classpath:catalog/coordinator-catalog.json", new DefaultResourceLoader()).stream()
                .filter(tool -> tool.name().equals("renew_worker_lease")).findFirst().orElseThrow();
        var valid = new java.util.LinkedHashMap<String, Object>(Map.of("productId", "p", "serviceId", "s",
                "workerTypeId", "w", "regionId", 0, "workerId", 0, "epoch", 9007199254740993L,
                "instanceId", "00000000-0000-0000-0000-000000000001",
                "registrationId", "00000000-0000-0000-0000-000000000002"));
        assertThat(FlatObjectSchema.accepts(definition.inputSchema(), valid)).isTrue();
        for (var change : Map.<String, Object>of("regionId", 16, "workerId", -1, "epoch", 1.5,
                "instanceId", "1-1-1-1-1", "url", "http://evil.example").entrySet()) {
            var invalid = new java.util.LinkedHashMap<>(valid);
            invalid.put(change.getKey(), change.getValue());
            assertThat(FlatObjectSchema.accepts(definition.inputSchema(), invalid)).isFalse();
        }
    }

    private List<ToolDefinition> load(String location, DefaultResourceLoader resources) {
        var backend = new GatewayProperties.Backend(URI.create("http://127.0.0.1:1234"), "");
        var properties = new GatewayProperties(location, Map.of("worker-coordinator", backend, "payment-api", backend),
                new GatewayProperties.Upstream(Duration.ofMillis(100), Duration.ofSeconds(1), 65536), List.of());
        return new FileCatalogLoader().load(properties, resources, environment);
    }
}
