package dev.mcp.gateway.security;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import dev.mcp.gateway.catalog.ToolDefinition;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.jackson3.JacksonMcpJsonMapper;
import org.springframework.ai.mcp.server.webflux.transport.WebFluxStreamableServerTransportProvider;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.io.ResourceLoader;
import org.springframework.http.HttpStatus;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.security.config.annotation.web.reactive.EnableWebFluxSecurity;
import org.springframework.security.config.web.server.ServerHttpSecurity;
import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.NimbusReactiveJwtDecoder;
import org.springframework.security.oauth2.jwt.ReactiveJwtDecoder;
import org.springframework.security.web.server.SecurityWebFilterChain;
import org.springframework.security.web.server.context.NoOpServerSecurityContextRepository;
import org.springframework.web.reactive.function.client.WebClient;
import org.springframework.web.reactive.function.server.RouterFunction;
import org.springframework.web.reactive.function.server.RouterFunctions;
import org.springframework.web.reactive.function.server.ServerResponse;
import reactor.netty.http.client.HttpClient;
import tools.jackson.databind.json.JsonMapper;

@Configuration(proxyBeanMethods = false)
@EnableWebFluxSecurity
@EnableConfigurationProperties(SecuritySettings.class)
public class SecurityConfiguration {
    @Bean
    SecurityWebFilterChain securityChain(ServerHttpSecurity http, SecuritySettings settings,
            ObjectProvider<ReactiveJwtDecoder> decoder, Environment environment) {
        SecuritySettings.require(!java.util.Arrays.asList(environment.getActiveProfiles()).contains("secured") || settings.enabled(),
                "secured profile cannot disable caller security");
        http.csrf(ServerHttpSecurity.CsrfSpec::disable).httpBasic(ServerHttpSecurity.HttpBasicSpec::disable)
                .formLogin(ServerHttpSecurity.FormLoginSpec::disable).logout(ServerHttpSecurity.LogoutSpec::disable)
                .requestCache(ServerHttpSecurity.RequestCacheSpec::disable)
                .securityContextRepository(NoOpServerSecurityContextRepository.getInstance());
        if (!settings.enabled()) return http.authorizeExchange(access -> access.anyExchange().permitAll()).build();
        String challenge = "Bearer resource_metadata=\"" + settings.metadataUrl() + "\", scope=\"gateway:read\"";
        return http.authorizeExchange(access -> access
                    .pathMatchers("/.well-known/oauth-protected-resource", "/.well-known/oauth-protected-resource/worker-coordinator/mcp").permitAll()
                    .pathMatchers("/worker-coordinator/mcp", "/worker-coordinator/mcp/**").authenticated()
                    .anyExchange().denyAll())
                .oauth2ResourceServer(oauth -> oauth.jwt(jwt -> jwt.jwtDecoder(decoder.getObject()))
                    .authenticationEntryPoint((exchange, exception) -> {
                        exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                        exchange.getResponse().getHeaders().set("WWW-Authenticate", challenge);
                        return exchange.getResponse().setComplete();
                    }))
                .exceptionHandling(errors -> errors.authenticationEntryPoint((exchange, exception) -> {
                    exchange.getResponse().setStatusCode(HttpStatus.UNAUTHORIZED);
                    exchange.getResponse().getHeaders().set("WWW-Authenticate", challenge);
                    return exchange.getResponse().setComplete();
                })).build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "gateway-security", name = "enabled", havingValue = "true")
    ReactiveJwtDecoder callerJwtDecoder(SecuritySettings settings) {
        var http = HttpClient.create().disableRetry(true).followRedirect(false)
                .option(io.netty.channel.ChannelOption.CONNECT_TIMEOUT_MILLIS, 2000).responseTimeout(Duration.ofSeconds(2));
        var jwksClient = WebClient.builder().clientConnector(new ReactorClientHttpConnector(http))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(65536)).build();
        var decoder = NimbusReactiveJwtDecoder.withJwkSetUri(settings.jwkSetUri().toString())
                .jwsAlgorithm(SignatureAlgorithm.RS256).validateType(false).webClient(jwksClient).build();
        OAuth2TokenValidator<Jwt> contract = jwt -> {
            try {
                boolean valid = jwt.getAudience().contains(settings.resource().toString())
                        && jwt.getExpiresAt() != null && jwt.getIssuedAt() != null
                        && !jwt.getIssuedAt().isAfter(Instant.now()) && jwt.getIssuedAt().isBefore(jwt.getExpiresAt())
                        && jwt.getSubject() != null && !jwt.getSubject().isBlank()
                        && jwt.getClaimAsString("tenant") != null && !jwt.getClaimAsString("tenant").isBlank()
                        && jwt.getClaims().get("scope") instanceof String scope
                        && scope.matches("[A-Za-z0-9:_-]+( +[A-Za-z0-9:_-]+)*")
                        && "at+jwt".equals(jwt.getHeaders().get("typ"));
                if (valid) return OAuth2TokenValidatorResult.success();
            }
            catch (RuntimeException invalidClaim) { /* Never retain token/claim diagnostics. */ }
            return OAuth2TokenValidatorResult.failure(new OAuth2Error("invalid_token", "Invalid access token", null));
        };
        decoder.setJwtValidator(new DelegatingOAuth2TokenValidator<>(new JwtTimestampValidator(Duration.ZERO),
                new JwtIssuerValidator(settings.issuer().toString()), contract));
        return token -> decoder.decode(token).timeout(Duration.ofSeconds(3))
                .onErrorMap(error -> new org.springframework.security.oauth2.jwt.BadJwtException("Invalid access token"));
    }

    @Bean
    @ConditionalOnProperty(prefix = "gateway-security", name = "enabled", havingValue = "true")
    TenantPolicy tenantPolicy(SecuritySettings settings, ResourceLoader resources, List<ToolDefinition> tools, Environment env) {
        SecuritySettings.require("true".equals(env.getProperty("spring.ai.mcp.server.enabled")), "security requires an enabled MCP profile");
        SecuritySettings.require(tools.stream().allMatch(tool -> TenantPolicy.TOOLS.contains(tool.name())),
                "secured catalog contains a tool without explicit authorization policy");
        return TenantPolicy.load(settings.policyLocation(), resources);
    }

    @Bean
    @ConditionalOnProperty(prefix = "gateway-security", name = "enabled", havingValue = "true")
    SessionAdmissionFilter sessionAdmissionFilter(TenantPolicy policy) { return new SessionAdmissionFilter(policy); }

    @Bean
    @ConditionalOnProperty(prefix = "gateway-security", name = "enabled", havingValue = "true")
    WebFluxStreamableServerTransportProvider securedTransport(@Qualifier("mcpServerJsonMapper") JsonMapper json,
            TenantPolicy policy) {
        return WebFluxStreamableServerTransportProvider.builder().jsonMapper(new JacksonMcpJsonMapper(json))
                .messageEndpoint("/worker-coordinator/mcp").maxSessions(256).sessionIdleTimeout(Duration.ofMinutes(15))
                .contextExtractor(request -> {
                    Object caller = request.attributes().get(Caller.ATTRIBUTE);
                    return caller instanceof Caller ? McpTransportContext.create(Map.of(Caller.ATTRIBUTE, caller)) : McpTransportContext.EMPTY;
                }).build();
    }

    @Bean
    @ConditionalOnProperty(prefix = "gateway-security", name = "enabled", havingValue = "true")
    RouterFunction<ServerResponse> protectedResourceMetadata(SecuritySettings settings) {
        var metadata = Map.of("resource", settings.resource().toString(), "authorization_servers", List.of(settings.issuer().toString()),
                "scopes_supported", List.of("gateway:read", "gateway:write"), "bearer_methods_supported", List.of("header"));
        return RouterFunctions.route().GET("/.well-known/oauth-protected-resource", request -> ServerResponse.ok().bodyValue(metadata))
                .GET("/.well-known/oauth-protected-resource/worker-coordinator/mcp", request -> ServerResponse.ok().bodyValue(metadata)).build();
    }
}
