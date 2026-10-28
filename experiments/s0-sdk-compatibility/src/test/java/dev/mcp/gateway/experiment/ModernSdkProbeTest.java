package dev.mcp.gateway.experiment;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import io.modelcontextprotocol.client.McpClient;
import io.modelcontextprotocol.client.transport.HttpClientStreamableHttpTransport;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonDefaults;
import io.modelcontextprotocol.modern.JsonRpc;
import io.modelcontextprotocol.modern.McpException;
import io.modelcontextprotocol.modern.McpSchema;
import io.modelcontextprotocol.modern.server.McpAsyncResponse;
import io.modelcontextprotocol.modern.server.McpRequestContext;
import io.modelcontextprotocol.modern.server.McpServer;
import io.modelcontextprotocol.modern.server.feature.McpAsyncToolRepository;
import io.modelcontextprotocol.modern.server.feature.ToolsFeature;
import io.modelcontextprotocol.modern.server.feature.ToolsPage;
import io.modelcontextprotocol.modern.server.transport.HttpServletMcpTransport;
import jakarta.servlet.Filter;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.web.server.servlet.context.ServletWebServerApplicationContext;
import org.springframework.boot.web.servlet.FilterRegistrationBean;
import org.springframework.boot.web.servlet.ServletRegistrationBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import static org.assertj.core.api.Assertions.*;

/** Isolated SDK experiment. HTTP fixtures below are NOT a supported modern SDK client. */
class ModernSdkProbeTest {
    static final String ENDPOINT = "/worker-coordinator/mcp";
    static final String VERSION = "2026-07-28";
    static final Duration LIMIT = Duration.ofSeconds(6);
    ServletWebServerApplicationContext app;
    Fixture fixture;
    URI uri;
    HttpClient http;
    final AtomicInteger nextId = new AtomicInteger();

    @BeforeEach void start() {
        var boot = new SpringApplication(ProbeConfiguration.class);
        boot.setWebApplicationType(WebApplicationType.SERVLET);
        boot.setDefaultProperties(Map.of("server.port", "0", "server.address", "127.0.0.1",
                "spring.main.banner-mode", "off", "logging.level.root", "ERROR"));
        app = (ServletWebServerApplicationContext) boot.run();
        fixture = app.getBean(Fixture.class);
        uri = URI.create("http://127.0.0.1:" + app.getWebServer().getPort() + ENDPOINT);
        http = HttpClient.newBuilder().connectTimeout(LIMIT).build();
    }

    @AfterEach void stop() {
        if (http != null) http.close();
        if (app != null) app.close();
    }

    @Test void discoverAndListWithoutInitializationOrSessions() throws Exception {
        var discovery = send("a", "server/discover", Map.of(), "server/discover", null, VERSION);
        assertThat(discovery.statusCode()).isEqualTo(200);
        assertThat(discovery.body()).contains(VERSION, "\"resultType\":\"complete\"", "\"tools\"");
        assertThat(discovery.headers().firstValue("Mcp-Session-Id")).isEmpty();
        var list = send("a", "tools/list", Map.of(), "tools/list", null, VERSION);
        assertThat(list.statusCode()).isEqualTo(200);
        assertThat(list.body()).contains("read_a", "\"cacheScope\":\"private\"", "\"ttlMs\":0")
                .doesNotContain("read_b", "binding", "backend");
        assertThat(fixture.calls.get()).isZero();
    }

