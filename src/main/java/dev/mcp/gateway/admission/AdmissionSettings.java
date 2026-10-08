package dev.mcp.gateway.admission;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties(prefix = "admission", ignoreUnknownFields = false)
public record AdmissionSettings(Path bootstrap, Path journalDirectory, @DefaultValue("30s") Duration policyMaxAge,
        Map<String, Credential> credentials) {
    public AdmissionSettings {
        AdmissionPolicy.require(bootstrap != null && !bootstrap.toString().isBlank() && journalDirectory != null && !journalDirectory.toString().isBlank() && policyMaxAge != null
                && policyMaxAge.compareTo(Duration.ofSeconds(30)) <= 0 && !policyMaxAge.isNegative() && !policyMaxAge.isZero());
        credentials = credentials == null ? Map.of() : Map.copyOf(credentials);
    }
    public record Credential(String token, URI audience, Instant expiresAt) {
        public Credential { AdmissionPolicy.require(token != null && token.matches("[A-Za-z0-9._~+/-]+=*")
                && audience != null && expiresAt != null); }
        @Override public String toString() { return "Credential[private]"; }
    }
    @Override public String toString() { return "AdmissionSettings[private]"; }
}
