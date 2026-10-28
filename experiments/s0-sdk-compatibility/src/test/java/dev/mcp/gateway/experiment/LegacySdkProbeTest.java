package dev.mcp.gateway.experiment;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.server.McpServer;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.server.transport.HttpServletStreamableServerTransportProvider;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.assertThat;

/** Official client against an independent SDK-owned legacy Boot server. */
class LegacySdkProbeTest {
    @Test void previewRetainsLegacyInitializationListingAndOrdinaryRead() {
        var boot = new SpringApplication(LegacyConfiguration.class);
        boot.setWebApplicationType(WebApplicationType.SERVLET);
        boot.setDefaultProperties(Map.of("server.port", "0", "server.address", "127.0.0.1",
                "spring.main.banner-mode", "off", "logging.level.root", "ERROR"));
        try (var app = (ServletWebServerApplicationContext) boot.run()) {
            var calls = app.getBean(AtomicInteger.class);
            var transport = HttpClientStreamableHttpTransport.builder("http://127.0.0.1:" + app.getWebServer().getPort())
                    .endpoint(ModernSdkProbeTest.ENDPOINT).connectTimeout(Duration.ofSeconds(3)).build();
            var client = McpClient.async(transport).requestTimeout(Duration.ofSeconds(3)).build();
            try {
                assertThat(client.initialize().block(Duration.ofSeconds(5)).protocolVersion()).isEqualTo("2025-11-25");
                assertThat(client.listTools().block(Duration.ofSeconds(5)).tools()).extracting(McpSchema.Tool::name)
                        .containsExactly("ordinary_read");
                assertThat(calls.get()).isZero();
                var result = client.callTool(new McpSchema.CallToolRequest("ordinary_read", Map.of(), null))
                        .block(Duration.ofSeconds(5));
                assertThat(result.isError()).isFalse();
                assertThat(result.structuredContent()).isEqualTo(Map.of("value", "fixture-read"));
                assertThat(calls.get()).isEqualTo(1);
            } finally { client.closeGracefully().block(Duration.ofSeconds(5)); }
        }
    }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class LegacyConfiguration {
        @Bean AtomicInteger calls() { return new AtomicInteger(); }
        @Bean HttpServletStreamableServerTransportProvider transport() {
            return HttpServletStreamableServerTransportProvider.builder().mcpEndpoint(ModernSdkProbeTest.ENDPOINT).build();
        }
        @Bean ServletRegistrationBean<HttpServletStreamableServerTransportProvider> servlet(HttpServletStreamableServerTransportProvider transport) {
            return new ServletRegistrationBean<>(transport, ModernSdkProbeTest.ENDPOINT);
        }
        @Bean(destroyMethod = "close") io.modelcontextprotocol.server.McpAsyncServer server(
                HttpServletStreamableServerTransportProvider transport, AtomicInteger calls) {
            var tool = McpSchema.Tool.builder().name("ordinary_read").description("Fixture ordinary read")
                    .inputSchema(Map.of("type", "object", "additionalProperties", false)).build();
            var specification = McpServerFeatures.AsyncToolSpecification.builder().tool(tool).callHandler((exchange, request) -> {
                calls.incrementAndGet();
                return Mono.just(McpSchema.CallToolResult.builder().structuredContent(Map.of("value", "fixture-read"))
                        .isError(false).build());
            }).build();
            return McpServer.async(transport).serverInfo("s0-legacy", "0.1.0")
                    .capabilities(McpSchema.ServerCapabilities.builder().tools(false).build()).tools(specification).build();
        }
    }
}
