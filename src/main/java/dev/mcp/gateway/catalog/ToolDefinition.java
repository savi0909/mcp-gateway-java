package dev.mcp.gateway.catalog;

import java.util.Map;

import dev.mcp.gateway.rest.RestBinding;

/** Loader supplies deeply immutable public schema maps and a separate private binding. */
public record ToolDefinition(String name, String description, Map<String, Object> inputSchema,
        Map<String, Object> outputSchema, Map<String, Boolean> annotations,
        RestBinding binding, String outputField) {
}
