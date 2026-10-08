package dev.mcp.gateway.admission;

import java.io.IOException;
import java.time.Instant;
import java.time.Duration;
import java.util.*;
import java.util.function.Supplier;

import dev.mcp.gateway.catalog.FlatObjectSchema;
import dev.mcp.gateway.catalog.ToolDefinition;
import dev.mcp.gateway.config.GatewayProperties;
import dev.mcp.gateway.rest.AllocationResult;
import dev.mcp.gateway.rest.RestBinding;
import dev.mcp.gateway.rest.RestBindingExecutor;
import io.modelcontextprotocol.server.McpAsyncServerExchange;
import io.modelcontextprotocol.spec.McpSchema;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.json.JsonMapper;

/** Serializes fresh admission and administrative commits; disk work stays off event-loop threads. */
public final class AdmissionService {
    private final AdmissionJournal journal;
    private final VerifiedPolicyCache policies;
    private final AdmissionSettings settings;
    private final GatewayProperties gateway;
    private final Map<String, ToolDefinition> tools;
    private final RestBindingExecutor executor;
    private final java.time.Clock clock;
    private final JsonMapper json = JsonMapper.builder().build();
    private record Context(String human, AdmissionPolicy.Application application, AdmissionPolicy.Delegation delegation) { }
    private record Ticket(String credential, Map<String, Object> facts) {
        @Override public String toString() { return "Ticket[private]"; }
    }

