package dev.mcp.gateway.support;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/** Separate JDK HTTP listener: no Spring, WebClient or gateway code in request handling. */
public final class IndependentHttpMock implements AutoCloseable {

    public enum Mode { NORMAL, DISCONNECT, WAIT_HEADERS, WAIT_BODY, CHUNKED }

    public record Request(String method, String path, Map<String, List<String>> headers, String body) {
        public String header(String name) {
            return headers.entrySet().stream().filter(entry -> entry.getKey().equalsIgnoreCase(name))
                    .map(entry -> entry.getValue().getFirst()).findFirst().orElse(null);
        }
    }

    public record Reply(int status, String body, String location, Mode mode) {
        public static Reply json(String body) {
            return new Reply(200, body, null, Mode.NORMAL);
        }
    }

    private final HttpServer server;
    private final ExecutorService threads = Executors.newCachedThreadPool(Thread.ofPlatform().daemon().factory());
    private final BlockingQueue<Reply> replies = new LinkedBlockingQueue<>();
    private final BlockingQueue<Request> requests = new LinkedBlockingQueue<>();
    private final CountDownLatch release = new CountDownLatch(1);
    private final AtomicInteger count = new AtomicInteger();

    public IndependentHttpMock() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.setExecutor(threads);
        server.createContext("/", this::handle);
        server.start();
    }

    public URI origin() {
        return URI.create("http://127.0.0.1:" + server.getAddress().getPort());
    }

    public void enqueue(Reply reply) {
        replies.add(reply);
    }

    public int count() {
        return count.get();
    }

    public Request takeRequest() throws InterruptedException {
        Request request = requests.poll(2, TimeUnit.SECONDS);
        if (request == null) {
            throw new AssertionError("No HTTP request arrived within fixture budget");
        }
        return request;
    }

    public Request pollRequest(long millis) throws InterruptedException {
        return requests.poll(millis, TimeUnit.MILLISECONDS);
    }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            var headers = exchange.getRequestHeaders().entrySet().stream().collect(
                    java.util.stream.Collectors.toUnmodifiableMap(Map.Entry::getKey,
                            entry -> List.copyOf(entry.getValue())));
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            count.incrementAndGet();
            requests.add(new Request(exchange.getRequestMethod(), exchange.getRequestURI().toASCIIString(), headers, body));
            Reply reply = replies.poll();
            if (reply == null) {
                reply = new Reply(500, "unexpected fixture request", null, Mode.NORMAL);
            }
            if (reply.mode() == Mode.DISCONNECT) {
                return;
            }
            if (reply.mode() == Mode.WAIT_HEADERS) {
                awaitRelease();
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json");
            if (reply.location() != null) {
                exchange.getResponseHeaders().set("Location", reply.location());
            }
            byte[] bytes = reply.body().getBytes(StandardCharsets.UTF_8);
            if (reply.mode() == Mode.CHUNKED || reply.mode() == Mode.WAIT_BODY) {
                exchange.sendResponseHeaders(reply.status(), 0);
                if (reply.mode() == Mode.WAIT_BODY) {
                    // Send part of a JSON document before stalling the remainder.
                    exchange.getResponseBody().write('{');
                    exchange.getResponseBody().flush();
                    awaitRelease();
                }
                for (int offset = 0; offset < bytes.length; offset += 128) {
                    exchange.getResponseBody().write(bytes, offset, Math.min(128, bytes.length - offset));
                    exchange.getResponseBody().flush();
                }
            }
            else {
                exchange.sendResponseHeaders(reply.status(), bytes.length == 0 ? -1 : bytes.length);
                if (bytes.length > 0) {
                    exchange.getResponseBody().write(bytes);
                }
            }
        }
        catch (IOException ex) {
            // The executor deliberately cancels timed-out/oversized/error responses.
        }
    }

    private void awaitRelease() {
        try {
            if (!release.await(4, TimeUnit.SECONDS)) {
                throw new AssertionError("Fixture release exceeded its bounded wait");
            }
        }
        catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
        }
    }

    @Override
    public void close() {
        release.countDown();
        server.stop(0);
        threads.shutdownNow();
    }
}
