package dev.mcp.gateway.rest;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import dev.mcp.gateway.config.GatewayProperties;
import dev.mcp.gateway.support.IndependentHttpMock;
import dev.mcp.gateway.support.IndependentHttpMock.Mode;
import dev.mcp.gateway.support.IndependentHttpMock.Reply;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import reactor.netty.resources.ConnectionProvider;

import static dev.mcp.gateway.rest.AllocationResult.Code.*;
import static org.assertj.core.api.Assertions.assertThat;

@Timeout(10)
@ExtendWith(OutputCaptureExtension.class)
class RestBindingExecutorTest {

    private static final Duration TEST_WAIT = Duration.ofSeconds(4);
    private IndependentHttpMock mock;
    private ConnectionProvider connections;

    @BeforeEach
    void startIndependentHttpFixture() throws Exception {
        mock = new IndependentHttpMock();
        connections = ConnectionProvider.builder("executor-test").maxConnections(2)
                .pendingAcquireMaxCount(2).pendingAcquireTimeout(Duration.ofMillis(200)).build();
    }

    @AfterEach
    void stopFixtureAndConnections() {
        mock.close();
        connections.dispose();
    }

    @Test
    void constructingExecutorAndPublisherMakesZeroRequestsThenTwoSubscriptionsMakeTwoExactRequests() throws Exception {
        var executor = executor(mock.origin(), "fixture-only-token", Duration.ofSeconds(2), 65536);
        String body = " {\"static\": [1,true], \"nested\": {\"a\":\"b\"}} ";
        var binding = binding(RestBinding.Method.POST, "/custom/ids", body, "/workerId");
        mock.enqueue(Reply.json("{\"workerId\":\" 000123 \",\"private\":\"discard\"}"));
        mock.enqueue(Reply.json("{\"workerId\":\"worker-1002\"}"));
        var invocation = executor.execute(binding);
        assertThat(mock.count()).isZero();

        assertThat(await(invocation)).isEqualTo(new AllocationResult.Success(" 000123 "));
        assertThat(await(invocation)).isEqualTo(new AllocationResult.Success("worker-1002"));
        assertThat(mock.count()).isEqualTo(2);
        for (int i = 0; i < 2; i++) {
            var request = mock.takeRequest();
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.path()).isEqualTo("/custom/ids");
            assertThat(request.body()).isEqualTo(body);
            assertThat(request.header("Authorization")).isEqualTo("Bearer fixture-only-token");
            assertThat(request.header("Content-Type")).isEqualTo("application/json");
            assertThat(request.header("Accept")).isEqualTo("application/json");
        }
        assertThat(mock.pollRequest(150)).isNull();
    }

    @Test
    void getHasNoBodyOrAuthorizationAndUsesItsOwnPath() throws Exception {
        mock.enqueue(Reply.json("{\"workerId\":\"get-id\"}"));
        var result = await(executor(mock.origin(), "", Duration.ofSeconds(2), 65536)
                .execute(binding(RestBinding.Method.GET, "/allocation", null, "/workerId")));
        assertThat(result).isEqualTo(new AllocationResult.Success("get-id"));
        var request = mock.takeRequest();
        assertThat(request.method()).isEqualTo("GET");
        assertThat(request.path()).isEqualTo("/allocation");
        assertThat(request.body()).isEmpty();
        assertThat(request.header("Authorization")).isNull();
        assertThat(request.header("Content-Type")).isNull();
        assertThat(mock.count()).isEqualTo(1);
    }

    @ParameterizedTest
    @CsvSource({"9007199254740993,9007199254740993", "9223372036854775808123456789,9223372036854775808123456789",
            "-9007199254740993,-9007199254740993", "0,0"})
    void projectsNestedEscapedPointerAndExactIntegralIds(String value, String expected) throws Exception {
        mock.enqueue(Reply.json("{\"data\":{\"a/b\":{\"~id\":" + value + "}},\"other\":\"discard\"}"));
        var result = await(executor(mock.origin(), "", Duration.ofSeconds(2), 65536)
                .execute(binding(RestBinding.Method.POST, "/workers/ids", "{}", "/data/a~1b/~0id")));
        assertThat(result).isEqualTo(new AllocationResult.Success(expected));
        assertThat(result.toString()).doesNotContain(expected);
        assertThat(mock.count()).isEqualTo(1);
    }

    @Test
    void rootPointerCanSelectAStringResponse() throws Exception {
        mock.enqueue(Reply.json("\"root-id\""));
        assertThat(await(executor(mock.origin(), "", Duration.ofSeconds(2), 65536)
                .execute(binding(RestBinding.Method.POST, "/workers/ids", "{}", ""))))
                .isEqualTo(new AllocationResult.Success("root-id"));
        assertThat(mock.count()).isEqualTo(1);
    }

    @Test
    void preservesDuplicateBackendIdsWithoutCachingOrFabrication() throws Exception {
        mock.enqueue(Reply.json("{\"workerId\":\"same-id\"}"));
        mock.enqueue(Reply.json("{\"workerId\":\"same-id\"}"));
        var executor = executor(mock.origin(), "", Duration.ofSeconds(2), 65536);
        assertThat(await(executor.execute(defaultBinding()))).isEqualTo(new AllocationResult.Success("same-id"));
        assertThat(await(executor.execute(defaultBinding()))).isEqualTo(new AllocationResult.Success("same-id"));
        assertThat(mock.count()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"", " ", "{", "{}", "null", "[]", "{\"workerId\":null}", "{\"workerId\":\"\"}",
            "{\"workerId\":\"   \"}", "{\"workerId\":false}", "{\"workerId\":1.25}", "{\"workerId\":1.0}",
            "{\"workerId\":1e3}", "{\"workerId\":[]}", "{\"workerId\":{}}", "{\"workerId\":\"x\"} {}",
            "{\"workerId\":\"x\",\"workerId\":\"y\"}"})
    void rejectsUnusableResponsesWithOneRequest(String body) throws Exception {
        mock.enqueue(Reply.json(body));
        assertFailure(await(executor(mock.origin(), "", Duration.ofSeconds(2), 65536).execute(defaultBinding())),
                UPSTREAM_INVALID_RESPONSE);
        assertThat(mock.count()).isEqualTo(1);
        mock.takeRequest();
        assertThat(mock.pollRequest(50)).isNull();
    }

    @ParameterizedTest
    @ValueSource(ints = {201, 202, 299})
    void acceptsAnySuccessfulStatusWithValidJson(int status) throws Exception {
        mock.enqueue(new Reply(status, "{\"workerId\":\"valid\"}", null, Mode.NORMAL));
        assertThat(await(executor(mock.origin(), "", Duration.ofSeconds(2), 65536).execute(defaultBinding())))
                .isEqualTo(new AllocationResult.Success("valid"));
        assertThat(mock.count()).isEqualTo(1);
    }

    @Test
    void rejectsNoContentEvenWithSuccessfulStatus() throws Exception {
        mock.enqueue(new Reply(204, "", null, Mode.NORMAL));
        assertFailure(await(executor(mock.origin(), "", Duration.ofSeconds(2), 65536).execute(defaultBinding())),
                UPSTREAM_INVALID_RESPONSE);
        assertThat(mock.count()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(ints = {400, 401, 301, 302, 303, 307, 308})
    void sanitizesErrorsAndNeverFollowsRedirects(int status, CapturedOutput output) throws Exception {
        String canary = "fixture-canary-never-disclose";
        try (var redirectTarget = new IndependentHttpMock()) {
            mock.enqueue(new Reply(status, "{\"secret\":\"" + canary + "\"}",
                    redirectTarget.origin() + "/redirected", Mode.NORMAL));
            var result = await(executor(mock.origin(), "fixture-only-token", Duration.ofSeconds(2), 65536)
                    .execute(defaultBinding()));
            assertFailure(result, UPSTREAM_HTTP_ERROR);
            assertThat(result.toString() + ((AllocationResult.Failure) result).message())
                    .doesNotContain(canary, "fixture-only-token", mock.origin().toString());
            mock.takeRequest();
            assertThat(mock.pollRequest(100)).isNull();
            assertThat(mock.count()).isEqualTo(1);
            assertThat(redirectTarget.count()).isZero();
            assertThat(output.getAll()).doesNotContain(canary, "fixture-only-token");
        }
    }

    @Test
    void aStalledErrorBodyDoesNotDelayKnownHttpFailure() throws Exception {
        mock.enqueue(new Reply(400, "unbounded-error", null, Mode.WAIT_BODY));
        long started = System.nanoTime();
        assertFailure(await(executor(mock.origin(), "", Duration.ofSeconds(2), 65536).execute(defaultBinding())),
                UPSTREAM_HTTP_ERROR);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(1));
        assertThat(mock.count()).isEqualTo(1);
    }

    @ParameterizedTest
    @ValueSource(strings = {"NORMAL", "CHUNKED"})
    void enforcesByteLimitForKnownLengthAndChunkedBodies(String mode) throws Exception {
        String prefix = "{\"workerId\":\"";
        String suffix = "\"}";
        String atLimit = prefix + "x".repeat(1024 - prefix.length() - suffix.length()) + suffix;
        mock.enqueue(new Reply(200, atLimit, null, Mode.valueOf(mode)));
        mock.enqueue(new Reply(200, atLimit + " ", null, Mode.valueOf(mode)));
        var executor = executor(mock.origin(), "", Duration.ofSeconds(2), 1024);
        assertThat(await(executor.execute(defaultBinding()))).isInstanceOf(AllocationResult.Success.class);
        assertFailure(await(executor.execute(defaultBinding())), UPSTREAM_INVALID_RESPONSE);
        assertThat(mock.count()).isEqualTo(2);
    }

    @ParameterizedTest
    @ValueSource(strings = {"WAIT_HEADERS", "WAIT_BODY"})
    void deadlineBoundsHeadersAndEntireBodyWithNoRetry(String mode) throws Exception {
        mock.enqueue(new Reply(200, "{\"workerId\":\"too-late\"}", null, Mode.valueOf(mode)));
        long started = System.nanoTime();
        assertFailure(await(executor(mock.origin(), "", Duration.ofMillis(400), 65536).execute(defaultBinding())),
                UPSTREAM_TIMEOUT);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        mock.takeRequest();
        assertThat(mock.pollRequest(150)).isNull();
        assertThat(mock.count()).isEqualTo(1);
    }

    @Test
    void disconnectAfterRequestIsAutomaticallyRetriedWithTheSameStaticBody() throws Exception {
        mock.enqueue(new Reply(200, "", null, Mode.DISCONNECT));
        mock.enqueue(Reply.json("{\"workerId\":\"recovered\"}"));
        assertThat(await(executor(mock.origin(), "", Duration.ofSeconds(2), 65536).execute(defaultBinding())))
                .isEqualTo(new AllocationResult.Success("recovered"));
        var first = mock.takeRequest();
        var second = mock.takeRequest();
        assertThat(first).isEqualTo(second);
        assertThat(mock.pollRequest(200)).isNull();
        assertThat(mock.count()).isEqualTo(2);
    }

    @Test
    void connectionRefusalIsBoundedAndReturnsNoNetworkDetails() throws Exception {
        URI unusedOrigin;
        try (var reservation = new ServerSocket()) {
            reservation.bind(new InetSocketAddress("127.0.0.1", 0));
            unusedOrigin = URI.create("http://127.0.0.1:" + reservation.getLocalPort());
        }
        long started = System.nanoTime();
        var result = await(executor(unusedOrigin, "", Duration.ofSeconds(2), 65536).execute(defaultBinding()));
        assertFailure(result, UPSTREAM_TIMEOUT);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(3));
        assertThat(((AllocationResult.Failure) result).message()).doesNotContain(unusedOrigin.toString(), "Exception");
        assertThat(mock.count()).isZero();
    }

    @ParameterizedTest
    @ValueSource(ints = {408, 429, 500, 503})
    void transientHttpFailuresAreRetriedWithoutLeakingTheirBody(int status, CapturedOutput output) throws Exception {
        String canary = "fixture-retry-canary";
        mock.enqueue(new Reply(status, canary, null, Mode.WAIT_BODY));
        mock.enqueue(Reply.json("{\"workerId\":\"recovered\"}"));
        assertThat(await(executor(mock.origin(), "fixture-only-token", Duration.ofSeconds(2), 65536)
                .execute(defaultBinding()))).isEqualTo(new AllocationResult.Success("recovered"));
        assertThat(mock.takeRequest()).isEqualTo(mock.takeRequest());
        assertThat(mock.count()).isEqualTo(2);
        assertThat(mock.pollRequest(150)).isNull();
        assertThat(output.getAll()).doesNotContain(canary, "fixture-only-token");
    }

    @Test
    void persistentTransientFailuresStopAtTheTotalDeadline() throws Exception {
        for (int i = 0; i < 10; i++) {
            mock.enqueue(new Reply(503, "temporary failure", null, Mode.NORMAL));
        }
        long started = System.nanoTime();
        assertFailure(await(executor(mock.origin(), "", Duration.ofMillis(400), 65536).execute(defaultBinding())),
                UPSTREAM_TIMEOUT);
        assertThat(Duration.ofNanos(System.nanoTime() - started)).isLessThan(Duration.ofSeconds(2));
        int completedCount = mock.count();
        assertThat(completedCount).isBetween(2, 6);
        for (int i = 0; i < completedCount; i++) {
            mock.takeRequest();
        }
        assertThat(mock.pollRequest(200)).isNull();
        assertThat(mock.count()).isEqualTo(completedCount);
    }

    @Test
    void paymentRetriesReuseOneUuidV7KeyAndNewInvocationsGetNewKeys() throws Exception {
        mock.enqueue(new Reply(503, "temporary failure", null, Mode.NORMAL));
        mock.enqueue(Reply.json("{\"id\":9007199254740993}"));
        mock.enqueue(Reply.json("{\"id\":9007199254740994}"));
        var paymentBinding = new RestBinding("REST", "worker-coordinator", RestBinding.Method.POST,
                "/api/v1/payments", "{\"amount\":\"12.34\"}", RestBinding.pointer("/id"), "clientIdempotencyKey");
        var executor = executor(mock.origin(), "", Duration.ofSeconds(2), 65536);
        assertThat(await(executor.execute(paymentBinding)))
                .isEqualTo(new AllocationResult.Success("9007199254740993"));
        assertThat(await(executor.execute(paymentBinding)))
                .isEqualTo(new AllocationResult.Success("9007199254740994"));
        var first = mock.takeRequest();
        var retry = mock.takeRequest();
        var next = mock.takeRequest();
        assertThat(first).isEqualTo(retry);
        var json = tools.jackson.databind.json.JsonMapper.builder().build();
        var firstBody = json.readTree(first.body());
        var nextBody = json.readTree(next.body());
        var key = java.util.UUID.fromString(firstBody.path("clientIdempotencyKey").stringValue());
        assertThat(key.version()).isEqualTo(7);
        assertThat(key.variant()).isEqualTo(2);
        assertThat(nextBody.path("clientIdempotencyKey")).isNotEqualTo(firstBody.path("clientIdempotencyKey"));
        assertThat(firstBody.path("amount").stringValue()).isEqualTo("12.34");
        assertThat(nextBody.path("amount")).isEqualTo(firstBody.path("amount"));
        assertThat(mock.count()).isEqualTo(3);
    }

    @Test
    void malformedResponseCanaryIsAbsentFromResultAndLogs(CapturedOutput output) throws Exception {
        String canary = "fixture-parser-canary";
        mock.enqueue(Reply.json("{\"workerId\": " + canary));
        var result = await(executor(mock.origin(), "", Duration.ofSeconds(2), 65536).execute(defaultBinding()));
        assertFailure(result, UPSTREAM_INVALID_RESPONSE);
        assertThat(result.toString() + ((AllocationResult.Failure) result).message()).doesNotContain(canary);
        assertThat(output.getAll()).doesNotContain(canary);
    }

    private RestBindingExecutor executor(URI origin, String token, Duration deadline, int limit) {
        var properties = new GatewayProperties("classpath:catalog/worker-catalog.json",
                Map.of("worker-coordinator", new GatewayProperties.Backend(origin, token)),
                new GatewayProperties.Upstream(Duration.ofMillis(200), deadline, limit), List.of());
        return new RestBindingExecutor(properties, connections);
    }

    private RestBinding defaultBinding() {
        return binding(RestBinding.Method.POST, "/workers/ids", "{}", "/workerId");
    }

    private RestBinding binding(RestBinding.Method method, String path, String body, String pointer) {
        return new RestBinding("REST", "worker-coordinator", method, path, body, RestBinding.pointer(pointer));
    }

    private AllocationResult await(reactor.core.publisher.Mono<AllocationResult> result) throws Exception {
        return result.toFuture().get(TEST_WAIT.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void assertFailure(AllocationResult result, AllocationResult.Code expected) {
        assertThat(result).isEqualTo(new AllocationResult.Failure(expected));
        var failure = (AllocationResult.Failure) result;
        assertThat(failure.allocationOutcome()).isEqualTo("unknown");
        assertThat(failure.message()).contains("The operation outcome is unknown.")
                .doesNotContain("retry", "rollback", "workerId");
    }
}