    public AdmissionService(AdmissionJournal journal, AdmissionSettings settings, GatewayProperties gateway,
            List<ToolDefinition> definitions, RestBindingExecutor executor) {
        this(journal, settings, gateway, definitions, executor, java.time.Clock.systemUTC());
    }
    AdmissionService(AdmissionJournal journal, AdmissionSettings settings, GatewayProperties gateway,
            List<ToolDefinition> definitions, RestBindingExecutor executor, java.time.Clock clock) {
        this.clock = clock;
        this.journal = journal; this.settings = settings; this.gateway = gateway; this.executor = executor;
        this.policies = new VerifiedPolicyCache(journal::readPolicy, settings.policyMaxAge(), System::nanoTime);
        this.tools = definitions.stream().collect(java.util.stream.Collectors.toUnmodifiableMap(ToolDefinition::name, t -> t));
        policies.current();
        synchronized (journal) {
            try {
                var latest = new LinkedHashMap<String, Map<String, Object>>();
                for (var record : journal.readAudits()) latest.put((String) record.get("id"), record);
                for (var record : latest.values()) {
                    if ("ADMITTED".equals(record.get("decision"))) {
                        var recovered = new LinkedHashMap<>(record); recovered.put("decision", "OUTCOME"); recovered.put("outcome", "UNKNOWN_AFTER_RESTART"); audit(recovered);
                    }
                    if ("ADMIN_PREPARED".equals(record.get("decision")) && record.get("afterRevision") instanceof Number revision) {
                        var recovered = new LinkedHashMap<>(record);
                        recovered.put("decision", policies.authoritative().revision() >= revision.longValue() ? "ADMIN_RECOVERED_EFFECTIVE" : "ADMIN_NOT_COMMITTED"); audit(recovered);
                    }
                }
            }
            catch (IOException failure) { throw AdmissionFailure.unavailable(); }
        }
    }
    private <T> Mono<T> disk(Supplier<T> work) { return Mono.fromSupplier(work).subscribeOn(Schedulers.boundedElastic()); }
    public Mono<List<String>> discover(AdmissionIdentity identity) {
        return disk(() -> {
            synchronized (journal) {
                var p = policies.current(); var context = context(p, identity);
                return tools.keySet().stream().filter(name -> {
                    var matches = eligibleContracts(p, identity, context, name);
                    if (matches.size() != 1) return false;
                    var entry = matches.getFirst();
                    return context.delegation().resources().stream().anyMatch(resource -> owned(p, identity, entry.getKey(), entry.getValue(), resource)
                            && permitted(p, identity, context, entry.getKey(), entry.getValue(), resource));
                }).sorted().toList();
            }
        });
    }
    public Mono<McpSchema.CallToolResult> invoke(ToolDefinition definition, McpSchema.CallToolRequest request, McpAsyncServerExchange exchange) {
        return Mono.defer(() -> {
            Object raw = exchange.transportContext().get(AdmissionIdentity.ATTRIBUTE);
            var identity = raw instanceof AdmissionIdentity i ? i : null;
            Map<String, Object> arguments = request.arguments() == null ? Map.of() : Collections.unmodifiableMap(new HashMap<>(request.arguments()));
            return disk(() -> admit(identity, definition, arguments)).flatMap(ticket ->
                executor.executeAuthorizedRead(definition.binding().resolve(arguments), ticket.credential())
                    .map(result -> result(definition, result))
                    .onErrorReturn(error("UPSTREAM_INVALID_RESPONSE", "unknown"))
                    .flatMap(result -> disk(() -> {
                        synchronized (journal) { recordOutcome(ticket, result.isError() ? "UNKNOWN" : "SUCCESS"); }
                        return result;
                    }).onErrorReturn(error("VERIFICATION_UNAVAILABLE", "unknown")))
                    .doOnCancel(() -> disk(() -> {
                        synchronized (journal) { recordOutcome(ticket, "UNKNOWN_CANCELLED"); }
                        return true;
                    }).subscribe(ignored -> { }, ignored -> { })))
                .onErrorResume(AdmissionFailure.class, failure -> Mono.just(error(failure.code(), "not_attempted")));
        });
    }
    private Ticket admit(AdmissionIdentity identity, ToolDefinition definition, Map<String, Object> arguments) {
        synchronized (journal) {
            var event = new LinkedHashMap<String, Object>();
            event.put("id", UUID.randomUUID().toString()); event.put("tool", definition.name());
            event.put("outcome", "NOT_ATTEMPTED"); event.put("verified", false); event.put("authenticatedEvidence", identity != null);
            if (identity != null) {
                event.put("organization", identity.organization()); event.put("tenant", identity.tenant());
                event.put("authority", identity.authority()); event.put("subject", identity.subject());
                event.put("application", identity.application()); event.put("mode", identity.mode());
                event.put("agentRun", identity.agentRun()); event.put("delegation", identity.delegation());
            }
            try {
                var p = policies.current(); event.put("revision", p.revision());
                var context = context(p, identity); event.put("human", context.human()); event.put("verified", true);
                if (!FlatObjectSchema.accepts(definition.inputSchema(), arguments)) throw new AdmissionFailure("INVALID_ARGUMENTS");
                var candidates = eligibleContracts(p, identity, context, definition.name());
                if (candidates.size() != 1) throw AdmissionFailure.denied();
                var cap = candidates.getFirst().getValue(); String capability = candidates.getFirst().getKey();
                Object resource = arguments.get(cap.resourceArgument());
                if (!(resource instanceof String value) || value.isBlank()) throw AdmissionFailure.unavailable();
                event.put("capability", capability); event.put("contract", cap.version()); event.put("server", cap.server());
                event.put("resource", value);
                boolean ownershipKnown = p.resources().stream().anyMatch(r -> r.server().equals(cap.server()) && r.capability().equals(capability)
                        && r.version().equals(cap.version()) && r.resource().equals(value));
                if (!ownershipKnown) throw AdmissionFailure.unavailable();
                if (!owned(p, identity, capability, cap, value)) throw AdmissionFailure.denied();
                if (!permitted(p, identity, context, capability, cap, value)) throw AdmissionFailure.denied();
                String credential = credential(p, identity, context, cap);
                event.put("decision", "ADMITTED"); event.put("outcome", "PENDING_OR_UNKNOWN");
                audit(event); // The forced journal write defines execution admission.
                return new Ticket(credential, Map.copyOf(event));
            }
            catch (AdmissionFailure failure) { event.put("decision", failure.code()); audit(event); throw failure; }
            catch (RuntimeException failure) { event.put("decision", "VERIFICATION_UNAVAILABLE"); audit(event); throw AdmissionFailure.unavailable(); }
        }
    }
    private Context context(AdmissionPolicy p, AdmissionIdentity i) {
        if (i == null || !"HUMAN_DELEGATED".equals(i.mode())) throw AdmissionFailure.denied();
        var c = principals(p, i); var d = p.delegations().get(i.delegation());
        if (d == null || d.revoked() || !d.organization().equals(i.organization()) || !d.tenant().equals(i.tenant())
                || !d.application().equals(i.application()) || !d.human().equals(c.human()) || d.issuedAt().isAfter(clock.instant())
                || !d.expiresAt().isAfter(clock.instant())) throw AdmissionFailure.denied();
        return new Context(c.human(), c.application(), d);
    }
    private Context administration(AdmissionPolicy p, AdmissionIdentity i) {
        if (i == null || !"ADMINISTRATION".equals(i.mode())) throw AdmissionFailure.denied();
        return principals(p, i);
    }
    private Context principals(AdmissionPolicy p, AdmissionIdentity i) {
        if (!i.expiresAt().isAfter(clock.instant())
                || !"ACTIVE".equals(p.organizations().get(i.organization()))) throw AdmissionFailure.denied();
        var tenant = p.tenants().get(i.tenant()); var app = p.applications().get(i.application());
        if (tenant == null || !tenant.organization().equals(i.organization()) || !"ACTIVE".equals(tenant.status())
                || app == null || !app.organization().equals(i.organization()) || !"ACTIVE".equals(app.status())
                || !app.tenants().contains(i.tenant())) throw AdmissionFailure.denied();
        String human = p.humans().entrySet().stream().filter(e -> e.getValue().authority().equals(i.authority())
                && e.getValue().subject().equals(i.subject())).map(Map.Entry::getKey).findFirst().orElseThrow(AdmissionFailure::denied);
        var person = p.humans().get(human);
        if (!"ACTIVE".equals(person.status()) || !person.tenants().contains(i.tenant())) throw AdmissionFailure.denied();
        return new Context(human, app, null);
    }
    private AdmissionPolicy.Capability eligible(AdmissionPolicy p, AdmissionIdentity i, Context c, String id, String tool) {
        var cap = p.capabilities().get(id); var definition = tools.get(tool);
        if (cap == null || definition == null || !cap.tool().equals(tool) || !"ACTIVE".equals(cap.status())
                || !"ORDINARY_READ".equals(cap.classification()) || definition.binding().method() != RestBinding.Method.GET
                || !cap.inputSchema().equals(definition.inputSchema()) || !cap.outputSchema().equals(definition.outputSchema())
                || !cap.fingerprint().equals(ContractFingerprint.of(definition))
                || !Set.copyOf(definition.binding().pathArguments().values()).equals(Set.of(cap.resourceArgument()))
                || !c.delegation().capabilities().contains(id) || !i.scopes().contains("gateway:read")) throw AdmissionFailure.denied();
        var server = p.servers().get(cap.server());
        if (server == null || !"ACTIVE".equals(server.status()) || !"INTERNAL".equals(server.trust())
                || !server.organization().equals(i.organization()) || !server.backendRef().equals(definition.binding().backendRef())
                || !gateway.backends().containsKey(server.backendRef()) || !server.origin().equals(gateway.backends().get(server.backendRef()).baseUrl())) throw AdmissionFailure.denied();
        boolean member = p.catalogs().values().stream().filter(cat -> cat.application().equals(i.application()))
                .flatMap(cat -> cat.memberships().values().stream()).anyMatch(m -> m.capability().equals(id)
                        && m.version().equals(cap.version()) && "ACTIVE".equals(m.status()));
        if (!member) throw AdmissionFailure.denied();
        return cap;
    }
    private List<Map.Entry<String, AdmissionPolicy.Capability>> eligibleContracts(AdmissionPolicy p, AdmissionIdentity i, Context c, String tool) {
        return p.capabilities().entrySet().stream().filter(entry -> {
            try { eligible(p, i, c, entry.getKey(), tool); return true; }
            catch (AdmissionFailure denied) { return false; }
        }).toList();
    }
    private boolean permitted(AdmissionPolicy p, AdmissionIdentity i, Context c, String id, AdmissionPolicy.Capability cap, String resource) {
        return c.delegation().resources().contains(resource)
                && p.grants().contains(new AdmissionPolicy.Grant("HUMAN", c.human(), i.tenant(), id, cap.version(), "READ", resource))
                && p.grants().contains(new AdmissionPolicy.Grant("APPLICATION", i.application(), i.tenant(), id, cap.version(), "READ", resource));
    }
    private boolean owned(AdmissionPolicy p, AdmissionIdentity i, String id, AdmissionPolicy.Capability cap, String resource) {
        return p.resources().contains(new AdmissionPolicy.ResourceScope(i.organization(), i.tenant(), cap.server(), id, cap.version(), resource));
    }
    private String credential(AdmissionPolicy p, AdmissionIdentity i, Context c, AdmissionPolicy.Capability cap) {
        var matches = p.credentials().stream().filter(ref -> ref.organization().equals(i.organization()) && ref.tenant().equals(i.tenant())
                && ref.application().equals(i.application()) && ref.human().equals(c.human()) && ref.server().equals(cap.server())
                && ref.purpose().equals("READ")).toList();
        if (matches.size() != 1) throw AdmissionFailure.unavailable();
        var secret = settings.credentials().get(matches.getFirst().reference());
        var backend = gateway.backends().get(p.servers().get(cap.server()).backendRef());
        if (secret == null || backend == null || !secret.expiresAt().isAfter(clock.instant()) || !secret.audience().equals(backend.baseUrl())) throw AdmissionFailure.unavailable();
        return secret.token();
    }
    private McpSchema.CallToolResult result(ToolDefinition definition, AllocationResult result) {
        if (result instanceof AllocationResult.ObjectSuccess success) {
            var output = FlatObjectSchema.project(definition.outputSchema(), success.fields(), true);
            String text = json.writeValueAsString(output);
            if (settings.credentials().values().stream().anyMatch(credential -> text.contains(credential.token())))
                return error("UPSTREAM_INVALID_RESPONSE", "unknown");
            return McpSchema.CallToolResult.builder().isError(false).structuredContent(output).addTextContent(text).build();
        }
        return error(result instanceof AllocationResult.Failure failure ? failure.code().name() : "UPSTREAM_INVALID_RESPONSE", "unknown");
    }
    private McpSchema.CallToolResult error(String code, String outcome) {
        return McpSchema.CallToolResult.builder().isError(true).addTextContent(json.writeValueAsString(
                Map.of("code", code, "message", "The operation could not be completed.", "allocationOutcome", outcome))).build();
    }
    private void audit(Map<String, Object> facts) {
        var record = new LinkedHashMap<>(facts); Instant at = clock.instant(); record.put("at", at.toString());
        record.put("retainUntil", at.plus(Duration.ofDays(90)).toString());
        try { journal.appendAudit(record); } catch (IOException | RuntimeException failure) { throw AdmissionFailure.unavailable(); }
    }
    private void recordOutcome(Ticket ticket, String outcome) {
        var event = new LinkedHashMap<>(ticket.facts()); event.put("decision", "OUTCOME"); event.put("outcome", outcome); audit(event);
    }

