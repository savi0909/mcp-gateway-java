package dev.mcp.gateway.mcp;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import dev.mcp.gateway.catalog.FileCatalogLoader;
import dev.mcp.gateway.catalog.FlatObjectSchema;
import dev.mcp.gateway.catalog.ToolDefinition;
import dev.mcp.gateway.config.GatewayProperties;
import dev.mcp.gateway.rest.AllocationResult;
import dev.mcp.gateway.rest.RestBindingExecutor;
import dev.mcp.gateway.security.Caller;
import dev.mcp.gateway.security.TenantPolicy;
import org.springframework.beans.factory.ObjectProvider;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.ai.mcp.customizer.McpAsyncServerCustomizer;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(prefix = "spring.ai.mcp.server", name = "enabled", havingValue = "true")
public class McpToolRegistrationConfiguration {

    private static final Logger LOG = LoggerFactory.getLogger(McpToolRegistrationConfiguration.class);
    private final JsonMapper json = JsonMapper.builder().build();

    @Bean
    McpAsyncServerCustomizer gatewayInputValidation() {
        // The supported catalog subset is enforced below, with sanitized application errors.
        return server -> server.validateToolInputs(false);
    }

    @Bean
    List<ToolDefinition> toolDefinitions(GatewayProperties properties, ResourceLoader resources, Environment environment) {
        if (!"127.0.0.1".equals(environment.getProperty("server.address"))) {
            throw new IllegalArgumentException("MCP demo must listen on 127.0.0.1");
        }
        return new FileCatalogLoader().load(properties, resources, environment);
    }

    @Bean
    List<McpServerFeatures.AsyncToolSpecification> catalogTools(List<ToolDefinition> definitions, RestBindingExecutor executor,
            ObjectProvider<TenantPolicy> policy) {
        return definitions.stream().map(definition -> specification(definition, executor, policy.getIfAvailable())).toList();
    }

    private McpServerFeatures.AsyncToolSpecification specification(ToolDefinition definition, RestBindingExecutor executor, TenantPolicy policy) {
        var hints = definition.annotations();
        var annotations = new McpSchema.ToolAnnotations(null, hints.get("readOnlyHint"), hints.get("destructiveHint"),
                hints.get("idempotentHint"), hints.get("openWorldHint"), null);
        var tool = McpSchema.Tool.builder().name(definition.name()).description(definition.description())
                .inputSchema(definition.inputSchema()).outputSchema(definition.outputSchema()).annotations(annotations).build();
        return McpServerFeatures.AsyncToolSpecification.builder().tool(tool)
                .callHandler((exchange, request) -> invoke(definition, executor, request, exchange, policy)).build();
    }

    @Bean
    McpOriginFilter mcpOriginFilter(GatewayProperties properties) {
        return new McpOriginFilter(properties.allowedOrigins());
    }

    private Mono<McpSchema.CallToolResult> invoke(ToolDefinition definition, RestBindingExecutor executor,
            McpSchema.CallToolRequest request, McpAsyncServerExchange exchange, TenantPolicy policy) {
        return Mono.defer(() -> {
            String correlation = UUID.randomUUID().toString();
            long started = System.nanoTime();
            var outcome = new AtomicReference<>("cancelled");
            Mono<McpSchema.CallToolResult> result;
            var arguments = request.arguments() == null ? Map.<String, Object>of() : request.arguments();
            boolean valid = definition.binding().responseObject()
                    ? FlatObjectSchema.accepts(definition.inputSchema(), arguments) : arguments.isEmpty();
            if (!valid) {
                outcome.set("INVALID_ARGUMENTS");
                result = Mono.just(error("INVALID_ARGUMENTS", "Arguments must match the advertised input schema.",
                        "not_attempted"));
            }
            else if (policy != null && (!(exchange.transportContext().get(Caller.ATTRIBUTE) instanceof Caller caller)
                    || !policy.allows(caller, definition.name(), arguments))) {
                outcome.set("ACCESS_DENIED");
                result = Mono.just(error("ACCESS_DENIED", "This operation is not permitted.", "not_attempted"));
            }
            else {
                result = executor.execute(definition.binding().resolve(arguments)).map(allocation -> {
                    if (allocation instanceof AllocationResult.ObjectSuccess success) {
                        var output = FlatObjectSchema.project(definition.outputSchema(), success.fields(), true);
                        outcome.set("success");
                        return McpSchema.CallToolResult.builder().isError(false).structuredContent(output)
                                .addTextContent(json.writeValueAsString(output)).build();
                    }
                    if (allocation instanceof AllocationResult.Success success) {
                        var output = Map.of(definition.outputField(), success.id());
                        outcome.set("success");
                        return McpSchema.CallToolResult.builder().isError(false).structuredContent(output)
                                .addTextContent(json.writeValueAsString(output)).build();
                    }
                    var failure = (AllocationResult.Failure) allocation;
                    outcome.set(failure.code().name());
                    return error(failure.code().name(), failure.message(), failure.allocationOutcome());
                }).onErrorResume(failure -> {
                    outcome.set("UPSTREAM_INVALID_RESPONSE");
                    return Mono.just(error("UPSTREAM_INVALID_RESPONSE", "The upstream operation could not be completed.", "unknown"));
                });
            }
            return result.doFinally(signal -> LOG.info("Tool invocation correlation={} tool={} durationMs={} outcome={}",
                    correlation, definition.name(), (System.nanoTime() - started) / 1_000_000, outcome.get()));
        });
    }

    private McpSchema.CallToolResult error(String code, String message, String outcome) {
        return McpSchema.CallToolResult.builder().isError(true)
                .addTextContent(json.writeValueAsString(Map.of("code", code, "message", message, "allocationOutcome", outcome)))
                .build();
    }
}
