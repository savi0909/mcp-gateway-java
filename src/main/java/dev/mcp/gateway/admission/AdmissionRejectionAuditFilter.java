package dev.mcp.gateway.admission;

import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Observes fixed response classifications only; never parses protocol payloads or token claims. */
public final class AdmissionRejectionAuditFilter implements WebFilter, Ordered {
    private final AdmissionService admission;
    public AdmissionRejectionAuditFilter(AdmissionService admission) { this.admission = admission; }
    @Override public int getOrder() { return Ordered.HIGHEST_PRECEDENCE; }
    @Override public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        boolean control = path.startsWith("/control/");
        if (control || path.equals("/worker-coordinator/mcp") || path.startsWith("/worker-coordinator/mcp/")) {
            exchange.getResponse().beforeCommit(() -> {
                var status = exchange.getResponse().getStatusCode();
                if (status == null || status.value() != 401 && status.value() != 403) return Mono.empty();
                return admission.recordHttpRejection(status.value(), control).then()
                        .onErrorResume(error -> {
                            exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
                            return Mono.empty();
                        });
            });
        }
        return chain.filter(exchange);
    }
}