    public record Change(long expectedRevision, String action, String target, String state, String catalog, String capability,
            String version, String purpose, String reason, AdmissionPolicy.Human human, AdmissionPolicy.Application application,
            AdmissionPolicy.Grant grant, AdmissionPolicy.Delegation delegation, AdmissionPolicy.Server server,
            AdmissionPolicy.Capability contract, AdmissionPolicy.CredentialScope credential, AdmissionPolicy.Catalog catalogDefinition,
            AdmissionPolicy.ResourceScope resource) { }
    public Mono<Map<String, Object>> change(AdmissionIdentity i, Change change) { return disk(() -> commit(i, change)); }
    private Map<String, Object> commit(AdmissionIdentity i, Change change) {
        synchronized (journal) {
            var facts = new LinkedHashMap<String, Object>(); facts.put("id", UUID.randomUUID().toString());
            facts.put("organization", i.organization()); facts.put("tenant", i.tenant()); facts.put("authority", i.authority()); facts.put("subject", i.subject()); facts.put("application", i.application());
            facts.put("action", change.action()); facts.put("target", change.target() == null ? "" : change.target()); facts.put("verified", false);
            try {
                var p = policies.authoritative(); var c = administration(p, i);
                facts.put("actor", c.human()); facts.put("verified", true); facts.put("beforeRevision", p.revision());
                if (!c.application().administration() || !i.scopes().contains("gateway:admin") || change.expectedRevision() != p.revision()
                        || change.reason() == null || change.reason().isBlank() || change.reason().length() > 256) throw AdmissionFailure.denied();
                validateChange(change);
                var roles = p.humans().get(c.human()).roles().getOrDefault(i.tenant(), Set.of());
                var people = new HashMap<>(p.humans()); var apps = new HashMap<>(p.applications()); var servers = new HashMap<>(p.servers());
                var caps = new HashMap<>(p.capabilities()); var cats = new HashMap<>(p.catalogs()); var ds = new HashMap<>(p.delegations()); var grants = new HashSet<>(p.grants());
                var credentialScopes = new HashSet<>(p.credentials());
                var resources = new HashSet<>(p.resources());
                if ("MEMBERSHIP".equals(change.action())) {
                    var cat = cats.get(change.catalog()); var cap = caps.get(change.capability());
                    if (!roles.contains("CATALOG_OWNER") || cat == null || !cat.owner().equals(c.human()) || cap == null
                            || !apps.get(cat.application()).tenants().contains(i.tenant()) || !cap.version().equals(change.version())
                            || !servers.get(cap.server()).organization().equals(i.organization()) || !Set.of("REQUEST", "REMOVE").contains(change.state())) throw AdmissionFailure.denied();
                    var members = new HashMap<>(cat.memberships());
                    String state = "REMOVE".equals(change.state()) ? "REMOVED" : "ORDINARY_READ".equals(cap.classification()) && "ACTIVE".equals(cap.status()) ? "ACTIVE" : "PENDING";
                    members.put(change.capability(), new AdmissionPolicy.Membership(change.capability(), cap.version(), state, c.human(), change.purpose()));
                    cats.put(change.catalog(), new AdmissionPolicy.Catalog(cat.application(), cat.owner(), cat.environment(), members));
                    facts.put("before", cat); facts.put("after", cats.get(change.catalog()));
                }
                else if ("PUBLICATION".equals(change.action())) {
                    var cap = caps.get(change.target());
                    if (!roles.contains("PUBLISHER") || cap == null || !servers.get(cap.server()).organization().equals(i.organization())
                            || "ACTIVE".equals(change.state()) && "UNCLASSIFIED".equals(cap.classification())) throw AdmissionFailure.denied();
                    caps.put(change.target(), new AdmissionPolicy.Capability(cap.server(), cap.tool(), cap.version(), change.state(), cap.classification(), cap.resourceArgument(), cap.inputSchema(), cap.outputSchema(), cap.fingerprint()));
                    facts.put("before", cap); facts.put("after", caps.get(change.target()));
                }
                else {
                    if (!roles.contains("SECURITY_ADMIN")) throw AdmissionFailure.denied();
                    switch (change.action()) {
                        case "HUMAN" -> {
                            var next = change.human(); var old = people.get(change.target());
                            if (next == null || change.target().equals(c.human()) || !next.authority().equals(i.authority())
                                    || !next.tenants().equals(Set.of(i.tenant())) || old != null && (!old.tenants().equals(Set.of(i.tenant()))
                                    || "RETIRED".equals(old.status()) || !old.authority().equals(next.authority()) || !old.subject().equals(next.subject()))
                                    || old == null && (!"PENDING".equals(next.status()) || !next.roles().isEmpty())) throw AdmissionFailure.denied();
                            people.put(change.target(), next); facts.put("before", old == null ? "ABSENT" : old); facts.put("after", next);
                        }
                        case "APPLICATION" -> {
                            var next = change.application(); var old = apps.get(change.target());
                            if (next == null || change.target().equals(i.application()) || next.owner().equals(c.human())
                                    || !next.organization().equals(i.organization()) || !next.tenants().equals(Set.of(i.tenant()))
                                    || old != null && ("RETIRED".equals(old.status()) || !old.organization().equals(next.organization()) || !old.tenants().equals(next.tenants()))
                                    || old == null && (!"PENDING".equals(next.status()) || next.administration())) throw AdmissionFailure.denied();
                            apps.put(change.target(), next); facts.put("before", old == null ? "ABSENT" : old); facts.put("after", next);
                        }
                        case "GRANT", "REVOKE_GRANT" -> {
                            var g = change.grant();
                            if (g == null || !g.tenant().equals(i.tenant()) || "HUMAN".equals(g.kind()) && g.principal().equals(c.human())
                                    || "APPLICATION".equals(g.kind()) && g.principal().equals(i.application())
                                    || "APPLICATION".equals(g.kind()) && apps.get(g.principal()).owner().equals(c.human())
                                    || !servers.get(caps.get(g.capability()).server()).organization().equals(i.organization())) throw AdmissionFailure.denied();
                            facts.put("before", grants.contains(g));
                            if ("GRANT".equals(change.action())) grants.add(g); else grants.remove(g);
                            facts.put("after", grants.contains(g)); facts.put("scope", g);
                        }
                        case "REVOKE_DELEGATION" -> {
                            var d = ds.get(change.target());
                            if (d == null || !d.tenant().equals(i.tenant()) || !d.organization().equals(i.organization())) throw AdmissionFailure.denied();
                            ds.put(change.target(), new AdmissionPolicy.Delegation(d.organization(), d.tenant(), d.human(), d.application(), d.issuedAt(), d.expiresAt(), true, d.capabilities(), d.resources()));
                            facts.put("before", d); facts.put("after", ds.get(change.target()));
                        }
                        case "DELEGATION" -> {
                            var d = change.delegation();
                            if (d == null || ds.containsKey(change.target()) || !d.tenant().equals(i.tenant()) || !d.organization().equals(i.organization())
                                    || d.human().equals(c.human()) || d.application().equals(i.application()) || !"ACTIVE".equals(people.get(d.human()).status())
                                    || !"ACTIVE".equals(apps.get(d.application()).status())) throw AdmissionFailure.denied();
                            ds.put(change.target(), d); facts.put("before", "ABSENT"); facts.put("after", d);
                        }
                        case "SERVER" -> {
                            var next = change.server(); var old = servers.get(change.target());
                            if (next == null || !next.organization().equals(i.organization()) || !"INTERNAL".equals(next.trust())
                                    || !gateway.backends().containsKey(next.backendRef()) || old != null && (!old.organization().equals(next.organization())
                                    || !old.backendRef().equals(next.backendRef()) || !old.trust().equals(next.trust()))) throw AdmissionFailure.denied();
                            servers.put(change.target(), next); facts.put("before", old == null ? "ABSENT" : old); facts.put("after", next);
                        }
                        case "CONTRACT" -> {
                            var cap = change.contract();
                            if (cap == null || caps.containsKey(change.target()) || !"PENDING".equals(cap.status())
                                    || !servers.get(cap.server()).organization().equals(i.organization())) throw AdmissionFailure.denied();
                            caps.put(change.target(), cap); facts.put("before", "ABSENT"); facts.put("after", cap);
                        }
                        case "CREDENTIAL", "REVOKE_CREDENTIAL" -> {
                            var ref = change.credential();
                            if (ref == null || !ref.organization().equals(i.organization()) || !ref.tenant().equals(i.tenant())
                                    || ref.human().equals(c.human()) || ref.application().equals(i.application())
                                    || !people.get(ref.human()).tenants().contains(i.tenant()) || !apps.get(ref.application()).tenants().contains(i.tenant())
                                    || !servers.get(ref.server()).organization().equals(i.organization())) throw AdmissionFailure.denied();
                            facts.put("before", credentialScopes.contains(ref));
                            if ("CREDENTIAL".equals(change.action())) {
                                if (!settings.credentials().containsKey(ref.reference())) throw AdmissionFailure.unavailable();
                                credentialScopes.add(ref);
                            }
                            else credentialScopes.remove(ref);
                            facts.put("after", credentialScopes.contains(ref)); facts.put("scope", ref);
                        }
                        case "CATALOG" -> {
                            var cat = change.catalogDefinition();
                            if (cat == null || cats.containsKey(change.target()) || !cat.memberships().isEmpty()
                                    || !apps.get(cat.application()).organization().equals(i.organization()) || !apps.get(cat.application()).tenants().contains(i.tenant())) throw AdmissionFailure.denied();
                            cats.put(change.target(), cat); facts.put("before", "ABSENT"); facts.put("after", cat);
                        }
                        case "RESOURCE", "REVOKE_RESOURCE" -> {
                            var resource = change.resource();
                            if (resource == null || !resource.organization().equals(i.organization()) || !resource.tenant().equals(i.tenant())) throw AdmissionFailure.denied();
                            facts.put("before", resources.contains(resource));
                            if ("RESOURCE".equals(change.action())) resources.add(resource); else resources.remove(resource);
                            facts.put("after", resources.contains(resource)); facts.put("scope", resource);
                        }
                        default -> throw AdmissionFailure.denied();
                    }
                }
                var next = p.next(people, apps, servers, caps, cats, ds, grants).withCredentials(credentialScopes).withResources(resources);
                facts.put("decision", "ADMIN_PREPARED"); facts.put("reason", change.reason()); facts.put("afterRevision", next.revision()); audit(facts);
                try { journal.writePolicy(next); } catch (IOException failure) { policies.invalidate(); throw AdmissionFailure.unavailable(); }
                policies.invalidate(); policies.current();
                audit(Map.of("id", facts.get("id"), "organization", i.organization(), "tenant", i.tenant(), "decision", "ADMIN_EFFECTIVE", "revision", next.revision()));
                return Map.of("revision", next.revision(), "accepted", true, "effective", true);
            }
            catch (RuntimeException failure) {
                facts.put("decision", failure instanceof AdmissionFailure f ? f.code() : "ACCESS_DENIED"); audit(facts);
                throw failure instanceof AdmissionFailure f ? f : AdmissionFailure.denied();
            }
        }
    }
    private void validateChange(Change change) {
        Set<String> allowed = switch (change.action()) {
            case "MEMBERSHIP" -> Set.of("catalog", "capability", "version", "state", "purpose");
            case "PUBLICATION" -> Set.of("target", "state");
            case "HUMAN" -> Set.of("target", "human");
            case "APPLICATION" -> Set.of("target", "application");
            case "GRANT", "REVOKE_GRANT" -> Set.of("grant");
            case "DELEGATION" -> Set.of("target", "delegation");
            case "REVOKE_DELEGATION" -> Set.of("target");
            case "SERVER" -> Set.of("target", "server");
            case "CONTRACT" -> Set.of("target", "contract");
            case "CREDENTIAL", "REVOKE_CREDENTIAL" -> Set.of("credential");
            case "CATALOG" -> Set.of("target", "catalogDefinition");
            case "RESOURCE", "REVOKE_RESOURCE" -> Set.of("resource");
            default -> throw AdmissionFailure.denied();
        };
        var fields = new LinkedHashMap<String, Object>();
        fields.put("target", change.target()); fields.put("state", change.state()); fields.put("catalog", change.catalog());
        fields.put("capability", change.capability()); fields.put("version", change.version()); fields.put("purpose", change.purpose());
        fields.put("human", change.human()); fields.put("application", change.application()); fields.put("grant", change.grant());
        fields.put("delegation", change.delegation()); fields.put("server", change.server()); fields.put("contract", change.contract());
        fields.put("credential", change.credential()); fields.put("catalogDefinition", change.catalogDefinition());
        fields.put("resource", change.resource());
        fields.entrySet().removeIf(e -> e.getValue() == null);
        if (!fields.keySet().equals(allowed) || fields.values().stream().anyMatch(value -> value instanceof String s && (s.isBlank() || s.length() > 256))) throw AdmissionFailure.denied();
    }
    public Mono<List<Map<String, Object>>> auditExport(AdmissionIdentity i) {
        return disk(() -> {
            synchronized (journal) {
                var p = policies.current(); var c = administration(p, i);
                if (!c.application().administration() || !i.scopes().contains("gateway:audit")
                        || !p.humans().get(c.human()).roles().getOrDefault(i.tenant(), Set.of()).contains("AUDIT_VIEWER")) throw AdmissionFailure.denied();
                audit(Map.of("id", UUID.randomUUID().toString(), "organization", i.organization(), "tenant", i.tenant(), "actor", c.human(), "decision", "AUDIT_ACCESS"));
                try { return journal.readAudits().stream().filter(record -> i.organization().equals(record.get("organization"))
                        && i.tenant().equals(record.get("tenant")) && record.get("retainUntil") instanceof String until
                        && Instant.parse(until).isAfter(clock.instant())).toList(); }
                catch (IOException failure) { throw AdmissionFailure.unavailable(); }
            }
        });
    }
}
