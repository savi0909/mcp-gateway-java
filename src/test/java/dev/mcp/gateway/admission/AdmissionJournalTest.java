package dev.mcp.gateway.admission;

import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AdmissionJournalTest {
    private AdmissionSettings settings(int segments) throws Exception {
        var directory = AdmissionFixtures.directory();
        var policy = AdmissionFixtures.policy("https://issuer.example");
        var bootstrap = directory.resolve("bootstrap.json");
        String body = AdmissionFixtures.JSON.writeValueAsString(policy); Files.writeString(bootstrap, body);
        int segmentBytes = Math.max(65536, body.getBytes(java.nio.charset.StandardCharsets.UTF_8).length * 2 + 8192);
        return new AdmissionSettings(bootstrap, directory.resolve("journals"), Duration.ofSeconds(30), Map.of(), segmentBytes, (long) segmentBytes * segments);
    }
    private AdmissionPolicy next(AdmissionPolicy p) {
        return p.next(p.humans(), p.applications(), p.servers(), p.capabilities(), p.catalogs(), p.delegations(), p.grants());
    }
    @Test void rollsBothJournalsAndRestoresLatestPolicyAndEveryAuditRecord() throws Exception {
        var settings = settings(12); long revision;
        try (var journal = new AdmissionJournal(settings)) {
            for (int n = 0; n < 10; n++) journal.writePolicy(next(journal.readPolicy()));
            for (int n = 0; n < 100; n++) journal.appendAudit(Map.of("id", "event-" + n, "decision", "TEST", "padding", "x".repeat(4096)));
            revision = journal.readPolicy().revision();
            assertThat((Integer) journal.storageStatus().get("policySegments")).isGreaterThan(1);
            assertThat((Integer) journal.storageStatus().get("auditSegments")).isGreaterThan(1);
        }
        try (var restored = new AdmissionJournal(settings)) {
            assertThat(restored.readPolicy().revision()).isEqualTo(revision);
            assertThat(restored.readAudits().stream().filter(r -> "TEST".equals(r.get("decision")))).hasSize(100);
            assertThat(restored.readAudits()).anyMatch(r -> "JOURNAL_SEGMENT_OPENED".equals(r.get("decision")));
        }
    }
    @Test void capacityFailurePreservesPriorRecordsAndCannotSilentlyExpandTheBudget() throws Exception {
        var settings = settings(1); int accepted = 0;
        try (var journal = new AdmissionJournal(settings)) {
            for (int n = 0; n < 100; n++) {
                try { journal.appendAudit(Map.of("id", "event-" + n, "decision", "TEST", "padding", "x".repeat(8192))); accepted++; }
                catch (java.io.IOException expected) { break; }
            }
            assertThat(accepted).isBetween(1, 99);
            assertThat((Long) journal.storageStatus().get("auditBytes")).isLessThanOrEqualTo(settings.journalMaxBytes());
            assertThatThrownBy(() -> journal.appendAudit(Map.of("padding", "x".repeat(settings.journalSegmentBytes())))).isInstanceOf(java.io.IOException.class);
        }
        try (var restored = new AdmissionJournal(settings)) {
            assertThat(restored.readAudits().stream().filter(r -> "TEST".equals(r.get("decision")))).hasSize(accepted);
        }
    }
    @Test void exclusiveWriterAndIncompleteAppendFailClosed() throws Exception {
        var settings = settings(4);
        try (var journal = new AdmissionJournal(settings)) {
            assertThatThrownBy(() -> new AdmissionJournal(settings)).isInstanceOf(java.io.IOException.class);
            assertThat(journal.readPolicy().revision()).isEqualTo(1);
        }
        Files.writeString(settings.journalDirectory().resolve("audit.jsonl"), "{incomplete", StandardOpenOption.APPEND);
        assertThatThrownBy(() -> new AdmissionJournal(settings)).isInstanceOf(java.io.IOException.class);
    }
    @Test void emptyLastSegmentRecoversWithoutLosingOrReplayingPolicy() throws Exception {
        var settings = settings(4);
        try (var journal = new AdmissionJournal(settings)) { journal.writePolicy(next(journal.readPolicy())); }
        Files.createFile(settings.journalDirectory().resolve("policy.000001.jsonl"));
        try (var restored = new AdmissionJournal(settings)) {
            assertThat(restored.readPolicy().revision()).isEqualTo(2);
            restored.writePolicy(next(restored.readPolicy()));
        }
        try (var restored = new AdmissionJournal(settings)) { assertThat(restored.readPolicy().revision()).isEqualTo(3); }
    }
    @Test void sealedSegmentTamperingAndMissingSegmentPreventRestart() throws Exception {
        var settings = settings(6);
        try (var journal = new AdmissionJournal(settings)) {
            for (int n = 0; n < 5; n++) journal.writePolicy(next(journal.readPolicy()));
        }
        Path first = settings.journalDirectory().resolve("policy.jsonl");
        String original = Files.readString(first);
        Files.writeString(first, original.replace("Display 0", "Changed 0"));
        assertThatThrownBy(() -> new AdmissionJournal(settings)).isInstanceOf(java.io.IOException.class);
        Files.writeString(first, original);
        Path middle = settings.journalDirectory().resolve("policy.000001.jsonl");
        Files.move(middle, settings.journalDirectory().resolve("policy.saved"));
        assertThatThrownBy(() -> new AdmissionJournal(settings)).isInstanceOf(java.io.IOException.class);
    }
    @Test void nonMonotonicWritesCannotReplaceCurrentAuthority() throws Exception {
        var settings = settings(4);
        try (var journal = new AdmissionJournal(settings)) {
            var first = journal.readPolicy();
            journal.writePolicy(next(first));
            assertThatThrownBy(() -> journal.writePolicy(first)).isInstanceOf(IllegalArgumentException.class);
            assertThatThrownBy(() -> journal.writePolicy(next(first))).isInstanceOf(IllegalArgumentException.class);
            assertThat(journal.readPolicy().revision()).isEqualTo(2);
        }
    }
    @Test void missingAuditHistoryNeverBootstrapsOverExistingPolicy() throws Exception {
        var settings = settings(4);
        try (var journal = new AdmissionJournal(settings)) { journal.writePolicy(next(journal.readPolicy())); }
        Files.move(settings.journalDirectory().resolve("audit.jsonl"), settings.journalDirectory().resolve("audit.saved"));
        assertThatThrownBy(() -> new AdmissionJournal(settings)).isInstanceOf(java.io.IOException.class);
    }
}
