package dev.mcp.gateway.admission;

import java.time.Instant;
import java.util.Set;

import org.springframework.security.oauth2.jwt.Jwt;

/** Construct only from a Spring Security validated resource-server JWT. */
public record AdmissionIdentity(String authority, String subject, String application, String organization,
        String tenant, String mode, String delegation, String agentRun, Instant expiresAt, Set<String> scopes) {
    public static final String ATTRIBUTE = AdmissionIdentity.class.getName();
    public AdmissionIdentity { scopes = Set.copyOf(scopes); }
    public static AdmissionIdentity from(Jwt jwt) {
        try {
            String app = jwt.getClaimAsString("application"), org = jwt.getClaimAsString("organization"),
                    mode = jwt.getClaimAsString("execution_mode"), delegation = jwt.getClaimAsString("delegation"), run = jwt.getClaimAsString("agent_run");
            if (app == null || app.isBlank() || org == null || org.isBlank() || !Set.of("HUMAN_DELEGATED", "ADMINISTRATION").contains(mode)
                    || "HUMAN_DELEGATED".equals(mode) && (delegation == null || delegation.isBlank())
                    || run == null || run.isBlank() || run.length() > 128) throw AdmissionFailure.denied();
            return new AdmissionIdentity(jwt.getIssuer().toString(), jwt.getSubject(), app, org,
                    jwt.getClaimAsString("tenant"), mode, delegation == null ? "" : delegation, run, jwt.getExpiresAt(),
                    Set.of(jwt.getClaimAsString("scope").split(" +")));
        }
        catch (RuntimeException invalid) { throw AdmissionFailure.denied(); }
    }
    public boolean sameOwner(AdmissionIdentity other) {
        return authority.equals(other.authority) && subject.equals(other.subject) && application.equals(other.application)
                && organization.equals(other.organization) && tenant.equals(other.tenant) && mode.equals(other.mode);
    }
    @Override public String toString() { return "AdmissionIdentity[private]"; }
}
