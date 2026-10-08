package dev.mcp.gateway.admission;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Single-writer local journals. Caller serializes admission/admin operations on this instance. */
public final class AdmissionJournal implements AutoCloseable {
    private static final int MAX = 16 * 1024 * 1024;
    private final JsonMapper json = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final Path policyPath, auditPath;
    private final FileChannel policy, audit;
    private final FileLock policyLock, auditLock;
    private final Object policyKey, auditKey;
    private boolean failed;
    private boolean closed;
    public AdmissionJournal(AdmissionSettings settings) throws IOException {
        Files.createDirectories(settings.journalDirectory());
        policyPath = settings.journalDirectory().resolve("policy.jsonl"); auditPath = settings.journalDirectory().resolve("audit.jsonl");
        policy = FileChannel.open(policyPath, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        FileChannel openedAudit = null; FileLock lockedPolicy = null, lockedAudit = null;
        try {
            lockedPolicy = policy.tryLock(); AdmissionPolicy.require(lockedPolicy != null);
            openedAudit = FileChannel.open(auditPath, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
            lockedAudit = openedAudit.tryLock(); AdmissionPolicy.require(lockedAudit != null);
            audit = openedAudit; policyLock = lockedPolicy; auditLock = lockedAudit;
            policyKey = key(policyPath); auditKey = key(auditPath);
            if (policy.size() == 0) {
                if (audit.size() != 0) throw new IOException("Incomplete initial provisioning");
                if (Files.size(settings.bootstrap()) > 1024 * 1024) throw new IOException("Invalid bootstrap size");
                var seed = json.readValue(Files.readAllBytes(settings.bootstrap()), AdmissionPolicy.class);
                appendAudit(Map.of("id", java.util.UUID.randomUUID().toString(), "at", Instant.now().toString(),
                        "decision", "TRUSTED_PROVISIONING", "revision", seed.revision(), "scope", "initial-private-policy"));
                writePolicy(seed);
            }
            readPolicy(); readAudits(); // reject truncated journals before serving.
        }
        catch (Exception failure) {
            if (lockedAudit != null) lockedAudit.close(); if (openedAudit != null) openedAudit.close();
            if (lockedPolicy != null) lockedPolicy.close(); policy.close();
            throw new IOException("Admission journals are unavailable");
        }
    }
    public synchronized AdmissionPolicy readPolicy() throws IOException {
        verify(policyPath, policy, policyKey);
        var lines = read(policy); AdmissionPolicy current = null;
        for (String line : lines) {
            var next = json.readValue(line, AdmissionPolicy.class);
            if (current != null && next.revision() != current.revision() + 1) throw new IllegalArgumentException("Invalid policy revision");
            current = next;
        }
        if (current == null) throw new IllegalArgumentException("Missing policy");
        return current;
    }
    public synchronized void writePolicy(AdmissionPolicy state) throws IOException {
        verify(policyPath, policy, policyKey); append(policy, json.writeValueAsBytes(state));
    }
    public synchronized void appendAudit(Map<String, Object> record) throws IOException {
        verify(auditPath, audit, auditKey); append(audit, json.writeValueAsBytes(record));
    }
    public synchronized List<Map<String, Object>> readAudits() throws IOException {
        verify(auditPath, audit, auditKey); var result = new ArrayList<Map<String, Object>>();
        for (String line : read(audit)) result.add(json.readValue(line, new tools.jackson.core.type.TypeReference<Map<String, Object>>() { }));
        return List.copyOf(result);
    }
    private Object key(Path path) throws IOException { return Files.readAttributes(path, BasicFileAttributes.class).fileKey(); }
    private void verify(Path path, FileChannel channel, Object expected) throws IOException {
        if (failed || !channel.isOpen() || !java.util.Objects.equals(key(path), expected)) throw new IOException("Journal unavailable");
    }
    private List<String> read(FileChannel channel) throws IOException {
        long size = channel.size(); if (size <= 0 || size > MAX) throw new IOException("Invalid journal size");
        var bytes = ByteBuffer.allocate((int) size); channel.position(0);
        while (bytes.hasRemaining()) if (channel.read(bytes) < 0) throw new IOException("Incomplete journal");
        String body = java.nio.charset.StandardCharsets.UTF_8.newDecoder().decode(bytes.flip()).toString();
        if (!body.endsWith("\n")) throw new IllegalArgumentException("Incomplete journal");
        return body.lines().toList();
    }
    private void append(FileChannel channel, byte[] bytes) throws IOException {
        if (channel.size() + bytes.length + 1 > MAX) throw new IOException("Journal capacity exceeded");
        channel.position(channel.size());
        try { var buffer = ByteBuffer.wrap(bytes); while (buffer.hasRemaining()) channel.write(buffer);
            var newline = ByteBuffer.wrap(new byte[]{'\n'}); while (newline.hasRemaining()) channel.write(newline); channel.force(true); }
        catch (IOException failure) { failed = true; throw failure; }
    }
    @Override public synchronized void close() throws IOException {
        if (closed) return; closed = true;
        try { auditLock.close(); audit.close(); } finally { policyLock.close(); policy.close(); }
    }
}
