package dev.mcp.gateway.catalog;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import dev.mcp.gateway.config.GatewayProperties;
import dev.mcp.gateway.rest.RestBinding;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** Strict supported catalog subset, read once before registration. No upstream HTTP calls. */
public final class FileCatalogLoader {

    private static final int MAX_CATALOG_BYTES = 1024 * 1024;
    private final JsonMapper json = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();

    public List<ToolDefinition> load(GatewayProperties properties, ResourceLoader resources, Environment environment) {
        require(properties.catalogLocation().startsWith("classpath:") || properties.catalogLocation().startsWith("file:"),
                "catalog-location must be classpath: or file:");
        require("worker-coordinator".equals(environment.getProperty("spring.ai.mcp.server.name"))
                && "/worker-coordinator/mcp".equals(environment.getProperty("spring.ai.mcp.server.streamable-http.mcp-endpoint")),
                "server name and fixed endpoint must agree with worker-coordinator");
        try (var input = resources.getResource(properties.catalogLocation()).getInputStream()) {
            byte[] bytes = input.readNBytes(MAX_CATALOG_BYTES + 1);
            require(bytes.length <= MAX_CATALOG_BYTES, "catalog exceeds size limit");
            var definitions = validate(json.readTree(bytes));
            require(definitions.stream().allMatch(tool -> properties.backends().containsKey(tool.binding().backendRef())),
                    "catalog references an unconfigured backend");
            return definitions;
        }
        catch (IOException ex) {
            throw new IllegalArgumentException("Catalog could not be read; check the configured resource");
        }
        catch (tools.jackson.core.JacksonException ex) {
            throw new IllegalArgumentException("Catalog must contain valid JSON with unique keys");
        }
    }

    private List<ToolDefinition> validate(JsonNode root) {
        fields(root, Set.of("schemaVersion", "servers"), Set.of());
        require(root.path("schemaVersion").isIntegralNumber(), "schemaVersion must be an integer");
        int version = root.path("schemaVersion").intValue();
        require(root.path("schemaVersion").bigIntegerValue().equals(java.math.BigInteger.valueOf(version))
                && (version == 1 || version == 2 || version == 3), "only catalog versions 1, 2 and 3 are supported");
        var servers = root.path("servers");
        require(servers.isArray() && servers.size() == 1, "exactly one server is supported");
        var server = servers.get(0);
        fields(server, Set.of("id", "tools"), Set.of());
        require("worker-coordinator".equals(text(server, "id")), "unsupported server id");
        var tools = server.path("tools");
        require(tools.isArray() && tools.size() >= 1 && tools.size() <= (version == 3 ? 32 : 1),
                "invalid tool count for catalog version");
        var definitions = new java.util.ArrayList<ToolDefinition>();
        var names = new java.util.HashSet<String>();
        for (var tool : tools) {
            var definition = validateTool(tool, version);
            require(names.add(definition.name()), "duplicate tool name");
            definitions.add(definition);
        }
        return List.copyOf(definitions);
    }

