package dev.mcp.gateway;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;

import dev.mcp.gateway.config.GatewayProperties;
import dev.mcp.gateway.support.IndependentHttpMock;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.core.env.Environment;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ScaffoldStartupTest {

    private static final IndependentHttpMock BACKEND = startBackend();

    @DynamicPropertySource
    static void backendProperties(DynamicPropertyRegistry registry) {
        registry.add("gateway.backends.worker-coordinator.base-url", () -> BACKEND.origin().toString());
    }

    @AfterAll
    static void stopBackend() {
        BACKEND.close();
    }

    private static IndependentHttpMock startBackend() {
        try {
            return new IndependentHttpMock();
        }
        catch (java.io.IOException ex) {
            throw new java.io.UncheckedIOException(ex);
        }
    }

    @LocalServerPort
    private int port;

    @Autowired
    private Environment environment;

    @Autowired
    private GatewayProperties properties;

    @Test
    void startsLocallyWithoutExposingAnUnimplementedMcpEndpoint() throws Exception {
        assertThat(environment.getProperty("server.address")).isEqualTo("127.0.0.1");
        assertThat(environment.getProperty("spring.ai.mcp.server.enabled", Boolean.class)).isFalse();
        assertThat(getClass().getResource("/catalog/worker-catalog.json")).isNotNull();
        assertThat(properties.workerCoordinator().baseUrl()).isEqualTo(BACKEND.origin());
        assertThat(BACKEND.count()).isZero();

        try (var client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(2)).build()) {
            var request = HttpRequest.newBuilder(
                    URI.create("http://127.0.0.1:" + port + "/worker-coordinator/mcp"))
                    .timeout(Duration.ofSeconds(5))
                    .GET()
                    .build();
            var response = client.send(request, HttpResponse.BodyHandlers.discarding());
            assertThat(response.statusCode()).isEqualTo(404);
            assertThat(BACKEND.count()).isZero();
        }
    }
}