    @Test void ordinaryReadsKeepTwentyConcurrentCallerContextsIsolated() throws Exception {
        var futures = new ArrayList<CompletableFuture<Void>>();
        for (int i = 0; i < 20; i++) {
            String caller = i % 2 == 0 ? "a" : "b";
            futures.add(CompletableFuture.runAsync(() -> {
                try {
                    var list = send(caller, "tools/list", Map.of(), "tools/list", null, VERSION);
                    assertThat(list.body()).contains("read_" + caller).doesNotContain("read_" + other(caller));
                    var read = send(caller, "tools/call", Map.of("name", "read_" + caller, "arguments", Map.of()),
                            "tools/call", "read_" + caller, VERSION);
                    assertThat(read.statusCode()).isEqualTo(200);
                    assertThat(read.body()).contains("\"caller\":\"" + caller + "\"", "\"resultType\":\"complete\"");
                } catch (Exception e) { throw new RuntimeException(e); }
            }));
        }
        CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).get(12, TimeUnit.SECONDS);
        assertThat(fixture.calls.get()).isEqualTo(20);
    }

    @Test void foreignToolAndMissingAuthenticationNeverExecute() throws Exception {
        assertThat(send("a", "tools/call", Map.of("name", "read_b"), "tools/call", "read_b", VERSION).body())
                .contains("\"error\"");
        assertThat(send("invalid", "tools/list", Map.of(), "tools/list", null, VERSION).statusCode()).isEqualTo(401);
        assertThat(fixture.calls.get()).isZero();
    }

    @Test void requiredHeaderAndBodyMismatchesFailBeforeBusinessDispatch() throws Exception {
        Map<String, Object> params = Map.of("name", "read_a", "arguments", Map.of());
        for (var response : List.of(
                send("a", "tools/call", params, "tools/list", "read_a", VERSION),
                send("a", "tools/call", params, null, "read_a", VERSION),
                send("a", "tools/call", params, "tools/call", "read_b", VERSION),
                send("a", "tools/call", params, "tools/call", null, VERSION),
                send("a", "tools/call", params, "tools/call", "read_a", "2025-11-25"))) {
            assertThat(response.statusCode()).isEqualTo(400);
            assertThat(response.body()).contains("-32020");
        }
        assertThat(fixture.calls.get()).isZero();
    }

    @Test void unsupportedVersionAndMissingMetadataFailBeforeBusinessDispatch() throws Exception {
        Map<String, Object> params = Map.of("_meta", Map.of(McpSchema.MetaKeys.PROTOCOL_VERSION, "1900-01-01",
                McpSchema.MetaKeys.CLIENT_CAPABILITIES, Map.of()));
        var badVersion = raw("a", "tools/list", params, "tools/list", null, "1900-01-01");
        assertThat(badVersion.statusCode()).isEqualTo(400);
        assertThat(badVersion.body()).contains("-32022", VERSION);
        var missing = raw("a", "tools/list", Map.of(), "tools/list", null, VERSION);
        assertThat(missing.statusCode()).isEqualTo(400);
        assertThat(missing.body()).contains("-32602");
        assertThat(fixture.calls.get()).isZero();
    }

    @Test void encodedNameIsDecodedAndMalformedEncodingRejected() throws Exception {
        var encoded = send("a", "tools/call", Map.of("name", "read_a", "arguments", Map.of()),
                "tools/call", "=?base64?cmVhZF9h?=", VERSION);
        assertThat(encoded.statusCode()).isEqualTo(200);
        var invalid = send("a", "tools/call", Map.of("name", "read_a", "arguments", Map.of()),
                "tools/call", "=?base64?%%%?=", VERSION);
        assertThat(invalid.statusCode()).isEqualTo(400);
        assertThat(invalid.body()).contains("-32020");
        assertThat(fixture.calls.get()).isEqualTo(1);
    }

    @Test void unknownMethodIs404WithoutBusinessDispatch() throws Exception {
        var response = send("a", "unsupported/method", Map.of(), "unsupported/method", null, VERSION);
        assertThat(response.statusCode()).isEqualTo(404);
        assertThat(response.body()).contains("-32601");
        assertThat(fixture.calls.get()).isZero();
    }

    @Test void previewAcceptsConflictingDuplicateRoutingHeader() throws Exception {
        // Characterizes an integration hazard, not a desired conformance assertion.
        var request = request("a", "tools/call", meta(Map.of("name", "read_a", "arguments", Map.of())),
                "tools/call", "read_a", VERSION).header("Mcp-Method", "tools/list").build();
        assertThat(http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        assertThat(fixture.calls.get()).isEqualTo(1);
    }

    @Test void previewDoesNotValidateCustomParameterHeaderAgainstBody() throws Exception {
        // SEP-2243 custom headers are explicitly absent from this preview.
        var request = request("a", "tools/call", meta(Map.of("name", "read_a", "arguments", Map.of("scope", "body"))),
                "tools/call", "read_a", VERSION).header("Mcp-Param-Scope", "conflict").build();
        assertThat(http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(200);
        assertThat(fixture.calls.get()).isEqualTo(1);
    }

    @Test void invalidArgumentsAndOriginAreRejectedBeforeExecution() throws Exception {
        var invalid = send("a", "tools/call", Map.of("name", "read_a", "arguments", Map.of("extra", true)),
                "tools/call", "read_a", VERSION);
        assertThat(invalid.body()).contains("\"isError\":true");
        var request = request("a", "tools/list", meta(Map.of()), "tools/list", null, VERSION)
                .header("Origin", "https://untrusted.invalid").build();
        assertThat(http.send(request, HttpResponse.BodyHandlers.ofString()).statusCode()).isEqualTo(403);
        assertThat(fixture.calls.get()).isZero();
    }

    @Test void legacyVerbsAreRejectedAndOfficialJavaClientCannotInitializeModernUpstream() throws Exception {
        for (String method : List.of("GET", "DELETE")) {
            var response = http.send(HttpRequest.newBuilder(uri).timeout(LIMIT).header("Authorization", "Bearer fixture-a")
                    .method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(response.statusCode()).isEqualTo(405);
        }
        var transport = HttpClientStreamableHttpTransport.builder(uri.resolve("/").toString())
                .endpoint(ENDPOINT).connectTimeout(LIMIT)
                .requestBuilder(HttpRequest.newBuilder().header("Authorization", "Bearer fixture-a"))
                .openConnectionOnStartup(false).build();
        var client = McpClient.async(transport).requestTimeout(Duration.ofSeconds(2)).build();
        try {
            assertThatThrownBy(() -> client.initialize().block(LIMIT)).isInstanceOf(RuntimeException.class);
            assertThat(fixture.posts.get()).isEqualTo(1);
            assertThat(fixture.calls.get()).isZero();
        } finally { client.closeGracefully().onErrorComplete().block(LIMIT); }
    }

    @Test void sseProgressArrivesBeforeFinalResultWithoutResumptionIds() throws Exception {
        var params = meta(Map.of("name", "read_a", "arguments", Map.of("stream", true)));
        var response = http.send(request("a", "tools/call", params, "tools/call", "read_a", VERSION).build(),
                HttpResponse.BodyHandlers.ofInputStream());
        assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
        assertThat(response.headers().firstValue("X-Accel-Buffering")).contains("no");
        try (var body = response.body()) {
            var reader = new BufferedReader(new InputStreamReader(body, StandardCharsets.UTF_8));
            String first = CompletableFuture.supplyAsync(() -> readThrough(reader, "notifications/progress"))
                    .get(3, TimeUnit.SECONDS);
            assertThat(first).doesNotContain("\"resultType\"", "\nid:");
            assertThat(fixture.finished.getCount()).isEqualTo(1);
            fixture.release.tryEmitValue(0L);
            String rest = CompletableFuture.supplyAsync(() -> readThrough(reader, "\"resultType\":\"complete\""))
                    .get(4, TimeUnit.SECONDS);
            assertThat(rest).contains("\"caller\":\"a\"").doesNotContain("\nid:");
        }
        assertThat(fixture.calls.get()).isEqualTo(1);
    }

    @Test void physicalDisconnectCancelsReactiveStreamWithinBound() throws Exception {
        String body = McpJsonDefaults.getMapper().writeValueAsString(new JsonRpc.JSONRPCRequest("2.0", "tools/call", 91,
                meta(Map.of("name", "read_a", "arguments", Map.of("stream", true, "never", true)))));
        try (var socket = new Socket("127.0.0.1", uri.getPort())) {
            socket.setSoTimeout(3000);
            String wire = "POST " + ENDPOINT + " HTTP/1.1\r\nHost: 127.0.0.1:" + uri.getPort()
                    + "\r\nAuthorization: Bearer fixture-a\r\nContent-Type: application/json\r\n"
                    + "Accept: application/json, text/event-stream\r\nMcp-Method: tools/call\r\nMcp-Name: read_a\r\n"
                    + "MCP-Protocol-Version: " + VERSION + "\r\nContent-Length: " + body.getBytes(StandardCharsets.UTF_8).length
                    + "\r\n\r\n" + body;
            socket.getOutputStream().write(wire.getBytes(StandardCharsets.UTF_8));
            assertThat(readThrough(new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8)),
                    "notifications/progress")).contains("200");
            socket.setSoLinger(true, 0);
        }
        assertThat(fixture.cancelled.await(5, TimeUnit.SECONDS)).isTrue();
        assertThat(fixture.calls.get()).isEqualTo(1);
        assertThat(fixture.finished.getCount()).isEqualTo(1);
    }

    static String readThrough(BufferedReader reader, String marker) {
        try {
            StringBuilder read = new StringBuilder();
            for (String line; (line = reader.readLine()) != null;) {
                read.append(line).append('\n');
                if (line.contains(marker)) return read.toString();
                if (read.length() > 32768) throw new AssertionError("Probe response exceeded bound");
            }
            throw new AssertionError("Stream ended without expected marker");
        } catch (java.io.IOException e) { throw new RuntimeException(e); }
    }

    HttpResponse<String> send(String caller, String method, Map<String, Object> params, String headerMethod,
            String name, String version) throws Exception {
        return raw(caller, method, meta(params), headerMethod, name, version);
    }
    HttpResponse<String> raw(String caller, String method, Map<String, Object> params, String headerMethod,
            String name, String version) throws Exception {
        return http.send(request(caller, method, params, headerMethod, name, version).build(), HttpResponse.BodyHandlers.ofString());
    }
    HttpRequest.Builder request(String caller, String method, Map<String, Object> params, String headerMethod,
            String name, String version) throws Exception {
        String body = McpJsonDefaults.getMapper().writeValueAsString(new JsonRpc.JSONRPCRequest("2.0", method, nextId.incrementAndGet(), params));
        var builder = HttpRequest.newBuilder(uri).timeout(LIMIT).header("Authorization", "Bearer fixture-" + caller)
                .header("Content-Type", "application/json").header("Accept", "application/json, text/event-stream")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (headerMethod != null) builder.header("Mcp-Method", headerMethod);
        if (name != null) builder.header("Mcp-Name", name);
        if (version != null) builder.header("MCP-Protocol-Version", version);
        return builder;
    }
    static Map<String, Object> meta(Map<String, Object> params) {
        var copy = new java.util.LinkedHashMap<>(params);
        copy.put("_meta", Map.of(McpSchema.MetaKeys.PROTOCOL_VERSION, VERSION,
                McpSchema.MetaKeys.CLIENT_CAPABILITIES, Map.of(), "progressToken", java.util.UUID.randomUUID().toString()));
        return copy;
    }
    static String other(String caller) { return "a".equals(caller) ? "b" : "a"; }

    @Configuration(proxyBeanMethods = false)
    @EnableAutoConfiguration
    static class ProbeConfiguration {
        @Bean Fixture fixture() { return new Fixture(); }
        @Bean FilterRegistrationBean<Filter> authentication(Fixture fixture) {
            // Fixed local fixture identities. This is not the gateway JWT/admission implementation.
            Filter filter = (request, response, chain) -> {
                var req = (HttpServletRequest) request;
                String auth = req.getHeader("Authorization");
                if (!List.of("Bearer fixture-a", "Bearer fixture-b").contains(auth == null ? "" : auth)) {
                    ((HttpServletResponse) response).setStatus(401); return;
                }
                req.setAttribute("caller", auth.endsWith("a") ? "a" : "b");
                if ("POST".equals(req.getMethod())) fixture.posts.incrementAndGet();
                chain.doFilter(request, response);
            };
            return new FilterRegistrationBean<>(filter);
        }
        @Bean ServletRegistrationBean<HttpServletMcpTransport> modernTransport(Fixture fixture) {
            var server = McpServer.builder().serverInfo(new McpSchema.Implementation("s0-probe", null, "0.1.0", null, null, null))
                    .feature(ToolsFeature.ofAsync(fixture)).build();
            var transport = HttpServletMcpTransport.builder(server).endpoint(ENDPOINT).maxRequestSize(32768)
                    .keepAliveInterval(Duration.ofMillis(100))
                    .contextExtractor(req -> McpTransportContext.create(Map.of("caller", req.getAttribute("caller"))))
                    .build();
            return new ServletRegistrationBean<>(transport, ENDPOINT);
        }
    }

    static class Fixture implements McpAsyncToolRepository {
        final AtomicInteger calls = new AtomicInteger();
        final AtomicInteger posts = new AtomicInteger();
        final CountDownLatch cancelled = new CountDownLatch(1);
        final CountDownLatch finished = new CountDownLatch(1);
        final reactor.core.publisher.Sinks.One<Long> release = reactor.core.publisher.Sinks.one();
        String caller(McpRequestContext ctx) { return (String) ctx.transportContext().get("caller"); }
        McpSchema.Tool tool(String caller) {
            return McpSchema.Tool.builder("read_" + caller, Map.of("type", "object", "additionalProperties", false,
                    "properties", Map.of("stream", Map.of("type", "boolean"), "never", Map.of("type", "boolean"),
                            "scope", Map.of("type", "string", "x-mcp-header", "Scope"))))
                    .outputSchema(Map.of("type", "object", "required", List.of("caller"),
                            "properties", Map.of("caller", Map.of("type", "string")), "additionalProperties", false)).build();
        }
        @Override public Mono<ToolsPage> list(McpRequestContext ctx, String cursor) {
            return Mono.just(ToolsPage.of(List.of(tool(caller(ctx)))));
        }
        @Override public Mono<McpSchema.Tool> find(McpRequestContext ctx, String name) {
            return ("read_" + caller(ctx)).equals(name) ? Mono.just(tool(caller(ctx))) : Mono.empty();
        }
        @Override public Mono<McpAsyncResponse<McpSchema.CallToolOutcome>> call(McpRequestContext ctx, McpSchema.CallToolRequest request) {
            if (!("read_" + caller(ctx)).equals(request.name())) return Mono.error(McpException.invalidParams("Unknown tool"));
            calls.incrementAndGet();
            var result = McpSchema.CallToolResult.builder().structuredContent(Map.of("caller", caller(ctx))).isError(false).build();
            if (!Boolean.TRUE.equals(request.arguments().get("stream"))) return Mono.just(McpAsyncResponse.result(result));
            return Mono.just(McpAsyncResponse.streaming(notifier -> {
                if (Boolean.TRUE.equals(request.arguments().get("never"))) {
                    return Flux.interval(Duration.ofMillis(40)).concatMap(n -> notifier.progress(n + 1, null, "working"))
                            .then(Mono.<McpSchema.CallToolOutcome>never()).doOnCancel(cancelled::countDown);
                }
                return notifier.progress(1, 2.0, "started").then(release.asMono())
                        .map(tick -> { finished.countDown(); return (McpSchema.CallToolOutcome) result; });
            }));
        }
    }
}
