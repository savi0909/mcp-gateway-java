package dev.mcp.gateway.config;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Deployment settings only; public metadata and static requests belong to the catalog. */
@ConfigurationProperties(prefix = "gateway", ignoreUnknownFields = false)
public record GatewayProperties(String catalogLocation, Map<String, Backend> backends,
        Upstream upstream, List<String> allowedOrigins) {

    public static final String BACKEND = "worker-coordinator";

    public GatewayProperties {
        require(catalogLocation != null && !catalogLocation.isBlank(), "catalog-location is required");
        require(backends != null && backends.containsKey(BACKEND) && backends.get(BACKEND) != null
                && backends.keySet().stream().allMatch(key -> key.equals(BACKEND) || key.equals("payment-api"))
                && backends.values().stream().allMatch(java.util.Objects::nonNull),
                "only worker-coordinator and optional payment-api backends are supported");
        backends = Map.copyOf(backends);
        require(upstream != null, "upstream settings are required");
        require(allowedOrigins != null, "allowed-origins is required (an empty list is supported)");
        for (String origin : allowedOrigins) {
            URI uri = origin(origin, "allowed-origins must contain HTTP(S) localhost origins");
            require(List.of("localhost", "127.0.0.1", "[::1]").contains(uri.getHost())
                    && uri.getRawPath().isEmpty(), "allowed-origins must contain HTTP(S) localhost origins");
        }
        allowedOrigins = List.copyOf(allowedOrigins);
    }

    public Backend workerCoordinator() {
        return backends.get(BACKEND);
    }

    public void validateRequestBudget(Duration requestBudget) {
        positiveMillis(requestBudget, "MCP request-timeout");
        require(upstream.deadline().compareTo(requestBudget) < 0,
                "upstream deadline must be shorter than MCP request-timeout");
    }

    public record Backend(URI baseUrl, String bearerToken) {
        public Backend {
            baseUrl = origin(baseUrl == null ? null : baseUrl.toString(),
                    "backend base-url must be an HTTP(S) origin without credentials, query or fragment");
            // Store an origin without a trailing slash so appending a validated path is unambiguous.
            baseUrl = URI.create(baseUrl.getScheme() + "://" + baseUrl.getRawAuthority());
            bearerToken = bearerToken == null ? "" : bearerToken;
            require(bearerToken.isEmpty() || bearerToken.matches("[A-Za-z0-9._~+/-]+=*"),
                    "bearer-token has invalid syntax");
        }

        @Override
        public String toString() {
            return "Backend[baseUrl=<private>, bearerToken=<redacted>]";
        }
    }

    public record Upstream(Duration connectTimeout, Duration deadline, int maxResponseBytes) {
        public Upstream {
            positiveMillis(connectTimeout, "connect-timeout");
            positiveMillis(deadline, "deadline");
            require(connectTimeout.toMillis() <= Integer.MAX_VALUE,
                    "connect-timeout exceeds connector range");
            require(connectTimeout.compareTo(deadline) <= 0, "connect-timeout must not exceed deadline");
            require(maxResponseBytes > 0, "max-response-bytes must be positive");
        }
    }

    private static URI origin(String value, String message) {
        try {
            URI uri = URI.create(value);
            require(!uri.isOpaque() && ("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    && uri.getHost() != null && uri.getRawUserInfo() == null
                    && uri.getRawQuery() == null && uri.getRawFragment() == null
                    && (uri.getRawPath().isEmpty() || "/".equals(uri.getRawPath()))
                    && (uri.getPort() == -1 || uri.getPort() > 0 && uri.getPort() <= 65535)
                    && !uri.getRawAuthority().endsWith(":"), message);
            return uri;
        }
        catch (IllegalArgumentException | NullPointerException ex) {
            throw new IllegalArgumentException(message);
        }
    }

    private static void positiveMillis(Duration duration, String name) {
        try {
            require(duration != null && !duration.isNegative() && duration.toMillis() >= 1,
                    name + " must be at least 1ms");
            duration.toNanos();
        }
        catch (ArithmeticException ex) {
            throw new IllegalArgumentException(name + " exceeds supported duration range");
        }
    }

    private static void require(boolean valid, String message) {
        if (!valid) {
            throw new IllegalArgumentException(message);
        }
    }
}
