package dev.mcp.gateway.admission;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import io.modelcontextprotocol.json.TypeRef;
import io.modelcontextprotocol.server.McpRequestHandler;
import io.modelcontextprotocol.server.McpServerFeatures;
import io.modelcontextprotocol.spec.*;
import org.springframework.ai.mcp.server.webflux.transport.WebFluxStreamableServerTransportProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Public SDK SPI decorator; SDK sessions own negotiation, envelopes and call dispatch. */
public final class FilteredMcpTransport implements McpStreamableServerTransportProvider {
    private final WebFluxStreamableServerTransportProvider delegate;
    private final AdmissionService admission;
    private final Map<String, McpSchema.Tool> tools;
    public FilteredMcpTransport(WebFluxStreamableServerTransportProvider delegate, AdmissionService admission,
            List<McpServerFeatures.AsyncToolSpecification> specifications) {
        this.delegate = delegate; this.admission = admission;
        this.tools = specifications.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(s -> s.tool().name(), McpServerFeatures.AsyncToolSpecification::tool));
    }
    @Override public void setSessionFactory(McpStreamableServerSession.Factory factory) {
        delegate.setSessionFactory(request -> {
            var initialized = factory.startSession(request);
            McpRequestHandler<McpSchema.ListToolsResult> handler = (exchange, params) -> {
                Object raw = exchange.transportContext().get(AdmissionIdentity.ATTRIBUTE);
                if (!(raw instanceof AdmissionIdentity i)) return Mono.error(AdmissionFailure.denied());
                return admission.discover(i).map(names -> new McpSchema.ListToolsResult(names.stream().map(tools::get).toList(), null));
            };
            return new McpStreamableServerSession.McpStreamableServerSessionInit(
                    new FilteringSession(initialized.session(), request, handler), initialized.initResult());
        });
    }
    @Override public Mono<Void> notifyClients(String method, Object params) { return delegate.notifyClients(method, params); }
    @Override public Mono<Void> notifyClient(String id, String method, Object params) { return delegate.notifyClient(id, method, params); }
    @Override public Mono<Void> closeGracefully() { return delegate.closeGracefully(); }
    @Override public List<String> protocolVersions() { return delegate.protocolVersions(); }
    private static final class FilteringSession extends McpStreamableServerSession {
        private final McpStreamableServerSession nativeSession;
        FilteringSession(McpStreamableServerSession session, McpSchema.InitializeRequest request, McpRequestHandler<McpSchema.ListToolsResult> handler) {
            super(session.getId(), request.capabilities(), request.clientInfo(), Duration.ofSeconds(10), Map.of("tools/list", handler), Map.of(), Mono::empty, null);
            this.nativeSession = session;
        }
        @Override public Mono<Void> responseStream(McpSchema.JSONRPCRequest request, McpStreamableServerTransport transport) {
            return "tools/list".equals(request.method()) ? super.responseStream(request, transport) : nativeSession.responseStream(request, transport);
        }
        @Override public void setMinLoggingLevel(McpSchema.LoggingLevel level) { super.setMinLoggingLevel(level); nativeSession.setMinLoggingLevel(level); }
        @Override public boolean isNotificationForLevelAllowed(McpSchema.LoggingLevel level) { return nativeSession.isNotificationForLevelAllowed(level); }
        @Override public <T> Mono<T> sendRequest(String method, Object params, TypeRef<T> type) { return nativeSession.sendRequest(method, params, type); }
        @Override public Mono<Void> sendNotification(String method, Object params) { return nativeSession.sendNotification(method, params); }
        @Override public Mono<Void> delete() { return nativeSession.delete(); }
        @Override public McpStreamableServerSessionStream listeningStream(McpStreamableServerTransport transport) { return nativeSession.listeningStream(transport); }
        @Override public Flux<McpSchema.JSONRPCMessage> replay(Object id) { return nativeSession.replay(id); }
        @Override public Mono<Void> accept(McpSchema.JSONRPCNotification message) { return nativeSession.accept(message); }
        @Override public Mono<Void> accept(McpSchema.JSONRPCResponse message) { return nativeSession.accept(message); }
        @Override public Mono<Void> closeGracefully() { return super.closeGracefully().then(nativeSession.closeGracefully()); }
        @Override public void close() { super.close(); nativeSession.close(); }
    }
}
