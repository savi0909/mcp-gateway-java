package dev.mcp.gateway.admission;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import dev.mcp.gateway.catalog.ToolDefinition;
import dev.mcp.gateway.config.GatewayProperties;
import dev.mcp.gateway.rest.RestBindingExecutor;
import dev.mcp.gateway.security.SecuritySettings;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpStreamableServerTransportProvider;
import org.springframework.ai.mcp.server.webflux.transport.WebFluxStreamableServerTransportProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.*;
import org.springframework.web.reactive.function.server.*;
import reactor.core.publisher.Mono;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@Profile("uc01")
@EnableConfigurationProperties(AdmissionSettings.class)
public class AdmissionConfiguration {
    @Bean(destroyMethod = "close") AdmissionJournal admissionJournal(AdmissionSettings settings, SecuritySettings security) throws IOException {
        AdmissionPolicy.require(security.enabled()); return new AdmissionJournal(settings);
    }
    @Bean AdmissionService admissionService(AdmissionJournal journal, AdmissionSettings settings, GatewayProperties gateway,
            List<ToolDefinition> tools, RestBindingExecutor executor) { return new AdmissionService(journal, settings, gateway, tools, executor); }
    @Bean AdmissionSessionFilter admissionSessionFilter() { return new AdmissionSessionFilter(); }
    @Bean WebFluxStreamableServerTransportProvider enterpriseTransport(@Qualifier("mcpServerJsonMapper") JsonMapper json) {
        return WebFluxStreamableServerTransportProvider.builder().jsonMapper(new JacksonMcpJsonMapper(json))
                .messageEndpoint("/worker-coordinator/mcp").maxSessions(256).sessionIdleTimeout(Duration.ofMinutes(15))
                .contextExtractor(request -> {
                    Object identity = request.attributes().get(AdmissionIdentity.ATTRIBUTE);
                    return identity instanceof AdmissionIdentity ? McpTransportContext.create(Map.of(AdmissionIdentity.ATTRIBUTE, identity)) : McpTransportContext.EMPTY;
                }).build();
    }
    @Bean @Primary McpStreamableServerTransportProvider filteredTransport(WebFluxStreamableServerTransportProvider delegate,
            AdmissionService admission, List<McpServerFeatures.AsyncToolSpecification> specifications) {
        return new FilteredMcpTransport(delegate, admission, specifications);
    }
    @Bean RouterFunction<ServerResponse> admissionAdministration(AdmissionService admission) {
        return RouterFunctions.route()
                .POST("/control/changes", request -> request.bodyToMono(AdmissionService.Change.class)
                    .flatMap(change -> admission.change(identity(request), change)).flatMap(result -> ServerResponse.ok().bodyValue(result))
                    .onErrorResume(this::failure))
                .GET("/control/audit", request -> admission.auditExport(identity(request)).flatMap(result -> ServerResponse.ok().bodyValue(result))
                    .onErrorResume(this::failure)).build();
    }
    private AdmissionIdentity identity(ServerRequest request) {
        Object raw = request.attributes().get(AdmissionIdentity.ATTRIBUTE);
        if (!(raw instanceof AdmissionIdentity i)) throw AdmissionFailure.denied(); return i;
    }
    private Mono<ServerResponse> failure(Throwable error) {
        String code = error instanceof AdmissionFailure f ? f.code() : "INVALID_REQUEST";
        return ServerResponse.status("VERIFICATION_UNAVAILABLE".equals(code) ? 503 : "ACCESS_DENIED".equals(code) ? 403 : 400).bodyValue(Map.of("code", code));
    }
}
