package dev.mcp.gateway.demo;

import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import tools.jackson.databind.json.JsonMapper;

/** Standalone SDK client. Discovery by default; explicit --allocate creates sample payments. */
public final class McpSmokeClient {

    private McpSmokeClient() {
    }

    public static void main(String[] args) throws Exception {
        boolean allocate = false;
        int count = 1;
        String origin = "http://127.0.0.1:8080";
        for (String arg : args) {
            if (arg.equals("--allocate")) {
                allocate = true;
            }
            else if (arg.startsWith("--count=")) {
                count = Integer.parseInt(arg.substring("--count=".length()));
            }
            else if (arg.startsWith("--base-url=")) {
                origin = arg.substring("--base-url=".length());
            }
            else {
                throw new IllegalArgumentException("Supported options: --allocate --count=N --base-url=ORIGIN");
            }
        }
        if (count < 1 || count > 20) {
            throw new IllegalArgumentException("count must be between 1 and 20");
        }
        var builder = HttpClientStreamableHttpTransport.builder(origin).endpoint("/worker-coordinator/mcp");
        String accessToken = System.getenv("MCP_ACCESS_TOKEN");
        if (accessToken != null && !accessToken.isBlank()) {
            if (!accessToken.matches("[A-Za-z0-9._~+/-]+=*")) {
                throw new IllegalArgumentException("MCP_ACCESS_TOKEN has invalid bearer syntax");
            }
            builder.requestBuilder(java.net.http.HttpRequest.newBuilder().header("Authorization", "Bearer " + accessToken));
        }
        var transport = builder.build();
        var client = McpClient.async(transport).requestTimeout(Duration.ofSeconds(12)).build();
        var json = JsonMapper.builder().build();
        try {
            var initialized = client.initialize().toFuture().get(15, TimeUnit.SECONDS);
            System.out.println("Negotiated protocol: " + initialized.protocolVersion());
            var tools = client.listTools().toFuture().get(12, TimeUnit.SECONDS).tools();
            if (tools.isEmpty()) {
                throw new IllegalStateException("Expected catalog-defined tools");
            }
            tools.forEach(tool -> System.out.println("Discovered tool: " + tool.name()));
            if (allocate) {
                var tool = tools.stream().filter(candidate -> candidate.name().equals("create_sample_payment"))
                        .findFirst().orElseGet(() -> {
                            if (tools.size() == 1 && tools.getFirst().inputSchema().get("properties") instanceof Map<?, ?> properties
                                    && properties.isEmpty()
                                    && tools.getFirst().outputSchema().containsKey("required")) {
                                return tools.getFirst();
                            }
                            throw new IllegalArgumentException("--allocate requires an empty-input ID allocation tool");
                        });
                String field = ((java.util.List<?>) tool.outputSchema().get("required")).getFirst().toString();
                var ids = new HashSet<String>();
                for (int i = 0; i < count; i++) {
                    var result = client.callTool(new McpSchema.CallToolRequest(tool.name(), Map.of(), null))
                            .toFuture().get(12, TimeUnit.SECONDS);
                    if (Boolean.TRUE.equals(result.isError())) {
                        throw new IllegalStateException("Gateway returned a sanitized tool error: " + json.writeValueAsString(result.content()));
                    }
                    var text = (McpSchema.TextContent) result.content().getFirst();
                    var textJson = json.readTree(text.text());
                    var structured = json.valueToTree(result.structuredContent());
                    if (!textJson.equals(structured) || !textJson.path(field).isString()) {
                        throw new IllegalStateException("Text and structured output must agree and contain a string ID");
                    }
                    ids.add(textJson.path(field).stringValue());
                }
                System.out.println("Completed allocations: " + count + "; distinct returned IDs: " + ids.size()
                        + "; text/structured content matched. IDs are not printed.");
                if (ids.size() != count) {
                    throw new IllegalStateException("This live sample run returned repeated IDs");
                }
            }
            else {
                System.out.println("Discovery only; no allocation requested.");
            }
        }
        catch (Exception failure) {
            if (accessToken != null && !accessToken.isBlank()) {
                throw new IllegalStateException("Authenticated MCP check failed; inspect sanitized gateway outcomes.");
            }
            throw failure;
        }
        finally {
            try { client.closeGracefully().toFuture().get(5, TimeUnit.SECONDS); }
            catch (Exception failure) { throw new IllegalStateException("MCP client closure failed."); }
        }
    }
}
