package dev.mcp.gateway.security;

import java.net.URI;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "gateway-security", ignoreUnknownFields = false)
public record SecuritySettings(boolean enabled, URI issuer, URI jwkSetUri, URI resource,
        String policyLocation, boolean allowLoopbackHttp) {
    public SecuritySettings {
        if (enabled) {
            validUri(issuer, allowLoopbackHttp);
            validUri(jwkSetUri, allowLoopbackHttp);
            validUri(resource, allowLoopbackHttp);
            require(resource.getPath().equals("/worker-coordinator/mcp"), "security resource must be the fixed MCP endpoint");
            require(policyLocation != null && !policyLocation.isBlank(), "security policy-location is required");
        }
    }
    private static void validUri(URI uri, boolean loopback) {
        require(uri != null && uri.getHost() != null && uri.getUserInfo() == null
                && uri.getQuery() == null && uri.getFragment() == null && !uri.isOpaque()
                && ("https".equals(uri.getScheme()) || loopback && "http".equals(uri.getScheme())
                    && java.util.Set.of("127.0.0.1", "localhost", "[::1]").contains(uri.getHost()))
                && (uri.getPort() == -1 || uri.getPort() > 0 && uri.getPort() <= 65535),
                "security URLs require HTTPS or explicitly allowed loopback HTTP, without credentials/query/fragment");
    }
    static void require(boolean valid, String message) { if (!valid) throw new IllegalArgumentException(message); }
    public String metadataUrl() {
        return resource.getScheme() + "://" + resource.getRawAuthority()
                + "/.well-known/oauth-protected-resource/worker-coordinator/mcp";
    }
    @Override public String toString() { return "SecuritySettings[enabled=" + enabled + ", configuration=<private>]"; }
}