    private ToolDefinition validateTool(JsonNode tool, int version) {
        fields(tool, Set.of("name", "description", "inputSchema", "outputSchema", "annotations", "binding"), Set.of());
        String name = text(tool, "name");
        require(name.matches("[A-Za-z0-9_.-]{1,128}"), "invalid tool name");
        String description = text(tool, "description");
        require(!description.isBlank(), "tool description is required");
        String outputField = version == 1 ? "workerId" : "paymentId";
        JsonNode expectedInput = json.readTree("{\"type\":\"object\",\"properties\":{},\"additionalProperties\":false}");
        JsonNode expectedOutput = json.readTree("{\"type\":\"object\",\"properties\":{\"" + outputField
                + "\":{\"type\":\"string\",\"minLength\":1}},\"required\":[\"" + outputField
                + "\"],\"additionalProperties\":false}");
        if (version < 3) {
            require(expectedInput.equals(tool.path("inputSchema")) && expectedOutput.equals(tool.path("outputSchema")),
                    "unsupported input or output schema");
        }
        else {
            FlatObjectSchema.validateDefinition(tool.path("inputSchema"));
            FlatObjectSchema.validateDefinition(tool.path("outputSchema"));
        }
        var annotations = tool.path("annotations");
        fields(annotations, Set.of("readOnlyHint", "destructiveHint", "idempotentHint", "openWorldHint"), Set.of());
        Map<String, Boolean> hints = new LinkedHashMap<>();
        annotations.properties().forEach(entry -> {
            require(entry.getValue().isBoolean(), "annotations must be booleans");
            hints.put(entry.getKey(), entry.getValue().booleanValue());
        });
        require(version == 3 || !hints.get("readOnlyHint") && !hints.get("destructiveHint")
                && !hints.get("idempotentHint") && hints.get("openWorldHint"),
                "tool must advertise the supported allocation hints");
        var binding = tool.path("binding");
        fields(binding, version == 3 ? Set.of("type", "backendRef", "method", "path")
                : Set.of("type", "backendRef", "method", "path", "responseIdPointer"),
                version == 1 ? Set.of("requestBody") : version == 2 ? Set.of("requestBody", "requestKeyField")
                        : Set.of("requestBody", "requestKeyField", "responseIdPointer", "bodyArguments", "pathArguments"));
        String keyField = binding.has("requestKeyField") ? text(binding, "requestKeyField") : null;
        require(version == 3 || "worker-coordinator".equals(text(binding, "backendRef")),
                "legacy catalogs support only the worker-coordinator backend");
        require(version != 2 || "clientIdempotencyKey".equals(keyField), "payment catalog requires a UUIDv7 request key");
        RestBinding.Method method;
        try {
            method = RestBinding.Method.valueOf(text(binding, "method"));
        }
        catch (IllegalArgumentException ex) {
            throw new IllegalArgumentException("only GET and POST binding methods are supported");
        }
        Map<String, String> bodyArguments = mappings(binding, "bodyArguments");
        Map<String, String> pathArguments = mappings(binding, "pathArguments");
        if (version == 3) {
            var inputFields = tool.path("inputSchema").path("properties");
            var mapped = new java.util.HashSet<>(bodyArguments.values());
            mapped.addAll(pathArguments.values());
            require(mapped.equals(inputFields.propertyNames()), "every input field must have an explicit REST mapping");
            pathArguments.values().forEach(argument -> require("^[A-Za-z0-9_-][A-Za-z0-9_.-]{0,99}$".equals(
                    inputFields.path(argument).path("pattern").asString()), "path arguments require safe identifier schemas"));
            require(hints.get("readOnlyHint") == (method == RestBinding.Method.GET), "readOnlyHint must match HTTP method");
            if (binding.has("responseIdPointer")) {
                require(keyField != null && expectedOutput.equals(tool.path("outputSchema"))
                        && inputFields.isEmpty(), "ID projection is supported only for the payment tool in version 3");
                outputField = "paymentId";
            }
            else {
                require(keyField == null, "object responses must not configure a payment request key");
                outputField = null;
            }
        }
        var rest = new RestBinding(text(binding, "type"), text(binding, "backendRef"), method, text(binding, "path"),
                binding.has("requestBody") ? json.writeValueAsString(binding.get("requestBody")) : null,
                RestBinding.pointer(binding.has("responseIdPointer") ? text(binding, "responseIdPointer") : ""),
                keyField, bodyArguments, pathArguments, version == 3 && outputField == null);
        return new ToolDefinition(name, description, immutableMap(tool.path("inputSchema")),
                immutableMap(tool.path("outputSchema")), Map.copyOf(hints), rest, outputField);
    }

    private Map<String, String> mappings(JsonNode binding, String field) {
        if (!binding.has(field)) {
            return Map.of();
        }
        var node = binding.get(field);
        require(node.isObject(), "argument mappings must be objects");
        var mappings = new LinkedHashMap<String, String>();
        node.properties().forEach(entry -> {
            require(entry.getValue().isString(), "argument mappings must name input fields");
            mappings.put(entry.getKey(), entry.getValue().stringValue());
        });
        return Map.copyOf(mappings);
    }

    private void fields(JsonNode node, Set<String> required, Set<String> optional) {
        require(node.isObject() && node.propertyNames().containsAll(required), "catalog object is missing required fields");
        require(node.propertyNames().stream().allMatch(key -> required.contains(key) || optional.contains(key)),
                "catalog contains unsupported fields");
    }

    private String text(JsonNode node, String key) {
        require(node.path(key).isString(), "catalog field " + key + " must be a string");
        return node.path(key).stringValue();
    }

    private Map<String, Object> immutableMap(JsonNode node) {
        Map<String, Object> value = json.convertValue(node, new TypeReference<Map<String, Object>>() { });
        Map<String, Object> frozen = new LinkedHashMap<>();
        value.forEach((key, item) -> frozen.put(key, freeze(item)));
        return Map.copyOf(frozen);
    }

    private Object freeze(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> frozen = new LinkedHashMap<>();
            map.forEach((key, item) -> frozen.put((String) key, freeze(item)));
            return Map.copyOf(frozen);
        }
        if (value instanceof List<?> list) {
            return list.stream().map(this::freeze).toList();
        }
        return value;
    }

    private void require(boolean valid, String message) {
        if (!valid) {
            throw new IllegalArgumentException(message);
        }
    }
}
