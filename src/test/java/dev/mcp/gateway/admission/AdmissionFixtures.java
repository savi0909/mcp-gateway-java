package dev.mcp.gateway.admission;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.*;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.json.JsonMapper;

final class AdmissionFixtures {
    static final JsonMapper JSON = JsonMapper.builder().build();
    static Path directory() throws Exception { return Files.createDirectories(Path.of("target", "uc01-fixtures", UUID.randomUUID().toString())); }
    static AdmissionPolicy policy(String authority) throws Exception { return policy(authority, java.net.URI.create("https://business.example")); }
    static AdmissionPolicy policy(String authority, java.net.URI origin) throws Exception {
        var humans = new HashMap<String, AdmissionPolicy.Human>(); var delegates = new HashMap<String, AdmissionPolicy.Delegation>();
        var resources = new HashSet<AdmissionPolicy.ResourceScope>(); var grants = new HashSet<AdmissionPolicy.Grant>(); var credentials = new HashSet<AdmissionPolicy.CredentialScope>();
        for (int n = 0; n < 50; n++) {
            String h = "human-" + n, app = n % 2 == 0 ? "application-alpha" : "application-beta", tenant = n % 2 == 0 ? "tenant-blue" : "tenant-green", resource = "item-" + n;
            humans.put(h, new AdmissionPolicy.Human(authority, "subject-" + n, "Display " + n, "ACTIVE", Set.of(tenant),
                    n == 0 ? Map.of(tenant, Set.of("CATALOG_OWNER")) : Map.of()));
            delegates.put("delegation-" + n, new AdmissionPolicy.Delegation("organization-north", tenant, h, app, Instant.now().minusSeconds(1), Instant.now().plusSeconds(600), false,
                    n == 2 ? Set.of("inventory.lookup@1", "service.lookup@1") : Set.of("inventory.lookup@1"), Set.of(resource)));
            for (String cap : n == 2 ? Set.of("inventory.lookup@1", "service.lookup@1") : Set.of("inventory.lookup@1")) {
                resources.add(new AdmissionPolicy.ResourceScope("organization-north", tenant, "coordinator", cap, "1", resource));
                grants.add(new AdmissionPolicy.Grant("HUMAN", h, tenant, cap, "1", "READ", resource));
                grants.add(new AdmissionPolicy.Grant("APPLICATION", app, tenant, cap, "1", "READ", resource));
            }
            credentials.add(new AdmissionPolicy.CredentialScope("organization-north", tenant, app, h, "coordinator", "READ", "credential-" + n));
        }
        humans.put("security-admin", new AdmissionPolicy.Human(authority, "admin-subject", "Administrator", "ACTIVE", Set.of("tenant-blue"),
                Map.of("tenant-blue", Set.of("SECURITY_ADMIN", "PUBLISHER", "AUDIT_VIEWER"))));
        humans.put("publisher", new AdmissionPolicy.Human(authority, "publisher-subject", "Publisher", "ACTIVE", Set.of("tenant-blue"), Map.of("tenant-blue", Set.of("PUBLISHER"))));
        var apps = Map.of("application-alpha", new AdmissionPolicy.Application("organization-north", "human-0", "ACTIVE", Set.of("tenant-blue"), false),
                "application-beta", new AdmissionPolicy.Application("organization-north", "human-1", "ACTIVE", Set.of("tenant-green"), false),
                "console", new AdmissionPolicy.Application("organization-north", "security-admin", "ACTIVE", Set.of("tenant-blue"), true));
        for (String h : Set.of("security-admin", "publisher", "human-0")) delegates.put("console-" + h,
                new AdmissionPolicy.Delegation("organization-north", "tenant-blue", h, "console", Instant.now().minusSeconds(1), Instant.now().plusSeconds(600), false, Set.of(), Set.of()));
        var root = JSON.readTree(Files.readString(Path.of("src/main/resources/catalog/coordinator-catalog.json")));
        var caps = new HashMap<String, AdmissionPolicy.Capability>();
        for (var node : root.path("servers").get(0).path("tools")) {
            String name = node.path("name").stringValue();
            if (Set.of("get_product", "get_service").contains(name)) caps.put(name.equals("get_product") ? "inventory.lookup@1" : "service.lookup@1",
                    new AdmissionPolicy.Capability("coordinator", name, "1", "ACTIVE", "ORDINARY_READ", name.equals("get_product") ? "productId" : "serviceId",
                            JSON.convertValue(node.path("inputSchema"), new TypeReference<Map<String, Object>>() {}), JSON.convertValue(node.path("outputSchema"), new TypeReference<Map<String, Object>>() {}), fingerprint(name)));
        }
        var member = new AdmissionPolicy.Membership("inventory.lookup@1", "1", "ACTIVE", "human-0", "inventory-read");
        return new AdmissionPolicy(1, Map.of("organization-north", "ACTIVE", "organization-south", "ACTIVE"),
                Map.of("tenant-blue", new AdmissionPolicy.Tenant("organization-north", "ACTIVE"), "tenant-green", new AdmissionPolicy.Tenant("organization-north", "ACTIVE"), "tenant-south", new AdmissionPolicy.Tenant("organization-south", "ACTIVE")),
                humans, apps, Map.of("coordinator", new AdmissionPolicy.Server("organization-north", "security-admin", "ACTIVE", "INTERNAL", "worker-coordinator", origin)), caps,
                Map.of("catalog-alpha", new AdmissionPolicy.Catalog("application-alpha", "human-0", "test", Map.of("inventory.lookup@1", member,
                        "service.lookup@1", new AdmissionPolicy.Membership("service.lookup@1", "1", "ACTIVE", "human-0", "service-read"))),
                        "catalog-beta", new AdmissionPolicy.Catalog("application-beta", "human-1", "test", Map.of("inventory.lookup@1", new AdmissionPolicy.Membership(member.capability(), member.version(), member.status(), "human-1", member.purpose())))), delegates, grants, credentials, resources);
    }
    private static String fingerprint(String name) {
        return definitions().stream().filter(t -> t.name().equals(name)).map(ContractFingerprint::of).findFirst().orElseThrow();
    }
    static List<dev.mcp.gateway.catalog.ToolDefinition> definitions() {
        var properties = new dev.mcp.gateway.config.GatewayProperties("classpath:catalog/coordinator-catalog.json",
                Map.of("worker-coordinator", new dev.mcp.gateway.config.GatewayProperties.Backend(java.net.URI.create("https://business.example"), "")),
                new dev.mcp.gateway.config.GatewayProperties.Upstream(java.time.Duration.ofMillis(200), java.time.Duration.ofSeconds(1), 8192), List.of());
        var env = new org.springframework.mock.env.MockEnvironment().withProperty("spring.ai.mcp.server.name", "worker-coordinator")
                .withProperty("spring.ai.mcp.server.streamable-http.mcp-endpoint", "/worker-coordinator/mcp");
        return new dev.mcp.gateway.catalog.FileCatalogLoader().load(properties, new org.springframework.core.io.DefaultResourceLoader(), env);
    }

}
