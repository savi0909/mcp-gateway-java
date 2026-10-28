package dev.mcp.gateway.admission;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.security.MessageDigest;
import java.time.Instant;
import java.util.*;
import java.util.function.Consumer;
import tools.jackson.databind.json.JsonMapper;

/** Bounded retained segments; originals are never overwritten or deleted. Owned by AdmissionJournal. */
final class SegmentedJournal implements AutoCloseable {
    private record Segment(Path path, FileChannel channel, FileLock lock, Object key) { }
    private record Stamp(Object key, long size, java.nio.file.attribute.FileTime modified) { }
    private final Path directory;
    private final String kind;
    private final int segmentBytes;
    private final long maxBytes;
    private final JsonMapper json;
    private final List<Segment> segments = new ArrayList<>();
    private final Map<Path, Stamp> sealed = new HashMap<>();
    private boolean failed;

    SegmentedJournal(Path directory, String kind, int segmentBytes, long maxBytes, JsonMapper json) throws IOException {
        this.directory = directory; this.kind = kind; this.segmentBytes = segmentBytes; this.maxBytes = maxBytes; this.json = json;
        try {
            open(directory.resolve(kind + ".jsonl")); // Legacy file and writer lock remain authoritative.
            var paths = inventory();
            for (int index = 0; index < paths.size(); index++) {
                if (!paths.get(index).equals(path(index + 1))) throw new IOException("Missing journal segment");
                open(paths.get(index));
            }
            if (size() > maxBytes) throw new IOException("Journal capacity exceeded");
            for (int index = 0; index < segments.size(); index++) {
                byte[] bytes = bytes(segments.get(index));
                if (index > 0) {
                    if (bytes.length == 0 && index == segments.size() - 1) {
                        byte[] marker = marker(index);
                        if (size() + marker.length + 1 > maxBytes) throw new IOException("Journal capacity exceeded");
                        appendRaw(segments.get(index), marker);
                        bytes = bytes(segments.get(index));
                    }
                    verifyMarker(index, bytes);
                }
                if (index < segments.size() - 1) sealed.put(segments.get(index).path(), stamp(segments.get(index).path()));
            }
        }
        catch (Exception error) { close(); throw new IOException("Journal segments unavailable"); }
    }
    private Path path(int index) { return directory.resolve(kind + "." + String.format(Locale.ROOT, "%06d", index) + ".jsonl"); }
    private List<Path> inventory() throws IOException {
        try (var files = Files.newDirectoryStream(directory, kind + ".*.jsonl")) {
            var paths = new ArrayList<Path>();
            for (Path path : files) {
                if (!path.getFileName().toString().matches(kind + "\\.[0-9]{6}\\.jsonl") || paths.size() >= 1023)
                    throw new IOException("Invalid journal inventory");
                paths.add(path);
            }
            paths.sort(Comparator.comparing(p -> p.getFileName().toString())); return paths;
        }
    }
    private void open(Path path) throws IOException {
        if (Files.isSymbolicLink(path)) throw new IOException("Invalid journal path");
        var channel = FileChannel.open(path, StandardOpenOption.CREATE, StandardOpenOption.READ, StandardOpenOption.WRITE);
        try {
            var lock = channel.tryLock();
            if (lock == null) throw new IOException("Journal locked");
            segments.add(new Segment(path, channel, lock, stamp(path).key()));
        }
        catch (Exception error) { channel.close(); throw new IOException("Journal locked"); }
    }
    private Stamp stamp(Path path) throws IOException {
        var attrs = Files.readAttributes(path, BasicFileAttributes.class, LinkOption.NOFOLLOW_LINKS);
        if (!attrs.isRegularFile()) throw new IOException("Invalid journal path");
        return new Stamp(attrs.fileKey(), attrs.size(), attrs.lastModifiedTime());
    }
    void verify() throws IOException {
        if (failed || segments.isEmpty()) throw new IOException("Journal unavailable");
        var paths = inventory();
        if (paths.size() != segments.size() - 1) throw new IllegalArgumentException("Journal inventory changed");
        for (int index = 0; index < segments.size(); index++) {
            var segment = segments.get(index); var current = stamp(segment.path());
            if (!segment.channel().isOpen() || !Objects.equals(current.key(), segment.key())
                    || sealed.containsKey(segment.path()) && !sealed.get(segment.path()).equals(current)
                    || index > 0 && !paths.get(index - 1).equals(segment.path()))
                throw new IllegalArgumentException("Journal changed");
        }
    }
    long size() throws IOException {
        long size = 0; for (var segment : segments) size += segment.channel().size(); return size;
    }
    int count() { return segments.size(); }
    void snapshot(CheckpointFileConsumer consumer) throws IOException {
        verify();
        for (var segment : segments) consumer.accept(segment.path().getFileName().toString(), bytes(segment));
    }
    @FunctionalInterface interface CheckpointFileConsumer { void accept(String name, byte[] bytes) throws IOException; }
    private byte[] bytes(Segment segment) throws IOException {
        long size = segment.channel().size();
        if (size > segmentBytes) throw new IllegalArgumentException("Invalid journal size");
        var buffer = ByteBuffer.allocate((int) size); segment.channel().position(0);
        while (buffer.hasRemaining()) if (segment.channel().read(buffer) < 0) throw new IOException("Incomplete journal");
        byte[] bytes = buffer.array();
        if (bytes.length > 0 && bytes[bytes.length - 1] != '\n') throw new IllegalArgumentException("Incomplete journal");
        return bytes;
    }
    static String digest(byte[] bytes) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes)); }
        catch (java.security.NoSuchAlgorithmException impossible) { throw new IllegalStateException(impossible); }
    }
    private byte[] marker(int index) throws IOException {
        return json.writeValueAsBytes(Map.of("journalSegment", index, "kind", kind,
                "previousSha256", digest(bytes(segments.get(index - 1))), "id", UUID.randomUUID().toString(),
                "decision", "JOURNAL_SEGMENT_OPENED", "at", Instant.now().toString()));
    }
    private void verifyMarker(int index, byte[] bytes) throws IOException {
        try {
            String first = new String(bytes, java.nio.charset.StandardCharsets.UTF_8).lines().findFirst().orElseThrow();
            var marker = json.readTree(first);
            if (marker.path("journalSegment").asInt() != index || !kind.equals(marker.path("kind").asText())
                    || !digest(bytes(segments.get(index - 1))).equals(marker.path("previousSha256").asText()))
                throw new IllegalArgumentException("Invalid segment link");
        }
        catch (RuntimeException error) { throw new IllegalArgumentException("Invalid segment link"); }
    }
    void visit(Consumer<String> consumer) throws IOException {
        verify();
        for (int index = 0; index < segments.size(); index++) {
            var lines = java.nio.charset.StandardCharsets.UTF_8.newDecoder().decode(ByteBuffer.wrap(bytes(segments.get(index)))).toString().lines();
            if (index > 0 && kind.equals("policy")) lines = lines.skip(1);
            lines.forEach(consumer);
        }
    }
    byte[] currentBytes() throws IOException {
        verify(); byte[] bytes = bytes(segments.getLast());
        if (segments.size() > 1) verifyMarker(segments.size() - 1, bytes);
        return bytes;
    }
    void append(byte[] record) throws IOException {
        verify();
        if (record.length + 1 > segmentBytes) throw new IOException("Journal record too large");
        var current = segments.getLast();
        if (current.channel().size() + record.length + 1 > segmentBytes) {
            if (segments.size() >= 1024) throw new IOException("Journal segment capacity exceeded");
            // Reserve the complete record and lifecycle marker before creating anything.
            byte[] marker = marker(segments.size());
            if (marker.length + record.length + 2 > segmentBytes || size() + marker.length + record.length + 2 > maxBytes)
                throw new IOException("Journal capacity exceeded");
            sealed.put(current.path(), stamp(current.path()));
            Path next = path(segments.size());
            Files.createFile(next); open(next); current = segments.getLast();
            appendRaw(current, marker);
        }
        if (size() + record.length + 1 > maxBytes) throw new IOException("Journal capacity exceeded");
        appendRaw(current, record);
    }
    private void appendRaw(Segment segment, byte[] bytes) throws IOException {
        try {
            segment.channel().position(segment.channel().size());
            var buffer = ByteBuffer.allocate(bytes.length + 1).put(bytes).put((byte) '\n'); buffer.flip();
            while (buffer.hasRemaining()) segment.channel().write(buffer);
            segment.channel().force(true);
        }
        catch (IOException failure) { failed = true; throw failure; }
    }
    @Override public void close() throws IOException {
        failed = true; IOException failure = null;
        for (var segment : segments) try { segment.channel().close(); } catch (IOException error) { failure = error; }
        if (failure != null) throw failure;
    }
}
