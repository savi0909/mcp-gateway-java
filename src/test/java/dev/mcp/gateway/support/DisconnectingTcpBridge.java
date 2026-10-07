package dev.mcp.gateway.support;

import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;

/** Byte-forwarding fixture, no protocol/body parsing or capture. Closing resets SDK HTTP streams. */
public final class DisconnectingTcpBridge implements AutoCloseable {
    private final ServerSocket listener = new ServerSocket();
    private final ExecutorService threads = Executors.newVirtualThreadPerTaskExecutor();
    private final Set<Socket> sockets = ConcurrentHashMap.newKeySet();
    public DisconnectingTcpBridge(int destination) throws Exception {
        listener.bind(new InetSocketAddress("127.0.0.1", 0));
        threads.submit(() -> {
            while (!listener.isClosed()) {
                try {
                    Socket client = listener.accept();
                    Socket server = new Socket("127.0.0.1", destination);
                    sockets.add(client); sockets.add(server);
                    threads.submit(() -> copy(client, server));
                    threads.submit(() -> copy(server, client));
                }
                catch (java.io.IOException closed) { break; }
            }
        });
    }
    public String origin() { return "http://127.0.0.1:" + listener.getLocalPort(); }
    private void copy(Socket from, Socket to) {
        try { from.getInputStream().transferTo(to.getOutputStream()); }
        catch (java.io.IOException expectedDisconnect) { }
        finally { reset(from); reset(to); sockets.remove(from); sockets.remove(to); }
    }
    private void reset(Socket socket) {
        try { socket.setSoLinger(true, 0); socket.close(); } catch (java.io.IOException ignored) { }
    }
    @Override public void close() throws Exception {
        listener.close(); sockets.forEach(this::reset); threads.close();
    }
}
