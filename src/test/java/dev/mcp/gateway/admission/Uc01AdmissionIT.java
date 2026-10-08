package dev.mcp.gateway.admission;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

import dev.mcp.gateway.McpGatewayApplication;
import dev.mcp.gateway.support.IndependentHttpMock;
import dev.mcp.gateway.support.LocalTokenIssuer;
import io.modelcontextprotocol.client.*;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.spec.McpSchema;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.test.system.*;
import org.springframework.context.ConfigurableApplicationContext;
import static org.assertj.core.api.Assertions.*;

@ExtendWith(OutputCaptureExtension.class)
class Uc01AdmissionIT {
    private static final String RESOURCE = "http://127.0.0.1:18080/worker-coordinator/mcp";
    private final List<McpAsyncClient> clients = new ArrayList<>();
    private final Map<McpAsyncClient, java.util.concurrent.atomic.AtomicReference<String>> sessions = new HashMap<>();
    private IndependentHttpMock backend; private LocalTokenIssuer issuer; private ConfigurableApplicationContext app;
    private String origin; private AdmissionPolicy seed; private Path directory;
    @BeforeEach void start() throws Exception {
        backend = new IndependentHttpMock(); issuer = new LocalTokenIssuer(); directory = AdmissionFixtures.directory();
        seed = AdmissionFixtures.policy(issuer.origin().toString(), backend.origin()); var bootstrap = directory.resolve("bootstrap.json");
        Files.writeString(bootstrap, AdmissionFixtures.JSON.writeValueAsString(seed));
        var args = new ArrayList<>(List.of("--spring.profiles.active=workers,secured,uc01", "--server.port=0",
                "--gateway.backends.worker-coordinator.base-url=" + backend.origin(), "--gateway.backends.worker-coordinator.bearer-token=forbidden-shared-fallback",
                "--gateway-security.issuer=" + issuer.origin(), "--gateway-security.jwk-set-uri=" + issuer.origin() + "/jwks",
                "--gateway-security.resource=" + RESOURCE, "--gateway-security.allow-loopback-http=true", "--gateway-security.policy-location=file:./examples/tenant-policy.json",
                "--gateway.upstream.connect-timeout=200ms", "--gateway.upstream.deadline=700ms",
                "--admission.bootstrap=" + bootstrap.toAbsolutePath(), "--admission.journal-directory=" + directory.resolve("journals").toAbsolutePath()));
        for (int n = 0; n < 50; n++) args.addAll(List.of("--admission.credentials.credential-" + n + ".token=isolated-" + n + "-canary",
                "--admission.credentials.credential-" + n + ".audience=" + backend.origin(), "--admission.credentials.credential-" + n + ".expires-at=" + Instant.now().plusSeconds(600)));
        app = new SpringApplicationBuilder(McpGatewayApplication.class).run(args.toArray(String[]::new));
        origin = "http://127.0.0.1:" + ((WebServerApplicationContext) app).getWebServer().getPort();
    }
    @AfterEach void stop() throws Exception {
        try { for (var client : clients) try { client.closeGracefully().toFuture().get(2, TimeUnit.SECONDS); } catch (Exception ignored) { } }
        finally { if (app != null) app.close(); if (backend != null) backend.close(); if (issuer != null) issuer.close(); }
    }
    private String token(int n, Map<String, Object> overrides) throws Exception {
        var claims = new HashMap<String, Object>(Map.of("application", n % 2 == 0 ? "application-alpha" : "application-beta",
                "organization", "organization-north", "execution_mode", "HUMAN_DELEGATED", "delegation", "delegation-" + n, "agent_run", "run-" + n));
        claims.putAll(overrides); return issuer.token("subject-" + n, n % 2 == 0 ? "tenant-blue" : "tenant-green", "gateway:read", RESOURCE, claims, false);
    }
    private String console(String human) throws Exception {
        String subject = human.equals("security-admin") ? "admin-subject" : human.equals("publisher") ? "publisher-subject" : "subject-0";
        return issuer.token(subject, "tenant-blue", "gateway:admin gateway:audit", RESOURCE, Map.of("application", "console", "organization", "organization-north",
                "execution_mode", "ADMINISTRATION", "agent_run", "admin-run"), false);
    }
    private McpAsyncClient connect(String token) throws Exception {
        var session = new java.util.concurrent.atomic.AtomicReference<String>();
        var transport = HttpClientStreamableHttpTransport.builder(origin).endpoint("/worker-coordinator/mcp")
                .requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer " + token))
                .httpRequestCustomizer((builder, method, uri, body, context) -> builder.build().headers().firstValue("Mcp-Session-Id").ifPresent(session::set)).build();
        var client = McpClient.async(transport).requestTimeout(Duration.ofSeconds(4)).build(); clients.add(client);
        sessions.put(client, session);
        client.initialize().toFuture().get(5, TimeUnit.SECONDS); return client;
    }
    private McpSchema.CallToolResult call(McpAsyncClient client, String name, Map<String, Object> args) throws Exception {
        return client.callTool(new McpSchema.CallToolRequest(name, args, null)).toFuture().get(5, TimeUnit.SECONDS);
    }
    private void denied(McpSchema.CallToolResult result, String code) {
        assertThat(result.isError()).isTrue(); assertThat(result.structuredContent()).isNull();
        assertThat(((McpSchema.TextContent) result.content().getFirst()).text()).contains(code, "not_attempted");
    }
    private void reply(int n) { backend.enqueue(IndependentHttpMock.Reply.json("{\"productId\":\"item-" + n + "\",\"productName\":\"Inventory\",\"status\":\"ACTIVE\",\"privateSecret\":\"withheld\"}")); }
    private HttpResponse<String> change(String actor, Map<String, Object> change) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(origin + "/control/changes")).timeout(Duration.ofSeconds(4))
                .header("Authorization", "Bearer " + console(actor)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(AdmissionFixtures.JSON.writeValueAsString(change))).build(), HttpResponse.BodyHandlers.ofString());
    }
    private long revision() throws Exception { return app.getBean(AdmissionJournal.class).readPolicy().revision(); }
    private HttpResponse<String> audit(String token) throws Exception {
        return HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(origin + "/control/audit")).timeout(Duration.ofSeconds(4))
                .header("Authorization", "Bearer " + token).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test void filtersPerUserAndEnforcesDirectCallsWithIsolatedCredentialsAndDurableAudit(CapturedOutput output) throws Exception {
        String t0 = token(0, Map.of()); var first = connect(t0); var second = connect(token(2, Map.of()));
        assertThat(first.listTools().toFuture().get(4, TimeUnit.SECONDS).tools()).extracting(McpSchema.Tool::name).containsExactly("get_product");
        assertThat(second.listTools().toFuture().get(4, TimeUnit.SECONDS).tools()).extracting(McpSchema.Tool::name).containsExactly("get_product", "get_service");
        assertThat(backend.count()).isZero();
        denied(call(first, "get_service", Map.of("serviceId", "item-0")), "ACCESS_DENIED");
        denied(call(first, "get_product", Map.of("productId", "item-2")), "ACCESS_DENIED");
        denied(call(first, "register_product", Map.of("productId", "item-0", "productName", "New")), "ACCESS_DENIED");
        reply(0); var result = call(first, "get_product", Map.of("productId", "item-0"));
        assertThat(result.isError()).isFalse(); assertThat(((Map<?, ?>) result.structuredContent()).containsKey("privateSecret")).isFalse();
        var request = backend.takeRequest(); assertThat(request.header("Authorization")).isEqualTo("Bearer isolated-0-canary");
        assertThat(request.headers().toString()).doesNotContain(t0, "subject-0", "organization-north", "forbidden-shared-fallback");
        assertThat(backend.count()).isEqualTo(1);
        backend.enqueue(IndependentHttpMock.Reply.json("{\"productId\":\"item-0\",\"productName\":\"isolated-0-canary\",\"status\":\"ACTIVE\"}"));
        var echoed = call(first, "get_product", Map.of("productId", "item-0"));
        assertThat(echoed.isError()).isTrue(); assertThat(echoed.structuredContent()).isNull();
        assertThat(echoed.content().toString()).contains("UPSTREAM_INVALID_RESPONSE").doesNotContain("isolated-0-canary");
        assertThat(backend.count()).isEqualTo(2);
        var records = app.getBean(AdmissionJournal.class).readAudits();
        assertThat(records.stream().filter(r -> "ADMITTED".equals(r.get("decision"))).count()).isEqualTo(2);
        assertThat(records.stream().filter(r -> "SUCCESS".equals(r.get("outcome"))).count()).isEqualTo(1);
        assertThat(AdmissionFixtures.JSON.writeValueAsString(records)).doesNotContain(t0, "isolated-0-canary", "privateSecret");
        assertThat(output.getAll()).doesNotContain(t0, "isolated-0-canary", "subject-0");
    }
    @Test void membershipRemovalIsImmediateAndDoesNotAffectOtherCatalogs() throws Exception {
        var alpha = connect(token(0, Map.of())); var beta = connect(token(1, Map.of()));
        var response = change("human-0", Map.of("expectedRevision", revision(), "action", "MEMBERSHIP", "catalog", "catalog-alpha", "capability", "inventory.lookup@1", "version", "1", "state", "REMOVE", "purpose", "inventory-read", "reason", "owner removal"));
        assertThat(response.statusCode()).isEqualTo(200); assertThat(response.body()).contains("\"effective\":true");
        assertThat(alpha.listTools().toFuture().get(4, TimeUnit.SECONDS).tools()).isEmpty();
        denied(call(alpha, "get_product", Map.of("productId", "item-0")), "ACCESS_DENIED");
        reply(1); assertThat(call(beta, "get_product", Map.of("productId", "item-1")).isError()).isFalse(); assertThat(backend.count()).isEqualTo(1);
    }
    @Test void publisherCannotGrantAndSecurityAdministratorCannotSelfActivateOrSelfGrant() throws Exception {
        var g = new AdmissionPolicy.Grant("HUMAN", "human-0", "tenant-blue", "inventory.lookup@1", "1", "READ", "item-0");
        assertThat(change("publisher", Map.of("expectedRevision", revision(), "action", "GRANT", "grant", g, "reason", "unauthorized" )).statusCode()).isEqualTo(403);
        var self = new AdmissionPolicy.Grant("HUMAN", "security-admin", "tenant-blue", "inventory.lookup@1", "1", "READ", "item-0");
        assertThat(change("security-admin", Map.of("expectedRevision", revision(), "action", "GRANT", "grant", self, "reason", "self grant")).statusCode()).isEqualTo(403);
        assertThat(change("security-admin", Map.of("expectedRevision", revision(), "action", "HUMAN", "target", "security-admin", "human", seed.humans().get("security-admin"), "reason", "self activation")).statusCode()).isEqualTo(403);
        assertThat(revision()).isEqualTo(1); assertThat(backend.count()).isZero();
    }
    @Test void revocationBlocksExistingSessionsAndWorkSubscribedAfterRevocation() throws Exception {
        var reader = connect(token(0, Map.of())); reply(0); assertThat(call(reader, "get_product", Map.of("productId", "item-0")).isError()).isFalse();
        var queued = reader.callTool(new McpSchema.CallToolRequest("get_product", Map.of("productId", "item-0"), null));
        long start = System.nanoTime();
        assertThat(change("security-admin", Map.of("expectedRevision", revision(), "action", "REVOKE_DELEGATION", "target", "delegation-0", "reason", "delegation revoked")).statusCode()).isEqualTo(200);
        denied(queued.toFuture().get(4, TimeUnit.SECONDS), "ACCESS_DENIED");
        denied(call(reader, "get_product", Map.of("productId", "item-0")), "ACCESS_DENIED");
        assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(Duration.ofSeconds(60)); assertThat(backend.count()).isEqualTo(1);
    }
    @Test void auditFailurePreventsDispatchAndReadsNeverRetryUpstreamFailures() throws Exception {
        var reader = connect(token(0, Map.of()));
        backend.enqueue(new IndependentHttpMock.Reply(503, "unavailable", null, IndependentHttpMock.Mode.NORMAL));
        var result = call(reader, "get_product", Map.of("productId", "item-0"));
        assertThat(result.isError()).isTrue(); assertThat(backend.count()).isEqualTo(1); assertThat(backend.pollRequest(10)).isNotNull(); assertThat(backend.pollRequest(300)).isNull();
        app.getBean(AdmissionJournal.class).close();
        denied(call(reader, "get_product", Map.of("productId", "item-0")), "VERIFICATION_UNAVAILABLE"); assertThat(backend.count()).isEqualTo(1);
    }
    @Test void rejectsContextForgeryWrongAudienceAndMissingDelegationWithZeroBusinessCalls() throws Exception {
        var forged = connect(token(0, Map.of("application", "unknown-application"))); denied(call(forged, "get_product", Map.of("productId", "item-0")), "ACCESS_DENIED");
        var mixed = connect(token(0, Map.of("organization", "organization-south"))); denied(call(mixed, "get_product", Map.of("productId", "item-0")), "ACCESS_DENIED");
        var absent = connect(token(0, Map.of("delegation", "unknown-delegation"))); denied(call(absent, "get_product", Map.of("productId", "item-0")), "ACCESS_DENIED");
        String wrong = issuer.token("subject-0", "tenant-blue", "gateway:read", "wrong-recipient");
        var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(origin + "/worker-coordinator/mcp")).header("Authorization", "Bearer " + wrong).GET().build(), HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).isEqualTo(401); assertThat(backend.count()).isZero();
    }
    @Test void scopesAuditAccessAndPreservesPinnedContracts() throws Exception {
        assertThat(audit(token(0, Map.of())).statusCode()).isEqualTo(403);
        var reader = connect(token(0, Map.of())); reply(0); call(reader, "get_product", Map.of("productId", "item-0"));
        var cap = seed.capabilities().get("inventory.lookup@1");
        var v2 = new AdmissionPolicy.Capability(cap.server(), cap.tool(), "2", "PENDING", cap.classification(), cap.resourceArgument(), cap.inputSchema(), cap.outputSchema(), cap.fingerprint());
        assertThat(change("security-admin", Map.of("expectedRevision", revision(), "action", "CONTRACT", "target", "inventory.lookup@2", "contract", v2, "reason", "register new version")).statusCode()).isEqualTo(200);
        assertThat(change("publisher", Map.of("expectedRevision", revision(), "action", "PUBLICATION", "target", "inventory.lookup@2", "state", "ACTIVE", "reason", "publish new version")).statusCode()).isEqualTo(200);
        var current = app.getBean(AdmissionJournal.class).readPolicy(); assertThat(current.catalogs().get("catalog-alpha").memberships()).doesNotContainKey("inventory.lookup@2");
        assertThat(current.catalogs().get("catalog-alpha").memberships().get("inventory.lookup@1").version()).isEqualTo("1");
        assertThat(change("publisher", Map.of("expectedRevision", revision(), "action", "PUBLICATION", "target", "inventory.lookup@1", "state", "RETIRED", "reason", "retire original version")).statusCode()).isEqualTo(200);
        denied(call(reader, "get_product", Map.of("productId", "item-0")), "ACCESS_DENIED");
        var export = audit(console("security-admin")); assertThat(export.statusCode()).isEqualTo(200); assertThat(export.body()).contains("ADMITTED", "OUTCOME", "ADMIN_EFFECTIVE").doesNotContain("tenant-green", "isolated-0-canary");
        assertThat(backend.count()).isEqualTo(1);
    }
    @Test void isolatesOneHundredInterleavedCallsAcrossFiftyUsersTwoApplicationsAndTwoTenants() throws Exception {
        var readers = new ArrayList<McpAsyncClient>(); for (int n = 0; n < 50; n++) readers.add(connect(token(n, Map.of())));
        // Identical public reply avoids coupling authorization verification to mock response ordering.
        for (int n = 0; n < 100; n++) backend.enqueue(IndependentHttpMock.Reply.json("{\"productId\":\"public-item\",\"productName\":\"Inventory\",\"status\":\"ACTIVE\"}"));
        var futures = new ArrayList<java.util.concurrent.CompletableFuture<McpSchema.CallToolResult>>();
        for (int n = 0; n < 100; n++) futures.add(readers.get(n % 50).callTool(new McpSchema.CallToolRequest("get_product", Map.of("productId", "item-" + n % 50), null)).toFuture());
        for (var future : futures) assertThat(future.get(15, TimeUnit.SECONDS).isError()).isFalse();
        assertThat(backend.count()).isEqualTo(100);
        for (int n = 0; n < 100; n++) { var request = backend.takeRequest(); int index = Integer.parseInt(request.path().substring(request.path().lastIndexOf('-') + 1)); assertThat(request.header("Authorization")).isEqualTo("Bearer isolated-" + index + "-canary"); }
        var admissions = app.getBean(AdmissionJournal.class).readAudits().stream().filter(r -> "ADMITTED".equals(r.get("decision"))).toList();
        assertThat(admissions).hasSize(100);
        for (var event : admissions) { int n = Integer.parseInt(((String) event.get("human")).substring(6));
            assertThat(event).containsEntry("application", n % 2 == 0 ? "application-alpha" : "application-beta").containsEntry("tenant", n % 2 == 0 ? "tenant-blue" : "tenant-green").containsEntry("resource", "item-" + n); }
    }

    private void fixturePolicy(AdmissionPolicy fixture) throws Exception {
        // Controlled authoritative fault injection; administration behavior has separate HTTP tests.
        app.getBean(AdmissionJournal.class).writePolicy(new AdmissionPolicy(revision() + 1, fixture.organizations(), fixture.tenants(), fixture.humans(), fixture.applications(), fixture.servers(), fixture.capabilities(), fixture.catalogs(), fixture.delegations(), fixture.grants(), fixture.credentials(), fixture.resources()));
    }
    @Test void failsClosedForLifecycleTrustClassificationContractAndCredentialChanges() throws Exception {
        var reader = connect(token(0, Map.of()));
        for (String status : Set.of("PENDING", "SUSPENDED", "RETIRED")) {
            var people = new HashMap<>(seed.humans()); var h = people.get("human-0");
            people.put("human-0", new AdmissionPolicy.Human(h.authority(), h.subject(), h.displayName(), status, h.tenants(), h.roles()));
            fixturePolicy(seed.next(people, seed.applications(), seed.servers(), seed.capabilities(), seed.catalogs(), seed.delegations(), seed.grants()));
            denied(call(reader, "get_product", Map.of("productId", "item-0")), "ACCESS_DENIED");
            var apps = new HashMap<>(seed.applications()); var a = apps.get("application-alpha");
            apps.put("application-alpha", new AdmissionPolicy.Application(a.organization(), a.owner(), status, a.tenants(), a.administration()));
            fixturePolicy(seed.next(seed.humans(), apps, seed.servers(), seed.capabilities(), seed.catalogs(), seed.delegations(), seed.grants()));
            denied(call(reader, "get_product", Map.of("productId", "item-0")), "ACCESS_DENIED");
        }
        for (String classification : Set.of("UNCLASSIFIED", "SENSITIVE_READ", "MUTATION")) {
            var caps = new HashMap<>(seed.capabilities()); var cap = caps.get("inventory.lookup@1");
            caps.put("inventory.lookup@1", new AdmissionPolicy.Capability(cap.server(), cap.tool(), cap.version(), cap.status(), classification, cap.resourceArgument(), cap.inputSchema(), cap.outputSchema(), cap.fingerprint()));
            fixturePolicy(seed.next(seed.humans(), seed.applications(), seed.servers(), caps, seed.catalogs(), seed.delegations(), seed.grants()));
            assertThat(reader.listTools().toFuture().get(4, TimeUnit.SECONDS).tools()).isEmpty(); denied(call(reader, "get_product", Map.of("productId", "item-0")), "ACCESS_DENIED");
        }
        var servers = new HashMap<>(seed.servers()); var s = servers.get("coordinator");
        servers.put("coordinator", new AdmissionPolicy.Server(s.organization(), s.owner(), s.status(), "THIRD_PARTY", s.backendRef(), s.origin()));
        fixturePolicy(seed.next(seed.humans(), seed.applications(), servers, seed.capabilities(), seed.catalogs(), seed.delegations(), seed.grants()));
        denied(call(reader, "get_product", Map.of("productId", "item-0")), "ACCESS_DENIED");
        servers.put("coordinator", new AdmissionPolicy.Server(s.organization(), s.owner(), s.status(), s.trust(), s.backendRef(), URI.create("https://unapproved.example")));
        fixturePolicy(seed.next(seed.humans(), seed.applications(), servers, seed.capabilities(), seed.catalogs(), seed.delegations(), seed.grants()));
        denied(call(reader, "get_product", Map.of("productId", "item-0")), "ACCESS_DENIED");
        var caps = new HashMap<>(seed.capabilities()); var cap = caps.get("inventory.lookup@1");
        caps.put("inventory.lookup@1", new AdmissionPolicy.Capability(cap.server(), cap.tool(), cap.version(), cap.status(), cap.classification(), cap.resourceArgument(), cap.inputSchema(), cap.outputSchema(), "0".repeat(64)));
        fixturePolicy(seed.next(seed.humans(), seed.applications(), seed.servers(), caps, seed.catalogs(), seed.delegations(), seed.grants()));
        denied(call(reader, "get_product", Map.of("productId", "item-0")), "ACCESS_DENIED");
        fixturePolicy(seed.withCredentials(Set.of())); denied(call(reader, "get_product", Map.of("productId", "item-0")), "VERIFICATION_UNAVAILABLE");
        assertThat(backend.count()).isZero();
    }
    @Test void currentGrantIntersectionDelegationExpiryAndSensitiveMembershipAreEnforced() throws Exception {
        var reader = connect(token(0, Map.of()));
        for (String kind : Set.of("APPLICATION", "HUMAN")) {
            var grants = new HashSet<>(seed.grants()); grants.removeIf(g -> g.kind().equals(kind) && g.resource().equals("item-0"));
            fixturePolicy(seed.next(seed.humans(), seed.applications(), seed.servers(), seed.capabilities(), seed.catalogs(), seed.delegations(), grants));
            denied(call(reader, "get_product", Map.of("productId", "item-0")), "ACCESS_DENIED");
        }
        var ds = new HashMap<>(seed.delegations()); var d = ds.get("delegation-0");
        ds.put("delegation-0", new AdmissionPolicy.Delegation(d.organization(), d.tenant(), d.human(), d.application(), Instant.now().minusSeconds(61), Instant.now().minusSeconds(1), false, d.capabilities(), d.resources()));
        fixturePolicy(seed.next(seed.humans(), seed.applications(), seed.servers(), seed.capabilities(), seed.catalogs(), ds, seed.grants()));
        denied(call(reader, "get_product", Map.of("productId", "item-0")), "ACCESS_DENIED");
        var cap = seed.capabilities().get("inventory.lookup@1");
        var sensitive = new AdmissionPolicy.Capability(cap.server(), cap.tool(), "sensitive-1", "PENDING", "SENSITIVE_READ", cap.resourceArgument(), cap.inputSchema(), cap.outputSchema(), cap.fingerprint());
        fixturePolicy(seed);
        assertThat(change("security-admin", Map.of("expectedRevision", revision(), "action", "CONTRACT", "target", "inventory.sensitive@1", "contract", sensitive, "reason", "new sensitive contract")).statusCode()).isEqualTo(200);
        assertThat(change("publisher", Map.of("expectedRevision", revision(), "action", "PUBLICATION", "target", "inventory.sensitive@1", "state", "ACTIVE", "reason", "publish sensitive contract")).statusCode()).isEqualTo(200);
        assertThat(change("human-0", Map.of("expectedRevision", revision(), "action", "MEMBERSHIP", "catalog", "catalog-alpha", "capability", "inventory.sensitive@1", "version", "sensitive-1", "state", "REQUEST", "purpose", "review", "reason", "request membership")).statusCode()).isEqualTo(200);
        assertThat(app.getBean(AdmissionJournal.class).readPolicy().catalogs().get("catalog-alpha").memberships().get("inventory.sensitive@1").status()).isEqualTo("PENDING");
        assertThat(backend.count()).isZero();
    }
    @Test void administrativeSuspensionRetirementAndCredentialRevocationAreEffective() throws Exception {
        var reader = connect(token(0, Map.of())); var h = seed.humans().get("human-0");
        var retired = new AdmissionPolicy.Human(h.authority(), h.subject(), "Renamed owner", "RETIRED", h.tenants(), h.roles());
        assertThat(change("security-admin", Map.of("expectedRevision", revision(), "action", "HUMAN", "target", "human-0", "human", retired, "reason", "retire identity")).statusCode()).isEqualTo(200);
        denied(call(reader, "get_product", Map.of("productId", "item-0")), "ACCESS_DENIED");
        assertThat(change("security-admin", Map.of("expectedRevision", revision(), "action", "HUMAN", "target", "human-0", "human", h, "reason", "attempt ID reuse")).statusCode()).isEqualTo(403);
        fixturePolicy(seed);
        var ref = seed.credentials().stream().filter(c -> c.human().equals("human-0")).findFirst().orElseThrow();
        assertThat(change("security-admin", Map.of("expectedRevision", revision(), "action", "REVOKE_CREDENTIAL", "credential", ref, "reason", "revoke downstream credential")).statusCode()).isEqualTo(200);
        denied(call(reader, "get_product", Map.of("productId", "item-0")), "VERIFICATION_UNAVAILABLE");
        assertThat(backend.count()).isZero();
    }
    @Test void sessionOwnershipAndUntrustedMetadataCannotChangeAuthority() throws Exception {
        var reader = connect(token(0, Map.of())); reader.listTools().toFuture().get(4, TimeUnit.SECONDS);
        String session = sessions.get(reader).get(); assertThat(session).isNotBlank();
        for (String foreign : List.of(token(2, Map.of()), token(0, Map.of("application", "application-beta")))) {
            for (String method : List.of("GET", "DELETE")) {
                var response = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create(origin + "/worker-coordinator/mcp"))
                        .timeout(Duration.ofSeconds(3)).header("Authorization", "Bearer " + foreign).header("Mcp-Session-Id", session)
                        .header("Accept", "application/json, text/event-stream").method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
                assertThat(response.statusCode()).isEqualTo(403);
            }
        }
        var forged = new McpSchema.CallToolRequest("get_product", Map.of("productId", "item-2"), Map.of("subject", "subject-2", "delegation", "delegation-2", "tenant", "tenant-green"));
        denied(reader.callTool(forged).toFuture().get(4, TimeUnit.SECONDS), "ACCESS_DENIED");
        denied(call(reader, "get_product", Map.of("productId", "item-0", "userId", "subject-2")), "INVALID_ARGUMENTS");
        var nullArguments = new HashMap<String, Object>(); nullArguments.put("productId", null);
        denied(call(reader, "get_product", nullArguments), "INVALID_ARGUMENTS");
        assertThat(backend.count()).isZero();
    }
    @Test void consoleAuthorityCannotInvokeToolsOrRenewSuspendedHumanDelegation() throws Exception {
        var console = connect(console("security-admin"));
        denied(call(console, "get_product", Map.of("productId", "item-0")), "ACCESS_DENIED");
        var h = seed.humans().get("human-0");
        var suspended = new AdmissionPolicy.Human(h.authority(), h.subject(), h.displayName(), "SUSPENDED", h.tenants(), h.roles());
        assertThat(change("security-admin", Map.of("expectedRevision", revision(), "action", "HUMAN", "target", "human-0", "human", suspended, "reason", "suspend principal")).statusCode()).isEqualTo(200);
        var d = seed.delegations().get("delegation-0");
        var renewed = new AdmissionPolicy.Delegation(d.organization(), d.tenant(), d.human(), d.application(), Instant.now(), Instant.now().plusSeconds(600), false, d.capabilities(), d.resources());
        assertThat(change("security-admin", Map.of("expectedRevision", revision(), "action", "DELEGATION", "target", "renewal-after-suspension", "delegation", renewed, "reason", "renew delegation")).statusCode()).isEqualTo(403);
        assertThat(audit(console("security-admin")).statusCode()).isEqualTo(200); assertThat(backend.count()).isZero();
    }
    @Test void missingOwnershipFailsVerificationAndCrossTenantOwnershipIsDenied() throws Exception {
        var reader = connect(token(0, Map.of()));
        var resource = seed.resources().stream().filter(r -> r.capability().equals("inventory.lookup@1") && r.resource().equals("item-0")).findFirst().orElseThrow();
        assertThat(change("security-admin", Map.of("expectedRevision", revision(), "action", "REVOKE_RESOURCE", "resource", resource, "reason", "withdraw verified ownership")).statusCode()).isEqualTo(200);
        denied(call(reader, "get_product", Map.of("productId", "item-0")), "VERIFICATION_UNAVAILABLE");
        var ownership = new HashSet<>(seed.resources()); ownership.remove(resource);
        ownership.add(new AdmissionPolicy.ResourceScope(resource.organization(), "tenant-green", resource.server(), resource.capability(), resource.version(), resource.resource()));
        fixturePolicy(seed.withResources(ownership));
        denied(call(reader, "get_product", Map.of("productId", "item-0")), "ACCESS_DENIED"); assertThat(backend.count()).isZero();
    }
}
