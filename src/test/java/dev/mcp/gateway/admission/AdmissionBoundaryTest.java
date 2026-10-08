package dev.mcp.gateway.admission;

import java.nio.file.Files;
import java.time.*;
import java.util.*;
import dev.mcp.gateway.config.GatewayProperties;
import dev.mcp.gateway.rest.RestBindingExecutor;
import dev.mcp.gateway.support.IndependentHttpMock;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.Test;
import reactor.netty.resources.ConnectionProvider;
import static org.assertj.core.api.Assertions.*;

/** Deterministic boundary tests supplement the real Boot/SDK integration suite. */
class AdmissionBoundaryTest {
    private static final class MutableClock extends Clock {
        private Instant now = Instant.now();
        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId zone) { return this; }
        @Override public Instant instant() { return now; }
    }
    private static final class Harness implements AutoCloseable {
        final MutableClock clock = new MutableClock(); final IndependentHttpMock backend = new IndependentHttpMock();
        final ConnectionProvider connections = ConnectionProvider.create("uc01-boundary-" + UUID.randomUUID(), 4);
        final AdmissionPolicy seed; final AdmissionSettings settings; final GatewayProperties gateway;
        final RestBindingExecutor executor; AdmissionJournal journal; AdmissionService service;
        final AdmissionIdentity identity;
        Harness() throws Exception {
            var dir = AdmissionFixtures.directory(); seed = AdmissionFixtures.policy("https://issuer.example", backend.origin());
            var ds = new HashMap<>(seed.delegations()); var d = ds.get("delegation-0");
            ds.put("delegation-0", new AdmissionPolicy.Delegation(d.organization(), d.tenant(), d.human(), d.application(), clock.now, clock.now.plusSeconds(900), false, d.capabilities(), d.resources()));
            var initial = new AdmissionPolicy(1, seed.organizations(), seed.tenants(), seed.humans(), seed.applications(), seed.servers(), seed.capabilities(), seed.catalogs(), ds, seed.grants(), seed.credentials(), seed.resources());
            var bootstrap = dir.resolve("bootstrap.json"); Files.writeString(bootstrap, AdmissionFixtures.JSON.writeValueAsString(initial));
            settings = new AdmissionSettings(bootstrap, dir.resolve("journals"), Duration.ofSeconds(30), Map.of("credential-0", new AdmissionSettings.Credential("read-only-canary", backend.origin(), clock.now.plusSeconds(60))));
            gateway = new GatewayProperties("classpath:catalog/coordinator-catalog.json", Map.of("worker-coordinator", new GatewayProperties.Backend(backend.origin(), "forbidden-fallback")),
                    new GatewayProperties.Upstream(Duration.ofMillis(200), Duration.ofMillis(500), 8192), List.of());
            executor = new RestBindingExecutor(gateway, connections); journal = new AdmissionJournal(settings); service = service(journal);
            identity = new AdmissionIdentity("https://issuer.example", "subject-0", "application-alpha", "organization-north", "tenant-blue", "HUMAN_DELEGATED", "delegation-0", "boundary-run", clock.now.plusSeconds(3600), Set.of("gateway:read"));
        }
        AdmissionService service(AdmissionJournal store) { return new AdmissionService(store, settings, gateway, AdmissionFixtures.definitions(), executor, clock); }
        McpSchema.CallToolResult call() {
            var tool = AdmissionFixtures.definitions().stream().filter(t -> t.name().equals("get_product")).findFirst().orElseThrow();
            var exchange = new McpAsyncServerExchange("supplementary-unit-session", null, null, null, McpTransportContext.create(Map.of(AdmissionIdentity.ATTRIBUTE, identity)));
            return service.invoke(tool, new McpSchema.CallToolRequest("get_product", Map.of("productId", "item-0"), null), exchange).block(Duration.ofSeconds(2));
        }
        @Override public void close() throws Exception { journal.close(); backend.close(); connections.disposeLater().block(Duration.ofSeconds(2)); }
    }
    @Test void delegationAllowsImmediatelyBeforeExpiryAndDeniesExactlyAtExpiry() throws Exception {
        try (var h = new Harness()) {
            h.clock.now = h.clock.now.plusSeconds(900).minusNanos(1);
            assertThat(h.service.discover(h.identity).block(Duration.ofSeconds(2))).containsExactly("get_product");
            h.clock.now = h.clock.now.plusNanos(1);
            assertThatThrownBy(() -> h.service.discover(h.identity).block(Duration.ofSeconds(2))).isInstanceOf(AdmissionFailure.class);
            assertThat(h.backend.count()).isZero();
            var d = h.journal.readPolicy().delegations().get("delegation-0");
            var ds = new HashMap<>(h.seed.delegations());
            ds.put("delegation-0", new AdmissionPolicy.Delegation(d.organization(), d.tenant(), d.human(), d.application(), h.clock.now, h.clock.now.plusSeconds(901), false, d.capabilities(), d.resources()));
            assertThatThrownBy(() -> h.seed.next(h.seed.humans(), h.seed.applications(), h.seed.servers(), h.seed.capabilities(), h.seed.catalogs(), ds, h.seed.grants())).isInstanceOf(IllegalArgumentException.class);
        }
    }
    @Test void credentialsExpireExactlyAtBoundaryWithoutSharedFallback() throws Exception {
        try (var h = new Harness()) {
            h.clock.now = h.clock.now.plusSeconds(60).minusNanos(1);
            h.backend.enqueue(IndependentHttpMock.Reply.json("{\"productId\":\"item-0\",\"productName\":\"Name\",\"status\":\"ACTIVE\"}"));
            assertThat(h.call().isError()).isFalse(); assertThat(h.backend.takeRequest().header("Authorization")).isEqualTo("Bearer read-only-canary");
            h.clock.now = h.clock.now.plusNanos(1); var denied = h.call();
            assertThat(denied.isError()).isTrue(); assertThat(denied.structuredContent()).isNull(); assertThat(denied.content().toString()).contains("VERIFICATION_UNAVAILABLE", "not_attempted");
            assertThat(h.backend.count()).isEqualTo(1);
        }
    }
    @Test void lostOutcomeAuditPreservesAdmissionAndRestartRecordsUncertaintyWithoutReplay() throws Exception {
        try (var h = new Harness()) {
            h.backend.enqueue(new IndependentHttpMock.Reply(200, "{}", null, IndependentHttpMock.Mode.WAIT_HEADERS));
            var future = java.util.concurrent.CompletableFuture.supplyAsync(h::call);
            h.backend.takeRequest(); h.journal.close();
            var result = future.get(3, java.util.concurrent.TimeUnit.SECONDS);
            assertThat(result.isError()).isTrue(); assertThat(result.structuredContent()).isNull(); assertThat(result.content().toString()).contains("VERIFICATION_UNAVAILABLE", "unknown").doesNotContain("not_attempted");
            h.journal = new AdmissionJournal(h.settings); h.service = h.service(h.journal);
            assertThat(h.journal.readAudits()).anyMatch(record -> "UNKNOWN_AFTER_RESTART".equals(record.get("outcome")));
            assertThat(h.backend.count()).isEqualTo(1);
        }
    }
}
