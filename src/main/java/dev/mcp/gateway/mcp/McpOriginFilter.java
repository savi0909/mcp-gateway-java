package dev.mcp.gateway.mcp;

import java.util.List;

import org.springframework.core.Ordered;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Reject browser origins before the SDK transport can invoke a tool. */
public final class McpOriginFilter implements WebFilter, Ordered {

    private final List<String> allowedOrigins;

    public McpOriginFilter(List<String> allowedOrigins) {
        this.allowedOrigins = List.copyOf(allowedOrigins);
    }

    @Override
    public int getOrder() {
        return Ordered.HIGHEST_PRECEDENCE;
    }

    @Override
    public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (path.equals("/worker-coordinator/mcp") || path.startsWith("/worker-coordinator/mcp/") || path.startsWith("/control/")) {
            var origins = exchange.getRequest().getHeaders().get(HttpHeaders.ORIGIN);
            if (origins != null && (origins.size() != 1 || !allowedOrigins.contains(origins.getFirst()))) {
                exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
                return exchange.getResponse().setComplete();
            }
        }
        return chain.filter(exchange);
    }
}
