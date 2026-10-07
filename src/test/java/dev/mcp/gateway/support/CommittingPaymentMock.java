package dev.mcp.gateway.support;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpServer;
import tools.jackson.databind.json.JsonMapper;

/** Models durable idempotency and commit-before-response-loss, independently of gateway execution. */
public final class CommittingPaymentMock implements AutoCloseable {
    public enum Mode { DROP_FIRST, DROP_ALWAYS, STALL_AFTER_COMMIT }
    public record Attempt(String key, String body) { }
    private final HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    private final Map<String, Long> results = new ConcurrentHashMap<>();
    private final LinkedBlockingQueue<Attempt> attempts = new LinkedBlockingQueue<>();
    private final AtomicInteger count = new AtomicInteger();
    private final java.util.concurrent.atomic.AtomicLong nextId = new java.util.concurrent.atomic.AtomicLong(9007199254740993L);
    private final CountDownLatch release = new CountDownLatch(1);
    private final JsonMapper json = JsonMapper.builder().build();
    public CommittingPaymentMock(Mode mode) throws Exception {
        server.setExecutor(threads);
        server.createContext("/api/v1/payments", exchange -> {
            try (exchange) {
                String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String key = json.readTree(body).path("clientIdempotencyKey").stringValue();
                long result = results.computeIfAbsent(key, ignored -> nextId.getAndIncrement());
                int attempt = count.incrementAndGet(); attempts.add(new Attempt(key, body));
                if (mode == Mode.DROP_ALWAYS || mode == Mode.DROP_FIRST && attempt == 1) return;
                if (mode == Mode.STALL_AFTER_COMMIT) {
                    try { release.await(5, TimeUnit.SECONDS); } catch (InterruptedException interrupted) { Thread.currentThread().interrupt(); }
                }
                byte[] response = json.writeValueAsBytes(Map.of("id", result));
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, response.length);
                exchange.getResponseBody().write(response);
            }
            catch (java.io.IOException disconnected) { /* Expected loss/cancellation. */ }
        });
        server.start();
    }
    public URI origin() { return URI.create("http://127.0.0.1:" + server.getAddress().getPort()); }
    public int count() { return count.get(); }
    public int commits() { return results.size(); }
    public Long result(String key) { return results.get(key); }
    public Attempt take() throws InterruptedException {
        var attempt = attempts.poll(2, TimeUnit.SECONDS);
        if (attempt == null) throw new AssertionError("No payment attempt within fixture budget");
        return attempt;
    }
    public Attempt poll(long millis) throws InterruptedException { return attempts.poll(millis, TimeUnit.MILLISECONDS); }
    @Override public void close() { release.countDown(); server.stop(0); threads.close(); }
}
