package dev.mcp.gateway.admission;

import java.time.Instant;
import java.util.Map;
import java.util.Set;

/** Private authoritative policy; not an MCP discovery document. */
public record AdmissionPolicy(long revision, Map<String, String> organizations, Map<String, Tenant> tenants,
        Map<String, Human> humans, Map<String, Application> applications, Map<String, Server> servers,
        Map<String, Capability> capabilities, Map<String, Catalog> catalogs, Map<String, Delegation> delegations,
        Set<Grant> grants, Set<CredentialScope> credentials, Set<ResourceScope> resources) {
    public AdmissionPolicy {
        require(revision > 0);
        organizations = Map.copyOf(organizations); tenants = Map.copyOf(tenants); humans = Map.copyOf(humans);
        applications = Map.copyOf(applications); servers = Map.copyOf(servers); capabilities = Map.copyOf(capabilities);
        catalogs = Map.copyOf(catalogs); delegations = Map.copyOf(delegations);
        grants = Set.copyOf(grants); credentials = Set.copyOf(credentials); resources = Set.copyOf(resources);
        require(organizations.size() <= 64 && tenants.size() <= 128 && humans.size() <= 4096
                && applications.size() <= 128 && capabilities.size() <= 1024 && delegations.size() <= 4096);
        organizations.values().forEach(AdmissionPolicy::status);
        for (var t : tenants.values()) { status(t.status()); require(organizations.containsKey(t.organization())); }
        var subjects = new java.util.HashSet<java.util.List<String>>();
        for (var h : humans.values()) {
            status(h.status()); require(h.authority() != null && !h.authority().isBlank() && h.subject() != null && !h.subject().isBlank());
            require(subjects.add(java.util.List.of(h.authority(), h.subject())));
            for (String tenant : h.tenants()) require(tenants.containsKey(tenant));
            require(h.tenants().containsAll(h.roles().keySet()));
            for (var roles : h.roles().values()) require(Set.of("SECURITY_ADMIN", "CATALOG_OWNER", "PUBLISHER", "AUDIT_VIEWER").containsAll(roles));
        }
        for (var a : applications.values()) {
            status(a.status()); require(organizations.containsKey(a.organization()) && humans.containsKey(a.owner()));
            for (String tenant : a.tenants()) require(tenants.containsKey(tenant) && tenants.get(tenant).organization().equals(a.organization()));
        }
        for (var s : servers.values()) { status(s.status()); require(organizations.containsKey(s.organization()) && humans.containsKey(s.owner())
                && Set.of("INTERNAL", "THIRD_PARTY").contains(s.trust()) && s.backendRef() != null); }
        for (var c : capabilities.values()) { status(c.status()); require(servers.containsKey(c.server()) && !c.version().isBlank()
                && c.tool() != null && c.resourceArgument() != null
                && Set.of("ORDINARY_READ", "SENSITIVE_READ", "MUTATION", "UNCLASSIFIED").contains(c.classification())); }
        var catalogApplications = new java.util.HashSet<String>();
        for (var c : catalogs.values()) {
            require(applications.containsKey(c.application()) && humans.containsKey(c.owner()) && !c.environment().isBlank());
            require(catalogApplications.add(c.application())); // UC-01: one environment/catalog per approved application identity.
            require(c.owner().equals(applications.get(c.application()).owner()));
            for (var m : c.memberships().values()) require(capabilities.containsKey(m.capability()) && humans.containsKey(m.requestedBy())
                    && !m.version().isBlank() && m.purpose() != null && !m.purpose().isBlank()
                    && Set.of("REQUESTED", "PENDING", "ACTIVE", "SUSPENDED", "REMOVED", "REJECTED").contains(m.status()));
        }
        for (var d : delegations.values()) require(humans.containsKey(d.human()) && applications.containsKey(d.application())
                && tenants.containsKey(d.tenant()) && tenants.get(d.tenant()).organization().equals(d.organization())
                && d.issuedAt().isBefore(d.expiresAt())
                && java.time.Duration.between(d.issuedAt(), d.expiresAt()).compareTo(java.time.Duration.ofMinutes(15)) <= 0);
        for (var g : grants) require(Set.of("HUMAN", "APPLICATION").contains(g.kind()) && "READ".equals(g.operation())
                && ("HUMAN".equals(g.kind()) ? humans.containsKey(g.principal()) : applications.containsKey(g.principal()))
                && tenants.containsKey(g.tenant()) && capabilities.containsKey(g.capability()) && !g.resource().isBlank());
        for (var c : credentials) require(organizations.containsKey(c.organization()) && tenants.containsKey(c.tenant())
                && applications.containsKey(c.application()) && humans.containsKey(c.human()) && servers.containsKey(c.server())
                && tenants.get(c.tenant()).organization().equals(c.organization()) && applications.get(c.application()).organization().equals(c.organization())
                && servers.get(c.server()).organization().equals(c.organization())
                && "READ".equals(c.purpose()) && c.reference() != null && !c.reference().isBlank());
        var ownership = new java.util.HashSet<java.util.List<String>>();
        for (var r : resources) require(tenants.containsKey(r.tenant()) && tenants.get(r.tenant()).organization().equals(r.organization())
                && servers.containsKey(r.server()) && servers.get(r.server()).organization().equals(r.organization())
                && capabilities.containsKey(r.capability()) && capabilities.get(r.capability()).server().equals(r.server())
                && capabilities.get(r.capability()).version().equals(r.version()) && r.resource() != null && !r.resource().isBlank()
                && ownership.add(java.util.List.of(r.server(), r.capability(), r.version(), r.resource())));
    }
    public record Tenant(String organization, String status) { }
    public record Human(String authority, String subject, String displayName, String status,
            Set<String> tenants, Map<String, Set<String>> roles) {
        public Human { tenants = Set.copyOf(tenants); roles = roles.entrySet().stream().collect(java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey, e -> Set.copyOf(e.getValue()))); }
    }
    public record Application(String organization, String owner, String status, Set<String> tenants, boolean administration) {
        public Application { tenants = Set.copyOf(tenants); }
    }
    public record Server(String organization, String owner, String status, String trust, String backendRef, java.net.URI origin) {
        public Server { require(origin != null); origin = new dev.mcp.gateway.config.GatewayProperties.Backend(origin, "").baseUrl(); }
    }
    public record Capability(String server, String tool, String version, String status, String classification,
            String resourceArgument, Map<String, Object> inputSchema, Map<String, Object> outputSchema, String fingerprint) {
        public Capability { inputSchema = immutable(inputSchema); outputSchema = immutable(outputSchema); require(fingerprint != null && fingerprint.matches("[0-9a-f]{64}")); }
    }
    public record Membership(String capability, String version, String status, String requestedBy, String purpose) { }
    public record Catalog(String application, String owner, String environment, Map<String, Membership> memberships) {
        public Catalog { memberships = Map.copyOf(memberships); }
    }
    public record Delegation(String organization, String tenant, String human, String application,
            Instant issuedAt, Instant expiresAt, boolean revoked, Set<String> capabilities, Set<String> resources) {
        public Delegation { capabilities = Set.copyOf(capabilities); resources = Set.copyOf(resources); }
    }
    public record Grant(String kind, String principal, String tenant, String capability, String version, String operation, String resource) { }
    public record CredentialScope(String organization, String tenant, String application, String human,
            String server, String purpose, String reference) { }
    public record ResourceScope(String organization, String tenant, String server, String capability, String version, String resource) { }
    public AdmissionPolicy next(Map<String, Human> people, Map<String, Application> apps, Map<String, Server> upstreams,
            Map<String, Capability> tools, Map<String, Catalog> memberships, Map<String, Delegation> delegated, Set<Grant> permissions) {
        return new AdmissionPolicy(revision + 1, organizations, tenants, people, apps, upstreams, tools, memberships, delegated, permissions, credentials, resources);
    }
    public AdmissionPolicy withCredentials(Set<CredentialScope> scoped) {
        return new AdmissionPolicy(revision, organizations, tenants, humans, applications, servers, capabilities, catalogs, delegations, grants, scoped, resources);
    }
    public AdmissionPolicy withResources(Set<ResourceScope> scoped) {
        return new AdmissionPolicy(revision, organizations, tenants, humans, applications, servers, capabilities, catalogs, delegations, grants, credentials, scoped);
    }
    public static void require(boolean condition) { if (!condition) throw new IllegalArgumentException("Invalid admission policy"); }
    private static void status(String value) { require(Set.of("PENDING", "ACTIVE", "SUSPENDED", "RETIRED").contains(value)); }
    @SuppressWarnings("unchecked")
    private static <T> T immutable(T value) {
        if (value instanceof Map<?, ?> map) return (T) map.entrySet().stream().collect(
                java.util.stream.Collectors.toUnmodifiableMap(e -> (String) e.getKey(), e -> immutable(e.getValue())));
        if (value instanceof java.util.List<?> list) return (T) list.stream().map(AdmissionPolicy::immutable).toList();
        require(value instanceof String || value instanceof Number || value instanceof Boolean);
        return value;
    }
    @Override public String toString() { return "AdmissionPolicy[revision=" + revision + ", private]"; }
}
