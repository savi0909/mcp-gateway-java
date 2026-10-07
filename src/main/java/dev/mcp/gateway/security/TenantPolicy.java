package dev.mcp.gateway.security;

import java.util.Map;
import java.util.Set;
import java.util.HashSet;
import java.util.UUID;

import org.springframework.core.io.ResourceLoader;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Immutable operator-owned admission policy. It never queries business backends. */
public record TenantPolicy(int schemaVersion, Map<String, Tenant> tenants) {
    public record Tenant(Set<String> products, Map<String, String> services, Map<String, String> workerTypes,
            Set<Integer> regions, Map<String, Owner> subjects, boolean paymentAllowed) {
        public Tenant {
            products = Set.copyOf(products); services = Map.copyOf(services); workerTypes = Map.copyOf(workerTypes);
            regions = Set.copyOf(regions); subjects = Map.copyOf(subjects);
            SecuritySettings.require(!products.isEmpty() && !subjects.isEmpty() && !regions.isEmpty(), "empty tenant policy");
            var ids = new HashSet<>(products); ids.addAll(services.keySet()); ids.addAll(workerTypes.keySet());
            SecuritySettings.require(ids.stream().allMatch(id -> id.matches("[A-Za-z0-9_-][A-Za-z0-9_.-]{0,99}"))
                    && subjects.keySet().stream().allMatch(id -> id != null && !id.isBlank())
                    && products.containsAll(services.values()) && services.keySet().containsAll(workerTypes.values())
                    && regions.stream().allMatch(id -> id >= 0 && id <= 15), "invalid tenant relationships");
        }
        @Override public String toString() { return "Tenant[<private>]"; }
    }
    public record Owner(UUID instanceId, UUID registrationId) {
        public Owner { SecuritySettings.require(instanceId != null && registrationId != null, "owner UUIDs required"); }
        @Override public String toString() { return "Owner[<private>]"; }
    }
    public TenantPolicy {
        SecuritySettings.require(schemaVersion == 1 && tenants != null && !tenants.isEmpty() && tenants.size() <= 32,
                "unsupported security policy");
        tenants = Map.copyOf(tenants);
        var products = new HashSet<String>(); var services = new HashSet<String>(); var types = new HashSet<String>();
        var instances = new HashSet<UUID>(); var registrations = new HashSet<UUID>();
        for (var entry : tenants.entrySet()) {
            SecuritySettings.require(entry.getKey().matches("[A-Za-z0-9_-]{1,100}"), "invalid tenant key");
            var tenant = entry.getValue();
            SecuritySettings.require(tenant.products().stream().allMatch(products::add)
                    && tenant.services().keySet().stream().allMatch(services::add)
                    && tenant.workerTypes().keySet().stream().allMatch(types::add)
                    && tenant.subjects().values().stream().allMatch(owner -> instances.add(owner.instanceId())
                        && registrations.add(owner.registrationId())), "overlapping tenant or owner policy");
        }
    }
    public static TenantPolicy load(String location, ResourceLoader resources) {
        SecuritySettings.require(location.startsWith("classpath:") || location.startsWith("file:"),
                "security policy-location must be classpath: or file:");
        var mapper = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
        try (var input = resources.getResource(location).getInputStream()) {
            byte[] bytes = input.readNBytes(262145);
            SecuritySettings.require(bytes.length <= 262144, "security policy exceeds limit");
            return mapper.readValue(bytes, TenantPolicy.class);
        }
        catch (Exception ex) { throw new IllegalArgumentException("Security policy is missing or invalid"); }
    }
    public boolean knows(Caller caller) {
        var tenant = tenants.get(caller.tenant());
        return tenant != null && tenant.subjects().containsKey(caller.subject());
    }
    public boolean allows(Caller caller, String tool, Map<String, Object> arguments) {
        if (caller == null || !knows(caller) || !caller.expiresAt().isAfter(java.time.Instant.now())) return false;
        String scope = READS.contains(tool) ? "gateway:read" : "gateway:write";
        if (!TOOLS.contains(tool) || !caller.scopes().contains(scope)) return false;
        var tenant = tenants.get(caller.tenant());
        if (tool.equals("create_sample_payment")) return tenant.paymentAllowed();
        String product = (String) arguments.get("productId"), service = (String) arguments.get("serviceId"),
                type = (String) arguments.get("workerTypeId");
        if (product != null && !tenant.products().contains(product)) return false;
        if (service != null && !tenant.services().containsKey(service)) return false;
        if (type != null && !tenant.workerTypes().containsKey(type)) return false;
        if (product != null && service != null && !product.equals(tenant.services().get(service))) return false;
        if (service != null && type != null && !service.equals(tenant.workerTypes().get(type))) return false;
        if (LEASES.contains(tool)) {
            var owner = tenant.subjects().get(caller.subject());
            return product != null && service != null && type != null
                    && tenant.regions().contains(((Number) arguments.get("regionId")).intValue())
                    && owner.instanceId().toString().equals(arguments.get("instanceId"))
                    && owner.registrationId().toString().equals(arguments.get("registrationId"));
        }
        return product != null || service != null || type != null;
    }
    public static final Set<String> READS = Set.of("get_product", "get_service", "get_worker_type");
    public static final Set<String> LEASES = Set.of("acquire_worker", "renew_worker_lease", "release_worker");
    public static final Set<String> TOOLS = Set.of("get_product", "get_service", "get_worker_type", "register_product",
            "register_service", "register_worker_type", "acquire_worker", "renew_worker_lease", "release_worker", "create_sample_payment");
    @Override public String toString() { return "TenantPolicy[<private>]"; }
}
