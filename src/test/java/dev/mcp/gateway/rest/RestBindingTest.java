package dev.mcp.gateway.rest;

import java.net.URI;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RestBindingTest {

    @ParameterizedTest
    @ValueSource(strings = {"http://evil/workers", "//evil/workers", "workers", "/../workers", "/a/./b",
            "/a/%2e%2e/b", "/a/.%2E/b", "/a/%252e%252e/b", "/%2f%2fevil", "/a%2fb",
            "/a\\b", "/a%5cb", "/workers?x=1", "/workers#x", "/workers%3fx", "/workers%23x",
            "/workers%0d%0ax", "/workers%00", "/workers%20", "/workers%"})
    void rejectsUnsafePaths(String path) {
        assertThatThrownBy(() -> binding(RestBinding.Method.POST, path, "{}", "/workerId"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void resolvesAnEncodedOrdinaryPathWithoutChangingOrigin() {
        var binding = binding(RestBinding.Method.POST, "/custom/%69ds", " {\"static\": [1, true]} ", "/data/a~1b/~0id");
        assertThat(binding.target(URI.create("http://127.0.0.1:1234")))
                .isEqualTo(URI.create("http://127.0.0.1:1234/custom/%69ds"));
        assertThat(binding.requestBodyJson()).isEqualTo(" {\"static\": [1, true]} ");
        assertThat(binding.toString()).doesNotContain("custom", "static", "data");
    }

    @ParameterizedTest
    @ValueSource(strings = {"workerId", "/bad~", "/bad~2escape", "#fragment"})
    void rejectsInvalidPointerSyntaxRatherThanUsingJacksonLeniency(String pointer) {
        assertThatThrownBy(() -> RestBinding.pointer(pointer)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allowsRootPointerAndRejectsUnsupportedBindingsOrBodies() {
        assertThat(RestBinding.pointer("").toString()).isEmpty();
        assertThatThrownBy(() -> binding(RestBinding.Method.GET, "/workers", "{}", "/workerId"))
                .hasMessage("GET must omit requestBody");
        for (String body : new String[] {null, "", "{", "{} {}", "{\"x\":1,\"x\":2}"}) {
            assertThatThrownBy(() -> binding(RestBinding.Method.POST, "/workers", body, "/workerId"))
                    .isInstanceOf(IllegalArgumentException.class);
        }
        assertThatThrownBy(() -> new RestBinding("MCP", "worker-coordinator", RestBinding.Method.GET,
                "/workers", null, RestBinding.pointer("/workerId"))).hasMessage("only REST bindings are supported");
        assertThatThrownBy(() -> new RestBinding("REST", "other", RestBinding.Method.GET,
                "/workers", null, RestBinding.pointer("/workerId"))).hasMessage("unsupported backend reference");
    }

    private RestBinding binding(RestBinding.Method method, String path, String body, String pointer) {
        return new RestBinding("REST", "worker-coordinator", method, path, body, RestBinding.pointer(pointer));
    }
}
