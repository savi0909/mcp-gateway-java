package dev.mcp.gateway.admission;

import java.io.IOException;
import java.nio.file.Files;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Single-writer retained local journals. Admission/admin operations serialize on this instance. */
public final class AdmissionJournal implements AutoCloseable {
    private final JsonMapper json = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final SegmentedJournal policy, audit;
    private final long maxBytes;
    private AdmissionPolicy current;
    private String currentDigest;
    private int currentSegment;

    public AdmissionJournal(AdmissionSettings settings) throws IOException {
        Files.createDirectories(settings.journalDirectory()); maxBytes = settings.journalMaxBytes();
        policy = new SegmentedJournal(settings.journalDirectory(), "policy", settings.journalSegmentBytes(), maxBytes, json);
        SegmentedJournal openedAudit = null;
        try {
            openedAudit = new SegmentedJournal(settings.journalDirectory(), "audit", settings.journalSegmentBytes(), maxBytes, json);
            audit = openedAudit;
            if (policy.size() != 0 && audit.size() == 0) throw new IOException("Missing audit history");
            if (policy.size() == 0) {
                if (audit.size() != 0) throw new IOException("Incomplete initial provisioning");
                if (Files.size(settings.bootstrap()) > 1024 * 1024) throw new IOException("Invalid bootstrap size");
                var seed = json.readValue(Files.readAllBytes(settings.bootstrap()), AdmissionPolicy.class);
                appendAudit(Map.of("id", UUID.randomUUID().toString(), "at", Instant.now().toString(),
                        "decision", "TRUSTED_PROVISIONING", "revision", seed.revision(), "scope", "initial-private-policy"));
                policy.append(json.writeValueAsBytes(seed));
            }
            readPolicy(); visitAudits(record -> { });
        }
        catch (Exception failure) {
            if (openedAudit != null) openedAudit.close(); policy.close();
            throw new IOException("Admission journals are unavailable");
        }
    }
    public synchronized AdmissionPolicy readPolicy() throws IOException {
        String digest = SegmentedJournal.digest(policy.currentBytes());
        if (current != null && currentSegment == policy.count() && digest.equals(currentDigest)) return current;
        AdmissionPolicy[] latest = {null};
        policy.visit(line -> {
            var next = json.readValue(line, AdmissionPolicy.class);
            if (latest[0] != null && next.revision() != latest[0].revision() + 1)
                throw new IllegalArgumentException("Invalid policy revision");
            latest[0] = next;
        });
        if (latest[0] == null || current != null && (latest[0].revision() < current.revision()
                || latest[0].revision() == current.revision() && !latest[0].equals(current)))
            throw new IllegalArgumentException("Invalid policy history");
        current = latest[0]; currentDigest = digest; currentSegment = policy.count(); return current;
    }
    public synchronized void writePolicy(AdmissionPolicy state) throws IOException {
        var before = readPolicy();
        if (state.revision() != before.revision() + 1) throw new IllegalArgumentException("Invalid policy revision");
        policy.append(json.writeValueAsBytes(state));
        current = state; currentDigest = SegmentedJournal.digest(policy.currentBytes()); currentSegment = policy.count();
    }
    public synchronized void appendAudit(Map<String, Object> record) throws IOException {
        audit.append(json.writeValueAsBytes(record));
    }
    public synchronized void visitAudits(Consumer<Map<String, Object>> visitor) throws IOException {
        audit.visit(line -> visitor.accept(json.readValue(line, new tools.jackson.core.type.TypeReference<Map<String, Object>>() { })));
    }
    /** Bounded compatibility export; larger histories must use the paginated service API. */
    public synchronized List<Map<String, Object>> readAudits() throws IOException {
        var result = new ArrayList<Map<String, Object>>();
        int[] bytes = {0};
        visitAudits(record -> {
            bytes[0] += json.writeValueAsBytes(record).length;
            if (result.size() >= 10000 || bytes[0] > 16 * 1024 * 1024) throw AdmissionFailure.unavailable();
            result.add(record);
        });
        return List.copyOf(result);
    }
    public synchronized Map<String, Object> storageStatus() throws IOException {
        policy.verify(); audit.verify();
        return Map.of("policyBytes", policy.size(), "auditBytes", audit.size(), "maxBytesPerJournal", maxBytes,
                "policySegments", policy.count(), "auditSegments", audit.count(), "physicalPurgeEnabled", false);
    }
    @Override public synchronized void close() throws IOException {
        try { audit.close(); } finally { policy.close(); }
    }
}
