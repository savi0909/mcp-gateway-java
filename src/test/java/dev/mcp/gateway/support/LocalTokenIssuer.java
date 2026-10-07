package dev.mcp.gateway.support;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.atomic.AtomicInteger;

import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.json.JsonMapper;

/** Ephemeral signer + public JWKS listener, separate from all business backend counters. */
public final class LocalTokenIssuer implements AutoCloseable {
    private final RSAKey key = new RSAKeyGenerator(2048).keyID("fixture-key").generate();
    private final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    private final AtomicInteger count = new AtomicInteger();
    public LocalTokenIssuer() throws Exception {
        server.setExecutor(threads);
        server.createContext("/jwks", exchange -> {
            try (exchange) {
                count.incrementAndGet();
                byte[] body = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.createContext("/.well-known/openid-configuration", exchange -> {
            try (exchange) {
                var json = JsonMapper.builder().build();
                byte[] body = json.writeValueAsBytes(Map.of("issuer", origin().toString(), "jwks_uri", origin() + "/jwks"));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.start();
    }
    public URI origin() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort()); }
    public int jwksCount() { return count.get(); }
    public String token(String subject, String tenant, String scope, String audience) throws Exception {
        return token(subject, tenant, scope, audience, Map.of(), false);
    }
    public String token(String subject, String tenant, String scope, String audience,
            Map<String, Object> overrides, boolean wrongSignature) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(origin().toString()).subject(subject).audience(audience)
                .issueTime(Date.from(Instant.now().minusSeconds(1))).expirationTime(Date.from(Instant.now().plusSeconds(120)))
                .claim("tenant", tenant).claim("scope", scope);
        overrides.forEach(claims::claim);
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID("fixture-key")
                .type(new JOSEObjectType("at+jwt")).build(), claims.build());
        jwt.sign(new RSASSASigner(wrongSignature ? new RSAKeyGenerator(2048).generate() : key));
        return jwt.serialize();
    }
    @Override public void close() { server.stop(0); threads.close(); }
}
