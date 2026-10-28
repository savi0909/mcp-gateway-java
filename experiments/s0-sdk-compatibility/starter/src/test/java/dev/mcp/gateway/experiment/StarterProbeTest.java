package dev.mcp.gateway.experiment;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.server.reactive.context.ReactiveWebServerApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/** Checks actual starter/BOM wiring, separately from the direct modern servlet probe. */
class StarterProbeTest {
    @Test void starterRegistersLegacyServerAndOfficialClientReadWorks() {
        var boot = new SpringApplication(StarterConfiguration.class);
        boot.setWebApplicationType(WebApplicationType.REACTIVE);
        boot.setDefaultProperties(Map.ofEntries(
                Map.entry("server.port", "0"), Map.entry("server.address", "127.0.0.1"),
                Map.entry("spring.main.banner-mode", "off"), Map.entry("logging.level.root", "ERROR"),
                Map.entry("spring.ai.mcp.server.protocol", "STREAMABLE"), Map.entry("spring.ai.mcp.server.type", "ASYNC"),
                Map.entry("spring.ai.mcp.server.streamable-http.mcp-endpoint", "/worker-coordinator/mcp"),
                Map.entry("spring.ai.mcp.server.annotation-scanner.enabled", "false"),
                Map.entry("spring.ai.mcp.server.tool-callback-converter", "false"),
                Map.entry("spring.ai.mcp.server.capabilities.resource", "false"),
                Map.entry("spring.ai.mcp.server.capabilities.prompt", "false"),
                Map.entry("spring.ai.mcp.server.capabilities.completion", "false")));
        try (var app = (ReactiveWebServerApplicationContext) boot.run()) {
            var calls = app.getBean(AtomicInteger.class);
            var transport = HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + app.getWebServer().getPort())
                    .endpoint("/worker-coordinator/mcp").connectTimeout(Duration.ofSeconds(3)).build();
            var client = McpClient.async(transport).requestTimeout(Duration.ofSeconds(3)).build();
            try {
                assertThat(client.initialize().block(Duration.ofSeconds(5)).protocolVersion()).isEqualTo("2025-11-25");
                assertThat(client.listTools().block(Duration.ofSeconds(5)).tools()).extracting(McpSchema.Tool::name)
                        .containsExactly("ordinary_read");
                assertThat(calls.get()).isZero();
                assertThat(client.callTool(new McpSchema.CallToolRequest("ordinary_read", Map.of(), null))
                        .block(Duration.ofSeconds(5)).isError()).isFalse();
                assertThat(calls.get()).isEqualTo(1);
                assertThat(app.getBeansOfType(io.modelcontextprotocol.server.McpAsyncServer.class)).hasSize(1);
            } finally { client.closeGracefully().block(Duration.ofSeconds(5)); }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class StarterConfiguration {
        @Bean AtomicInteger calls() { return new AtomicInteger(); }
        @Bean List<McpServerFeatures.AsyncToolSpecification> tools(AtomicInteger calls) {
            var tool = McpSchema.Tool.builder().name("ordinary_read").description("Fixture ordinary read")
                    .inputSchema(Map.of("type", "object", "additionalProperties", false)).build();
            return List.of(McpServerFeatures.AsyncToolSpecification.builder().tool(tool).callHandler((exchange, request) -> {
                calls.incrementAndGet();
                return Mono.just(McpSchema.CallToolResult.builder().structuredContent(Map.of("value", "fixture-read"))
                        .isError(false).build());
            }).build());
        }
    }
}
