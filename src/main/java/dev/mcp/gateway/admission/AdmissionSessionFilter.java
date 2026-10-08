package dev.mcp.gateway.admission;

import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import org.springframework.core.Ordered;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.WebFilter;
import org.springframework.web.server.WebFilterChain;
import reactor.core.publisher.Mono;

/** Binds SDK-owned session identifiers to authenticated owners; contains no protocol parsing. */
public final class AdmissionSessionFilter implements WebFilter, Ordered {
    private static final String SESSION = "Mcp-Session-Id";
    private static final Duration IDLE = Duration.ofMinutes(15);
    private record Entry(AdmissionIdentity owner, Instant accessed) { }
    private final Map<String, Entry> sessions = new HashMap<>();
    public AdmissionSessionFilter() { }
    @Override public int getOrder() { return -90; } // after Spring Security's WebFilterChainProxy (-100)

    @Override public Mono<Void> filter(ServerWebExchange exchange, WebFilterChain chain) {
        String path = exchange.getRequest().getPath().value();
        if (!path.equals("/worker-coordinator/mcp") && !path.startsWith("/worker-coordinator/mcp/") && !path.startsWith("/control/")) return chain.filter(exchange);
        return exchange.getPrincipal().map(principal -> {
            if (!(principal instanceof JwtAuthenticationToken authentication)) throw new Denied();
            var caller = AdmissionIdentity.from(authentication.getToken());
            return caller;
        }).switchIfEmpty(Mono.error(new Denied())).flatMap(caller -> {
            var ids = exchange.getRequest().getHeaders().get(SESSION);
            if (ids != null && (ids.size() != 1 || !admit(ids.getFirst(), caller))) return deny(exchange);
            exchange.getAttributes().put(AdmissionIdentity.ATTRIBUTE, caller);
            exchange.getResponse().beforeCommit(() -> {
                String created = exchange.getResponse().getHeaders().getFirst(SESSION);
                if (created != null && ids == null && !bind(created, caller)) {
                    exchange.getResponse().setStatusCode(HttpStatus.SERVICE_UNAVAILABLE);
                    exchange.getResponse().getHeaders().remove(SESSION);
                }
                return Mono.empty();
            });
            return chain.filter(exchange).doFinally(signal -> {
                if (ids != null && exchange.getRequest().getMethod() == org.springframework.http.HttpMethod.DELETE
                        && exchange.getResponse().getStatusCode() != null
                        && exchange.getResponse().getStatusCode().is2xxSuccessful()) remove(ids.getFirst());
            });
        }).onErrorResume(Denied.class, error -> deny(exchange)).onErrorResume(AdmissionFailure.class, error -> deny(exchange));
    }
    private synchronized boolean admit(String id, AdmissionIdentity caller) {
        expire();
        var entry = sessions.get(id);
        if (entry == null || !entry.owner().sameOwner(caller)) return false;
        sessions.put(id, new Entry(entry.owner(), Instant.now()));
        return true;
    }
    private synchronized boolean bind(String id, AdmissionIdentity caller) {
        expire();
        if (sessions.size() >= 256 || sessions.containsKey(id)) return false;
        sessions.put(id, new Entry(caller, Instant.now()));
        return true;
    }
    private synchronized void remove(String id) { sessions.remove(id); }
    private void expire() { sessions.values().removeIf(entry -> entry.accessed().plus(IDLE).isBefore(Instant.now())); }
    private Mono<Void> deny(ServerWebExchange exchange) {
        exchange.getResponse().setStatusCode(HttpStatus.FORBIDDEN);
        return exchange.getResponse().setComplete();
    }
    private static final class Denied extends RuntimeException { private Denied() { super(null, null, false, false); } }
}
