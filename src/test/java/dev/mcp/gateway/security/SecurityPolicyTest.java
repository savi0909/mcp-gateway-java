package dev.mcp.gateway.security;

import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.DefaultResourceLoader;
import org.springframework.core.io.Resource;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static org.assertj.core.api.Assertions.*;

class SecurityPolicyTest {
    private final JsonMapper json = JsonMapper.builder().build();
    @Test void requiresCompleteSettingsAndExplicitHttpException() {
        URI issuer = URI.create("https://issuer.example"), jwks = URI.create("https://issuer.example/jwks"),
                resource = URI.create("https://gateway.example/worker-coordinator/mcp");
        assertThatCode(() -> new SecuritySettings(true, issuer, jwks, resource, "file:policy.json", false)).doesNotThrowAnyException();
        assertThatThrownBy(() -> new SecuritySettings(true, null, jwks, resource, "file:policy.json", false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SecuritySettings(true, issuer, jwks, resource, "", false)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new SecuritySettings(true, issuer, null, resource, "file:policy.json", false)).isInstanceOf(IllegalArgumentException.class);
        for (String invalid : new String[]{"http://issuer.example", "https://user@issuer.example", "https://issuer.example?q=secret",
                "https://issuer.example#fragment", "http://127.0.0.1:1234"}) {
            assertThatThrownBy(() -> new SecuritySettings(true, URI.create(invalid), jwks, resource, "file:policy.json", false))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new SecuritySettings(true, issuer, jwks, URI.create("https://gateway.example/other"), "file:policy.json", false))
                .isInstanceOf(IllegalArgumentException.class);
    }
    @Test void rejectsAmbiguousPolicyAndKeepsSnapshotsImmutable() throws Exception {
        String original = Files.readString(Path.of("examples/tenant-policy.json"));
        for (String change : new String[]{"overlap", "parent", "version", "unknown", "owner", "region"}) {
            var root = (ObjectNode) json.readTree(original);
            var a = (ObjectNode) root.path("tenants").path("tenant-a");
            switch (change) {
                case "overlap" -> ((ObjectNode) root.path("tenants").path("tenant-b")).set("products", json.readTree("[\"p\"]"));
                case "parent" -> ((ObjectNode) a.path("services")).put("s", "not-owned");
                case "version" -> root.put("schemaVersion", 99);
                case "unknown" -> root.put("unexpected", "canary-policy-value");
                case "owner" -> ((ObjectNode) a.path("subjects").path("alice")).put("instanceId", "bad-uuid");
                case "region" -> a.set("regions", json.readTree("[16]"));
            }
            assertThatThrownBy(() -> load(json.writeValueAsString(root))).isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Security policy is missing or invalid");
        }
        assertThatThrownBy(() -> load("{\"schemaVersion\":1,\"schemaVersion\":1,\"tenants\":{}}"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> load("x".repeat(262145))).isInstanceOf(IllegalArgumentException.class);
        var policy = load(original);
        assertThatThrownBy(policy.tenants()::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThatThrownBy(policy.tenants().get("tenant-a").products()::clear).isInstanceOf(UnsupportedOperationException.class);
        assertThat(policy.toString()).doesNotContain("alice", "tenant-a");
        assertThat(policy.knows(new Caller("issuer", "alice", "tenant-b", Set.of("gateway:read"), Instant.now().plusSeconds(30)))).isFalse();
    }
    private TenantPolicy load(String content) {
        return TenantPolicy.load("file:fixture", new DefaultResourceLoader() {
            @Override public Resource getResource(String location) { return new ByteArrayResource(content.getBytes(java.nio.charset.StandardCharsets.UTF_8)); }
        });
    }
}
