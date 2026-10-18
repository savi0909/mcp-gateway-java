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
        @Override public void close() throws Exception { service.close(); journal.close(); backend.close(); connections.disposeLater().block(Duration.ofSeconds(2)); }
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
            h.service.close(); h.journal = new AdmissionJournal(h.settings); h.service = h.service(h.journal);
            assertThat(h.journal.readAudits()).anyMatch(record -> "UNKNOWN_AFTER_RESTART".equals(record.get("outcome")));
            assertThat(h.backend.count()).isEqualTo(1);
        }
    }
    @Test void auditVisibilityExpiresExactlyAtBoundaryWhileEvidenceRemainsRetained() throws Exception {
        try (var h = new Harness()) {
            var admin = new AdmissionIdentity("https://issuer.example", "admin-subject", "console", "organization-north", "tenant-blue",
                    "ADMINISTRATION", "", "audit-boundary", h.clock.now.plus(Duration.ofDays(100)), Set.of("gateway:audit"));
            String id = UUID.randomUUID().toString();
            h.journal.appendAudit(Map.of("id", id, "organization", "organization-north", "tenant", "tenant-blue",
                    "decision", "RETENTION_FIXTURE", "at", h.clock.now.minus(Duration.ofDays(90)).plusSeconds(1).toString(),
                    "retainUntil", h.clock.now.plusSeconds(1).toString()));
            h.clock.now = h.clock.now.plusSeconds(1).minusNanos(1);
            assertThat(h.service.auditPage(admin, null, null, 200).block(Duration.ofSeconds(2)).get("records").toString()).contains(id);
            h.clock.now = h.clock.now.plusNanos(1);
            assertThat(h.service.auditPage(admin, null, null, 200).block(Duration.ofSeconds(2)).get("records").toString()).doesNotContain(id);
            assertThat(h.journal.readAudits()).anyMatch(record -> id.equals(record.get("id")));
            assertThat(h.backend.count()).isZero();
        }
    }
    @Test void auditSnapshotExcludesLaterWritesWithRepeatedOrBackwardTimestamps() throws Exception {
        for (boolean backwards : List.of(false, true)) {
            try (var h = new Harness()) {
                var admin = new AdmissionIdentity("https://issuer.example", "admin-subject", "console", "organization-north", "tenant-blue",
                        "ADMINISTRATION", "", "audit-snapshot", h.clock.now.plusSeconds(3600), Set.of("gateway:audit"));
                for (int n = 0; n < 3; n++) h.journal.appendAudit(Map.of("id", "snapshot-" + n,
                        "organization", "organization-north", "tenant", "tenant-blue", "decision", "SNAPSHOT_FIXTURE",
                        "at", h.clock.now.toString(), "retainUntil", h.clock.now.plus(Duration.ofDays(90)).toString()));
                var first = h.service.auditPage(admin, null, null, 1).block(Duration.ofSeconds(2));
                String after = (String) first.get("nextCursor"); var snapshot = Instant.parse((String) first.get("snapshotAt"));
                h.journal.appendAudit(Map.of("id", "late-same-time", "organization", "organization-north", "tenant", "tenant-blue",
                        "decision", "LATE_FIXTURE", "at", h.clock.now.toString(), "retainUntil", h.clock.now.plusSeconds(3600).toString()));
                if (backwards) h.clock.now = h.clock.now.minusSeconds(1);
                h.journal.appendAudit(Map.of("id", "late-backward-time", "organization", "organization-north", "tenant", "tenant-blue",
                        "decision", "LATE_FIXTURE", "at", h.clock.now.toString(), "retainUntil", h.clock.now.plusSeconds(3600).toString()));
                var second = h.service.auditPage(admin, after, snapshot, 1).block(Duration.ofSeconds(2));
                var third = h.service.auditPage(admin, (String) second.get("nextCursor"), snapshot, 1).block(Duration.ofSeconds(2));
                assertThat(first.get("records").toString()).contains("snapshot-0").doesNotContain("AUDIT_ACCESS", "LATE_FIXTURE");
                assertThat(second.get("records").toString()).contains("snapshot-1").doesNotContain("AUDIT_ACCESS", "LATE_FIXTURE");
                assertThat(third.get("records").toString()).contains("snapshot-2").doesNotContain("AUDIT_ACCESS", "LATE_FIXTURE");
                assertThat(third.get("nextCursor")).isEqualTo("");
                assertThat(h.backend.count()).isZero();
            }
        }
    }
    @Test void auditCursorRejectsChangedBoundaryAnchorSnapshotAndMissingSnapshot() throws Exception {
        try (var h = new Harness()) {
            var admin = new AdmissionIdentity("https://issuer.example", "admin-subject", "console", "organization-north", "tenant-blue",
                    "ADMINISTRATION", "", "audit-cursor", h.clock.now.plusSeconds(3600), Set.of("gateway:audit"));
            for (int n = 0; n < 2; n++) h.journal.appendAudit(Map.of("id", "cursor-" + n,
                    "organization", "organization-north", "tenant", "tenant-blue", "decision", "CURSOR_FIXTURE",
                    "at", h.clock.now.toString(), "retainUntil", h.clock.now.plusSeconds(3600).toString()));
            var page = h.service.auditPage(admin, null, null, 1).block(Duration.ofSeconds(2));
            String cursor = (String) page.get("nextCursor"); var snapshot = Instant.parse((String) page.get("snapshotAt"));
            assertThat(cursor).matches("[0-9a-f]{64}");
            for (String changed : List.of("00000003" + cursor.substring(8), cursor.substring(0, 8) + "00000002" + cursor.substring(16), "f".repeat(64))) {
                assertThatThrownBy(() -> h.service.auditPage(admin, changed, snapshot, 1).block(Duration.ofSeconds(2))).isInstanceOf(AdmissionFailure.class);
            }
            assertThatThrownBy(() -> h.service.auditPage(admin, cursor, snapshot.plusNanos(1), 1).block(Duration.ofSeconds(2))).isInstanceOf(AdmissionFailure.class);
            assertThatThrownBy(() -> h.service.auditPage(admin, cursor, null, 1).block(Duration.ofSeconds(2))).isInstanceOf(AdmissionFailure.class);
            assertThatThrownBy(() -> h.service.auditPage(admin, null, snapshot.plusNanos(1), 1).block(Duration.ofSeconds(2))).isInstanceOf(AdmissionFailure.class);
            assertThat(h.backend.count()).isZero();
        }
    }
    @Test void auditCursorSurvivesReopenButStillChecksCurrentRetentionAndReviewerAuthority() throws Exception {
        try (var h = new Harness()) {
            var admin = new AdmissionIdentity("https://issuer.example", "admin-subject", "console", "organization-north", "tenant-blue",
                    "ADMINISTRATION", "", "audit-reopen", h.clock.now.plusSeconds(3600), Set.of("gateway:audit"));
            for (int n = 0; n < 3; n++) h.journal.appendAudit(Map.of("id", "reopen-" + n,
                    "organization", "organization-north", "tenant", "tenant-blue", "decision", "REOPEN_FIXTURE",
                    "at", h.clock.now.toString(), "retainUntil", h.clock.now.plusSeconds(n < 2 ? 1 : 3600).toString()));
            var first = h.service.auditPage(admin, null, null, 1).block(Duration.ofSeconds(2));
            String cursor = (String) first.get("nextCursor"); var snapshot = Instant.parse((String) first.get("snapshotAt"));
            h.service.close(); h.journal.close(); h.journal = new AdmissionJournal(h.settings); h.service = h.service(h.journal);
            h.clock.now = h.clock.now.plusSeconds(1);
            var remaining = h.service.auditPage(admin, cursor, snapshot, 200).block(Duration.ofSeconds(2));
            assertThat(remaining.get("records").toString()).contains("reopen-2").doesNotContain("reopen-0", "reopen-1", "AUDIT_ACCESS");
            assertThat(remaining.get("nextCursor")).isEqualTo("");
            var p = h.journal.readPolicy(); var people = new HashMap<>(p.humans()); var viewer = people.get("security-admin");
            people.put("security-admin", new AdmissionPolicy.Human(viewer.authority(), viewer.subject(), viewer.displayName(), viewer.status(), viewer.tenants(), Map.of()));
            h.journal.writePolicy(p.next(people, p.applications(), p.servers(), p.capabilities(), p.catalogs(), p.delegations(), p.grants()));
            assertThatThrownBy(() -> h.service.auditPage(admin, cursor, snapshot, 200).block(Duration.ofSeconds(2))).isInstanceOf(AdmissionFailure.class);
            assertThat(h.journal.readAudits()).anyMatch(record -> "reopen-1".equals(record.get("id")));
            assertThat(h.backend.count()).isZero();
        }
    }
    @Test void auditCursorCannotBeReboundToAnotherIndependentlyAuthorizedTenant() throws Exception {
        try (var h = new Harness()) {
            var p = h.journal.readPolicy(); var people = new HashMap<>(p.humans()); var apps = new HashMap<>(p.applications());
            var person = people.get("security-admin"); var console = apps.get("console"); var tenants = Set.of("tenant-blue", "tenant-green");
            people.put("security-admin", new AdmissionPolicy.Human(person.authority(), person.subject(), person.displayName(), person.status(), tenants,
                    Map.of("tenant-blue", Set.of("AUDIT_VIEWER"), "tenant-green", Set.of("AUDIT_VIEWER"))));
            apps.put("console", new AdmissionPolicy.Application(console.organization(), console.owner(), console.status(), tenants, true));
            h.journal.writePolicy(p.next(people, apps, p.servers(), p.capabilities(), p.catalogs(), p.delegations(), p.grants()));
            for (String tenant : tenants) for (int n = 0; n < 2; n++) h.journal.appendAudit(Map.of("id", tenant + "-" + n,
                    "organization", "organization-north", "tenant", tenant, "decision", "SCOPED_FIXTURE",
                    "at", h.clock.now.toString(), "retainUntil", h.clock.now.plusSeconds(3600).toString()));
            var blue = new AdmissionIdentity("https://issuer.example", "admin-subject", "console", "organization-north", "tenant-blue",
                    "ADMINISTRATION", "", "audit-blue", h.clock.now.plusSeconds(3600), Set.of("gateway:audit"));
            var green = new AdmissionIdentity(blue.authority(), blue.subject(), blue.application(), blue.organization(), "tenant-green",
                    blue.mode(), blue.delegation(), "audit-green", blue.expiresAt(), blue.scopes());
            var first = h.service.auditPage(blue, null, null, 1).block(Duration.ofSeconds(2));
            String cursor = (String) first.get("nextCursor"); var snapshot = Instant.parse((String) first.get("snapshotAt"));
            assertThat(h.service.auditPage(green, null, null, 1).block(Duration.ofSeconds(2)).get("records").toString()).contains("tenant-green-0");
            assertThatThrownBy(() -> h.service.auditPage(green, cursor, snapshot, 1).block(Duration.ofSeconds(2))).isInstanceOf(AdmissionFailure.class);
            var rest = h.service.auditPage(blue, cursor, snapshot, 200).block(Duration.ofSeconds(2));
            assertThat(rest.get("records").toString()).contains("tenant-blue-1").doesNotContain("tenant-green", "AUDIT_ACCESS");
            assertThat(rest.get("nextCursor")).isEqualTo("");
            assertThat(h.backend.count()).isZero();
        }
    }
}
