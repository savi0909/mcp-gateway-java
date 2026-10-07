package dev.mcp.gateway.security;

import java.time.Instant;
import java.util.Set;

/** Trusted identity only, never constructed from tool arguments or MCP metadata. */
public record Caller(String issuer, String subject, String tenant, Set<String> scopes, Instant expiresAt) {
    public static final String ATTRIBUTE = Caller.class.getName();
    public Caller { scopes = Set.copyOf(scopes); }
    public boolean sameOwner(Caller other) {
        return issuer.equals(other.issuer) && subject.equals(other.subject) && tenant.equals(other.tenant);
    }
    @Override public String toString() { return "Caller[<private>]"; }
}
