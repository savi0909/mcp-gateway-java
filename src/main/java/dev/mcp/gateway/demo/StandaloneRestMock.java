package dev.mcp.gateway.demo;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;

import com.sun.net.httpserver.HttpServer;

/** Independent loopback REST demo. Only this fixture issues synthetic worker IDs. */
public final class StandaloneRestMock {
    private StandaloneRestMock() { }

    public static void main(String[] args) throws Exception {
        int port = args.length == 0 ? 19090 : Integer.parseInt(args[0]);
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        var count = new AtomicLong();
        server.createContext("/workers/ids", exchange -> {
            try (exchange) {
                byte[] input = exchange.getRequestBody().readNBytes(1025);
                if (!exchange.getRequestURI().toString().equals("/workers/ids")
                        || !exchange.getRequestMethod().equals("POST")
                        || !new String(input, StandardCharsets.UTF_8).equals("{}")) {
                    exchange.sendResponseHeaders(400, -1);
                    return;
                }
                byte[] body = ("{\"workerId\":\"worker-" + count.incrementAndGet() + "\"}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        server.createContext("/health", exchange -> {
            try (exchange) { exchange.sendResponseHeaders(200, -1); }
        });
        server.createContext("/stats", exchange -> {
            try (exchange) {
                byte[] body = ("{\"allocations\":" + count.get() + "}").getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                exchange.getResponseBody().write(body);
            }
        });
        Runtime.getRuntime().addShutdownHook(new Thread(() -> { server.stop(0); executor.close(); }));
        server.start();
        System.out.println("Independent REST mock: http://127.0.0.1:" + server.getAddress().getPort());
        new CountDownLatch(1).await();
    }
}
