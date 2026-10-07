package dev.mcp.gateway.config;

import java.time.Duration;

import dev.mcp.gateway.rest.RestBindingExecutor;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.context.properties.bind.Bindable;
import org.springframework.boot.context.properties.bind.Binder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import reactor.netty.resources.ConnectionProvider;

@Configuration(proxyBeanMethods = false)
@EnableConfigurationProperties(GatewayProperties.class)
public class RestExecutorConfiguration {

    @Bean(destroyMethod = "dispose")
    ConnectionProvider workerCoordinatorConnections(GatewayProperties properties, Environment environment) {
        Duration requestBudget = Binder.get(environment)
                .bind("spring.ai.mcp.server.request-timeout", Bindable.of(Duration.class))
                .orElse(Duration.ofSeconds(10));
        properties.validateRequestBudget(requestBudget);
        return ConnectionProvider.builder("worker-coordinator")
                .maxConnections(16)
                .pendingAcquireMaxCount(32)
                .pendingAcquireTimeout(properties.upstream().connectTimeout())
                .build();
    }

    @Bean
    RestBindingExecutor restBindingExecutor(GatewayProperties properties,
            ConnectionProvider workerCoordinatorConnections) {
        return new RestBindingExecutor(properties, workerCoordinatorConnections);
    }
}
