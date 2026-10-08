package dev.mcp.gateway.rest;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.TimeoutException;

import dev.mcp.gateway.config.GatewayProperties;
import io.netty.channel.ChannelOption;
import io.netty.channel.ConnectTimeoutException;
import io.netty.handler.timeout.ReadTimeoutException;
import org.springframework.core.io.buffer.DataBuffer;
import org.springframework.core.io.buffer.DataBufferLimitException;
import org.springframework.core.io.buffer.DataBufferUtils;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.ClientResponse;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.netty.http.client.HttpClient;
import reactor.netty.resources.ConnectionProvider;
import reactor.util.retry.Retry;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

import static dev.mcp.gateway.rest.AllocationResult.Code.*;

/** Allocation retries are explicit and bounded by one total deadline. No startup call or result cache. */
public final class RestBindingExecutor {

    private final GatewayProperties properties;
    private final WebClient client;
    private final JsonMapper json;

    public RestBindingExecutor(GatewayProperties properties, ConnectionProvider connections) {
        this.properties = properties;
        HttpClient http = HttpClient.create(connections)
                .disableRetry(true)
                .followRedirect(false)
                .option(ChannelOption.CONNECT_TIMEOUT_MILLIS, (int) properties.upstream().connectTimeout().toMillis())
                .responseTimeout(properties.upstream().deadline());
        this.client = WebClient.builder()
                .clientConnector(new ReactorClientHttpConnector(http))
                .codecs(codecs -> codecs.defaultCodecs().maxInMemorySize(properties.upstream().maxResponseBytes()))
                .build();
        this.json = JsonMapper.builder()
                .enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
                .enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS)
                .enable(DeserializationFeature.USE_BIG_INTEGER_FOR_INTS)
                .enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS)
                .build();
    }

    public Mono<AllocationResult> execute(RestBinding binding) {
        // A new request key per invocation, generated outside the retried publisher.
        return Mono.defer(() -> executeWithBody(binding, bodyForInvocation(binding)));
    }

    private String bodyForInvocation(RestBinding binding) {
        if (binding.requestKeyField() == null) {
            return binding.requestBodyJson();
        }
        ObjectNode body = (ObjectNode) json.readTree(binding.requestBodyJson());
        body.put(binding.requestKeyField(), UuidV7RequestKey.next());
        return json.writeValueAsString(body);
    }

    private Mono<AllocationResult> executeWithBody(RestBinding binding, String body) {
        return attempt(binding, body, properties.backends().get(binding.backendRef()).bearerToken())
                .retryWhen(Retry.backoff(Long.MAX_VALUE, Duration.ofMillis(100))
                        .maxBackoff(Duration.ofSeconds(1))
                        .filter(error -> error instanceof AttemptFailure failure && failure.retryable))
                .timeout(properties.upstream().deadline())
                .onErrorResume(error -> Mono.just(new AllocationResult.Failure(
                        error instanceof AttemptFailure failure ? failure.code : category(error))));
    }

    /** UC-01: explicit context credential, one GET attempt, no shared-token fallback. */
    public Mono<AllocationResult> executeAuthorizedRead(RestBinding binding, String token) {
        if (binding.method() != RestBinding.Method.GET || token == null || token.isBlank())
            return Mono.error(new IllegalArgumentException("Invalid authorized read"));
        return attempt(binding, null, token).timeout(properties.upstream().deadline())
                .onErrorResume(error -> Mono.just(new AllocationResult.Failure(
                        error instanceof AttemptFailure failure ? failure.code : category(error))));
    }

    private Mono<AllocationResult> attempt(RestBinding binding, String body, String token) {
        return Mono.defer(() -> {
            var backend = properties.backends().get(binding.backendRef());
            var request = client.method(HttpMethod.valueOf(binding.method().name()))
                    .uri(binding.target(backend.baseUrl()))
                    .accept(MediaType.APPLICATION_JSON);
            if (!token.isEmpty()) {
                request.headers(headers -> headers.setBearerAuth(token));
            }
            WebClient.RequestHeadersSpec<?> ready = request;
            if (binding.method() == RestBinding.Method.POST) {
                ready = request.contentType(MediaType.APPLICATION_JSON)
                        .bodyValue(body.getBytes(StandardCharsets.UTF_8));
            }
            return ready.exchangeToMono(response -> read(response, binding));
        })
                .onErrorMap(error -> {
                    if (error instanceof AttemptFailure) {
                        return error;
                    }
                    var code = category(error);
                    return new AttemptFailure(code, code == UPSTREAM_UNAVAILABLE || code == UPSTREAM_TIMEOUT);
                });
    }

    private Mono<AllocationResult> read(ClientResponse response, RestBinding binding) {
        if (!response.statusCode().is2xxSuccessful()) {
            int status = response.statusCode().value();
            // Subscribe and cancel the body immediately: do not drain an unbounded/error body.
            // Signal only after the body subscription exists; otherwise WebClient drains it on release.
            var stop = Sinks.<Void>empty();
            return response.bodyToFlux(DataBuffer.class)
                    .doOnSubscribe(subscription -> stop.tryEmitEmpty())
                    .takeUntilOther(stop.asMono())
                    .doOnDiscard(DataBuffer.class, DataBufferUtils::release)
                    .then(Mono.error(new AttemptFailure(UPSTREAM_HTTP_ERROR,
                            status == 408 || status == 429 || status >= 500 && status < 600)));
        }
        return DataBufferUtils.join(response.bodyToFlux(DataBuffer.class), properties.upstream().maxResponseBytes())
                .map(buffer -> {
                    byte[] bytes;
                    try {
                        bytes = new byte[buffer.readableByteCount()];
                        buffer.read(bytes);
                    }
                    finally {
                        DataBufferUtils.release(buffer);
                    }
                    return project(bytes, binding);
                })
                .defaultIfEmpty(new AllocationResult.Failure(UPSTREAM_INVALID_RESPONSE));
    }

    private AllocationResult project(byte[] bytes, RestBinding binding) {
        try {
            var root = json.readTree(bytes);
            if (binding.responseObject()) {
                if (!root.isObject()) {
                    return new AllocationResult.Failure(UPSTREAM_INVALID_RESPONSE);
                }
                return new AllocationResult.ObjectSuccess(json.convertValue(root,
                        new tools.jackson.core.type.TypeReference<java.util.Map<String, Object>>() { }));
            }
            var id = root.at(binding.responseIdPointer());
            if (id.isString() && !id.stringValue().isBlank()) {
                return new AllocationResult.Success(id.stringValue());
            }
            if (id.isIntegralNumber()) {
                return new AllocationResult.Success(id.bigIntegerValue().toString());
            }
        }
        catch (RuntimeException ex) {
            // Parser exceptions may include response bytes; never retain or log them.
        }
        return new AllocationResult.Failure(UPSTREAM_INVALID_RESPONSE);
    }

    private AllocationResult.Code category(Throwable error) {
        for (Throwable cause = error; cause != null; cause = cause.getCause()) {
            if (cause instanceof TimeoutException || cause instanceof ConnectTimeoutException
                    || cause instanceof ReadTimeoutException) {
                return UPSTREAM_TIMEOUT;
            }
            if (cause instanceof DataBufferLimitException) {
                return UPSTREAM_INVALID_RESPONSE;
            }
        }
        return UPSTREAM_UNAVAILABLE;
    }

    /** Retains only a safe category; never carry upstream exception text, URLs, headers or bodies. */
    private static final class AttemptFailure extends RuntimeException {
        private final AllocationResult.Code code;
        private final boolean retryable;

        private AttemptFailure(AllocationResult.Code code, boolean retryable) {
            super(code.name(), null, false, false);
            this.code = code;
            this.retryable = retryable;
        }
    }
}
