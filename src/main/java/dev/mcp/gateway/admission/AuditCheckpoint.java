package dev.mcp.gateway.admission;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

/** Private offline storage maintenance. No evidence removal or business replay. */
public final class AuditCheckpoint {
    @FunctionalInterface interface DurableWriter { void write(Path path, byte[] bytes) throws IOException; }
    public record Receipt(String checkpointId, String manifestSha256, long policyRevision) { }
    public record Entry(String name, long bytes, long records, String sha256) { }
    public record Manifest(int version, String format, String checkpointId, String at, String actor, String reason,
            long policyRevision, int segmentBytes, long maxBytes, List<Entry> files) { }
    public record RecoveryAuthorization(String manifestSha256, long minimumPolicyRevision, String verifiedAt,
            String expiresAt, String actor, String reason) { }
    private final JsonMapper json = JsonMapper.builder().enable(StreamReadFeature.STRICT_DUPLICATE_DETECTION)
            .enable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES).enable(DeserializationFeature.FAIL_ON_TRAILING_TOKENS).build();
    private final DurableWriter writer;
    public AuditCheckpoint() { this(AuditCheckpoint::writeForced); }
    AuditCheckpoint(DurableWriter writer) { this.writer = Objects.requireNonNull(writer); }

    public Receipt checkpoint(AdmissionSettings settings, Path destination, String actor, String reason) throws IOException {
        references(actor, reason); requireSource(settings.journalDirectory());
        Path source = settings.journalDirectory().toRealPath();
        Path parent = destination.toAbsolutePath().getParent().toRealPath();
        if (parent.startsWith(source)) throw new IOException("Archive must be outside source");
        String id = UUID.randomUUID().toString();
        Instant acceptedAt = Instant.now();
        try (var journal = new AdmissionJournal(settings)) {
            synchronized (journal) {
                journal.appendAudit(event(id, "CHECKPOINT_STARTED", actor, reason, acceptedAt));
                try {
                    Files.createDirectory(destination);
                    var files = new ArrayList<Entry>();
                    journal.snapshot((name, bytes) -> {
                        writeChecked(destination.resolve(name), bytes);
                        files.add(new Entry(name, bytes.length, recordCount(bytes), SegmentedJournal.digest(bytes)));
                    });
                    long revision = journal.readPolicy().revision();
                    var manifest = new Manifest(1, "gateway-uc01-segmented-s2-v1", id, Instant.now().toString(), actor, reason, revision,
                            settings.journalSegmentBytes(), settings.journalMaxBytes(), List.copyOf(files));
                    byte[] bytes = json.writeValueAsBytes(manifest); String digest = SegmentedJournal.digest(bytes);
                    writeChecked(destination.resolve("manifest.json"), bytes);
                    writeChecked(destination.resolve("checkpoint.complete"), line(digest));
                    var completed = new LinkedHashMap<>(event(id, "CHECKPOINT_COMPLETED", actor, reason, acceptedAt));
                    completed.put("manifestSha256", digest); completed.put("revision", revision);
                    journal.appendAudit(completed);
                    return new Receipt(id, digest, revision);
                }
                catch (Exception failure) {
                    try { journal.appendAudit(event(id, "CHECKPOINT_FAILED", actor, reason, acceptedAt)); }
                    catch (Exception auditFailure) { failure.addSuppressed(auditFailure); }
                    throw new IOException("Checkpoint unavailable; preserve incomplete destination", failure);
                }
            }
        }
    }

    public void restore(Path archive, AdmissionSettings destination, String trustedDigest, long expectedRevision,
            String actor, String reason) throws IOException {
        references(actor, reason);
        Instant acceptedAt = Instant.now();
        Manifest manifest = verify(archive, trustedDigest, expectedRevision);
        if (destination.journalSegmentBytes() != manifest.segmentBytes() || destination.journalMaxBytes() != manifest.maxBytes())
            throw new IOException("Restore storage bounds mismatch");
        Path target = destination.journalDirectory().toAbsolutePath();
        if (Files.exists(target, LinkOption.NOFOLLOW_LINKS) || target.getParent().toRealPath().startsWith(archive.toRealPath()))
            throw new IOException("Restore requires a new destination outside archive");
        String operationId = UUID.randomUUID().toString();
        var prepared = new LinkedHashMap<>(event(operationId, "RESTORE_PREPARED", actor, reason, acceptedAt));
        prepared.put("manifestSha256", trustedDigest); prepared.put("revision", expectedRevision);
        // Intent is outside the destination: even interruption before its first file blocks bootstrap.
        writeChecked(restoreIntent(target), json.writeValueAsBytes(prepared));
        Files.createDirectory(destination.journalDirectory());
        writeChecked(destination.journalDirectory().resolve("restore.pending"), line(trustedDigest));
        for (var entry : manifest.files()) {
            byte[] bytes = readEntry(archive, entry, manifest.segmentBytes());
            writeChecked(destination.journalDirectory().resolve(entry.name()), bytes);
        }
        try (var journal = new AdmissionJournal(destination, true)) {
            if (journal.readPolicy().revision() != expectedRevision) throw new IOException("Restore policy revision mismatch");
            var event = new LinkedHashMap<>(event(operationId, "RESTORE_COMPLETED", actor, reason, acceptedAt));
            event.put("checkpointId", manifest.checkpointId()); event.put("manifestSha256", trustedDigest);
            event.put("revision", expectedRevision); journal.appendAudit(event);
        }
        writeChecked(destination.journalDirectory().resolve("restore.complete"), line(trustedDigest));
    }

    public Manifest verify(Path archive, String trustedDigest, long expectedRevision) throws IOException {
        try {
            if (trustedDigest == null || !trustedDigest.matches("[0-9a-f]{64}") || expectedRevision < 1
                    || Files.isSymbolicLink(archive) || !Files.isDirectory(archive, LinkOption.NOFOLLOW_LINKS))
                throw new IOException("Invalid archive trust anchor");
            byte[] manifestBytes = boundedRead(archive.resolve("manifest.json"), 1024 * 1024);
            if (!trustedDigest.equals(SegmentedJournal.digest(manifestBytes))
                    || !Arrays.equals(line(trustedDigest), boundedRead(archive.resolve("checkpoint.complete"), 65)))
                throw new IOException("Uncommitted or untrusted archive");
            var manifest = json.readValue(manifestBytes, Manifest.class);
            if (manifest.version() != 1 || !"gateway-uc01-segmented-s2-v1".equals(manifest.format())
                    || manifest.policyRevision() != expectedRevision || manifest.files() == null
                    || manifest.files().size() < 2 || manifest.files().size() > 2048)
                throw new IOException("Invalid manifest");
            references(manifest.actor(), manifest.reason()); UUID.fromString(manifest.checkpointId()); Instant.parse(manifest.at());
            new AdmissionSettings(Path.of("unused"), Path.of("unused"), java.time.Duration.ofSeconds(30), Map.of(), manifest.segmentBytes(), manifest.maxBytes());
            var names = new HashSet<String>(); var totals = new HashMap<String, Long>();
            for (var entry : manifest.files()) {
                if (entry.name() == null || !entry.name().matches("(policy|audit)(\\.[0-9]{6})?\\.jsonl") || !names.add(entry.name()))
                    throw new IOException("Invalid archive inventory");
                String kind = entry.name().startsWith("policy") ? "policy" : "audit";
                if (totals.merge(kind, entry.bytes(), Long::sum) > manifest.maxBytes()) throw new IOException("Archive capacity exceeded");
                readEntry(archive, entry, manifest.segmentBytes());
            }
            if (!names.containsAll(Set.of("policy.jsonl", "audit.jsonl"))) throw new IOException("Missing base history");
            var ordered = new ArrayList<String>();
            for (String kind : List.of("policy", "audit")) {
                int count = (int) names.stream().filter(name -> name.startsWith(kind)).count();
                for (int index = 0; index < count; index++)
                    ordered.add(kind + (index == 0 ? "" : "." + String.format(Locale.ROOT, "%06d", index)) + ".jsonl");
            }
            if (!ordered.equals(manifest.files().stream().map(Entry::name).toList())) throw new IOException("Invalid archive ordering");
            names.add("manifest.json"); names.add("checkpoint.complete");
            try (var paths = Files.newDirectoryStream(archive)) {
                int count = 0;
                for (var path : paths) if (++count > names.size() || !names.contains(path.getFileName().toString()))
                    throw new IOException("Unexpected archive content");
                if (count != names.size()) throw new IOException("Missing archive content");
            }
            return manifest;
        }
        catch (RuntimeException failure) { throw new IOException("Archive verification failed", failure); }
    }

    private byte[] readEntry(Path archive, Entry entry, int limit) throws IOException {
        if (entry.bytes() < 0 || entry.bytes() > limit || entry.sha256() == null || !entry.sha256().matches("[0-9a-f]{64}"))
            throw new IOException("Invalid archive entry");
        byte[] bytes = boundedRead(archive.resolve(entry.name()), limit);
        if (bytes.length != entry.bytes() || entry.records() != recordCount(bytes)
                || !entry.sha256().equals(SegmentedJournal.digest(bytes))) throw new IOException("Archive integrity failure");
        return bytes;
    }
    static byte[] boundedRead(Path path, int limit) throws IOException {
        if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.size(path) > limit) throw new IOException("Invalid archive file");
        try (var input = Files.newInputStream(path, LinkOption.NOFOLLOW_LINKS)) {
            byte[] bytes = input.readNBytes(limit + 1);
            if (bytes.length > limit) throw new IOException("Archive file too large");
            return bytes;
        }
    }
    static void requireSource(Path directory) throws IOException {
        if (Files.isSymbolicLink(directory)) throw new IOException("Invalid source directory");
        for (String kind : List.of("policy", "audit"))
            if (!Files.isRegularFile(directory.resolve(kind + ".jsonl"), LinkOption.NOFOLLOW_LINKS)
                    || Files.size(directory.resolve(kind + ".jsonl")) == 0) throw new IOException("Missing source history");
    }
    private void writeChecked(Path path, byte[] bytes) throws IOException {
        writer.write(path, bytes);
        if (!Arrays.equals(bytes, boundedRead(path, bytes.length))) throw new IOException("Storage read-back failed");
    }
    static void writeForced(Path path, byte[] bytes) throws IOException {
        try (var channel = FileChannel.open(path, StandardOpenOption.CREATE_NEW, StandardOpenOption.WRITE)) {
            var buffer = ByteBuffer.wrap(bytes);
            while (buffer.hasRemaining()) channel.write(buffer);
            channel.force(true);
        }
    }
    private static byte[] line(String value) { return (value + "\n").getBytes(StandardCharsets.US_ASCII); }
    private static long recordCount(byte[] bytes) {
        long count = 0; for (byte value : bytes) if (value == '\n') count++; return count;
    }
    static Path restoreIntent(Path directory) {
        return directory.toAbsolutePath().resolveSibling(directory.getFileName() + ".restore.pending");
    }
    static RecoveryAuthorization verifyRecoveryAuthorization(AdmissionSettings settings, long revision) throws IOException {
        try {
            Path path = settings.recoveryAuthorization();
            if (path == null || path.toRealPath().startsWith(settings.journalDirectory().toRealPath()))
                throw new IOException("Independent recovery authorization required");
            for (Path parent = path.toRealPath().getParent(); parent != null; parent = parent.getParent())
                if (Files.exists(parent.resolve("checkpoint.complete"))) throw new IOException("Recovery authorization cannot be archived material");
            var checkpoints = new AuditCheckpoint();
            var authorization = checkpoints.json.readValue(boundedRead(path, 4096), RecoveryAuthorization.class);
            references(authorization.actor(), authorization.reason());
            Instant now = Instant.now(), verified = Instant.parse(authorization.verifiedAt()), expires = Instant.parse(authorization.expiresAt());
            if (verified.isAfter(now) || !expires.isAfter(now) || !expires.isAfter(verified)
                    || java.time.Duration.between(verified, expires).compareTo(java.time.Duration.ofSeconds(30)) > 0
                    || authorization.minimumPolicyRevision() < 1 || revision < authorization.minimumPolicyRevision()
                    || !Arrays.equals(line(authorization.manifestSha256()), boundedRead(settings.journalDirectory().resolve("restore.pending"), 65)))
                throw new IOException("Stale or conflicting recovery authority");
            return authorization;
        }
        catch (RuntimeException failure) { throw new IOException("Recovery authority unavailable", failure); }
    }
    private static void references(String actor, String reason) throws IOException {
        if (actor == null || reason == null || !actor.matches("[A-Za-z0-9][A-Za-z0-9._:@/-]{0,127}")
                || !reason.matches("[A-Za-z0-9][A-Za-z0-9._:@/-]{0,127}")) throw new IOException("Operator and ticket references required");
    }
    private static Map<String, Object> event(String id, String decision, String actor, String reason, Instant acceptedAt) {
        String at = Instant.now().toString();
        var record = new LinkedHashMap<String, Object>(Map.of("id", id, "decision", decision, "actor", actor, "reason", reason,
                "at", at, "acceptedAt", acceptedAt.toString(), "scope", "ALL_RETAINED_SCOPES",
                "status", decision.endsWith("COMPLETED") ? "COMPLETED" : decision.endsWith("FAILED") ? "FAILED" : "PREPARED"));
        if (decision.endsWith("COMPLETED")) record.put("effectiveAt", at);
        return record;
    }

    /** Run through the pinned Maven runtime; arguments contain paths/references, never credentials. */
    public static void main(String[] args) throws Exception {
        var checkpoints = new AuditCheckpoint();
        if (args.length < 1) throw new IllegalArgumentException("Expected checkpoint, restore, verify or status");
        switch (args[0]) {
            case "checkpoint" -> {
                if (args.length != 8 && args.length != 9) throw new IllegalArgumentException("checkpoint source archive actor ticket segmentBytes maxBytes bootstrap [recoveryAuthorization]");
                var settings = settings(args[1], args[7], args[5], args[6]);
                if (args.length == 9) settings = new AdmissionSettings(settings.bootstrap(), settings.journalDirectory(), settings.policyMaxAge(), Map.of(),
                        settings.journalSegmentBytes(), settings.journalMaxBytes(), Path.of(args[8]));
                System.out.println(checkpoints.json.writeValueAsString(checkpoints.checkpoint(settings, Path.of(args[2]), args[3], args[4])));
            }
            case "restore" -> {
                if (args.length != 8) throw new IllegalArgumentException("restore archive destination digest revision actor ticket bootstrap");
                var manifest = checkpoints.verify(Path.of(args[1]), args[3], Long.parseLong(args[4]));
                var settings = new AdmissionSettings(Path.of(args[7]), Path.of(args[2]), java.time.Duration.ofSeconds(30), Map.of(), manifest.segmentBytes(), manifest.maxBytes());
                checkpoints.restore(Path.of(args[1]), settings, args[3], Long.parseLong(args[4]), args[5], args[6]);
                System.out.println("Restore complete; current authority reconciliation required before startup");
            }
            case "verify" -> {
                if (args.length != 4) throw new IllegalArgumentException("verify archive digest revision");
                checkpoints.verify(Path.of(args[1]), args[2], Long.parseLong(args[3])); System.out.println("Archive verified");
            }
            case "status" -> {
                if (args.length != 7) throw new IllegalArgumentException("status source bootstrap segmentBytes maxBytes policyRate auditRate");
                requireSource(Path.of(args[1]));
                try (var journal = new AdmissionJournal(settings(args[1], args[2], args[3], args[4]), true)) {
                    System.out.println(checkpoints.json.writeValueAsString(journal.capacityStatus(Long.parseLong(args[5]), Long.parseLong(args[6]))));
                }
            }
            default -> throw new IllegalArgumentException("Unknown storage operation");
        }
    }
    private static AdmissionSettings settings(String directory, String bootstrap, String segment, String max) {
        return new AdmissionSettings(Path.of(bootstrap), Path.of(directory), java.time.Duration.ofSeconds(30), Map.of(), Integer.parseInt(segment), Long.parseLong(max));
    }
}
