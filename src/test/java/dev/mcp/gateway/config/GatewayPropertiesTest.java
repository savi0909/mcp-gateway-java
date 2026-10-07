package dev.mcp.gateway.config;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;

class GatewayPropertiesTest {

    private final ApplicationContextRunner context = new ApplicationContextRunner()
            .withUserConfiguration(RestExecutorConfiguration.class)
            .withPropertyValues("gateway.catalog-location=classpath:catalog/worker-catalog.json",
                    "gateway.backends.worker-coordinator.base-url=http://127.0.0.1:9090",
                    "gateway.upstream.connect-timeout=2s", "gateway.upstream.deadline=5s",
                    "gateway.upstream.max-response-bytes=65536",
                    "gateway.allowed-origins[0]=http://localhost:6274",
                    "spring.ai.mcp.server.request-timeout=10s");

    @Test
    void bindsImmutableDeploymentSettingsAndRedactsPrivateValues() {
        context.withPropertyValues("gateway.backends.worker-coordinator.bearer-token=fixture-only-token").run(ctx -> {
            assertThat(ctx).hasNotFailed();
            var properties = ctx.getBean(GatewayProperties.class);
            assertThat(properties.upstream().connectTimeout()).isEqualTo(Duration.ofSeconds(2));
            assertThat(properties.upstream().deadline()).isEqualTo(Duration.ofSeconds(5));
            assertThat(properties.upstream().maxResponseBytes()).isEqualTo(65536);
            assertThat(properties.workerCoordinator().bearerToken()).isEqualTo("fixture-only-token");
            assertThat(properties.toString()).doesNotContain("fixture-only-token", "9090");
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> properties.backends().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> properties.allowedOrigins().clear())
                    .isInstanceOf(UnsupportedOperationException.class);
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://user:password@localhost:9090", "http://localhost:9090/path",
            "http://localhost:9090?query=1", "http://localhost:9090#fragment", "ftp://localhost", "/relative",
            "http://localhost:0", "http://localhost:65536", "http://localhost:", "http://:9090"})
    void rejectsUnsafeBackendOrigins(String origin) {
        context.withPropertyValues("gateway.backends.worker-coordinator.base-url=" + origin)
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost", "https://localhost:9443/", "http://127.0.0.1:1234",
            "http://[::1]:1234"})
    void acceptsHttpAndHttpsOrigins(String origin) {
        context.withPropertyValues("gateway.backends.worker-coordinator.base-url=" + origin).run(ctx -> {
            assertThat(ctx).hasNotFailed();
            assertThat(ctx.getBean(GatewayProperties.class).workerCoordinator().baseUrl().getRawPath()).isEmpty();
        });
    }

    @ParameterizedTest
    @ValueSource(strings = {"gateway.upstream.connect-timeout=0ms", "gateway.upstream.deadline=-1s",
            "gateway.upstream.connect-timeout=6s", "gateway.upstream.connect-timeout=2147483648ms",
            "gateway.upstream.deadline=10s", "gateway.upstream.deadline=11s",
            "spring.ai.mcp.server.request-timeout=4s", "spring.ai.mcp.server.request-timeout=0ms",
            "gateway.upstream.max-response-bytes=0", "gateway.upstream.max-response-bytes=-1",
            "gateway.upstream.max-response-bytes=2147483648", "gateway.upstream.deadline=PT0.000000001S",
            "gateway.catalog-location=", "gateway.backends.other.base-url=http://localhost:1234",
            "gateway.upstream.typo=1"})
    void rejectsInvalidSettingsAtContextStartup(String setting) {
        context.withPropertyValues(setting).run(ctx -> assertThat(ctx).hasFailed());
    }

    @ParameterizedTest
    @ValueSource(strings = {"*", "null", "http://example.com", "http://localhost:6274/path",
            "http://localhost:6274/", "http://localhost:6274?x=1", "http://localhost.evil:6274"})
    void rejectsNonlocalOrMalformedAllowedOrigins(String origin) {
        context.withPropertyValues("gateway.allowed-origins[0]=" + origin).run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    void rejectsHeaderInjectionWithoutRetainingTokenInTheValidationException() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() ->
                new GatewayProperties.Backend(java.net.URI.create("http://localhost"), "fixture\r\ninjection"))
                .isInstanceOf(IllegalArgumentException.class).hasMessage("bearer-token has invalid syntax");
    }
}
