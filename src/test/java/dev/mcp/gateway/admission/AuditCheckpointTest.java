package dev.mcp.gateway.admission;

import java.io.IOException;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AuditCheckpointTest {
    private AdmissionSettings settings() throws Exception {
        var root = AdmissionFixtures.directory();
        var bootstrap = root.resolve("bootstrap.json");
        Files.writeString(bootstrap, AdmissionFixtures.JSON.writeValueAsString(AdmissionFixtures.policy("https://issuer.example")));
        return new AdmissionSettings(bootstrap, root.resolve("journals"), Duration.ofSeconds(30), Map.of(), 65536, 65536L * 12);
    }
    private AdmissionSettings at(AdmissionSettings s, Path directory) {
        return new AdmissionSettings(s.bootstrap(), directory, s.policyMaxAge(), Map.of(), s.journalSegmentBytes(), s.journalMaxBytes(), directory.resolveSibling("authority.json"));
    }
    private void authorize(AdmissionSettings s, String digest, long revision) throws Exception {
        Files.writeString(s.recoveryAuthorization(), AdmissionFixtures.JSON.writeValueAsString(Map.of("manifestSha256", digest,
                "minimumPolicyRevision", revision, "verifiedAt", java.time.Instant.now().minusSeconds(1).toString(),
                "expiresAt", java.time.Instant.now().plusSeconds(25).toString(), "actor", "recovery-operator", "reason", "ticket-restore")));
    }
    @Test void checkpointRestoresAllSegmentsExpiredEvidenceAndUnresolvedAdmissions() throws Exception {
        var s = settings(); var archive = s.bootstrap().resolveSibling("archive");
        try (var journal = new AdmissionJournal(s)) {
            journal.appendAudit(Map.of("id", "unfinished", "decision", "ADMITTED", "at", "2020-01-01T00:00:00Z"));
            for (int n = 0; n < 35; n++) journal.appendAudit(Map.of("id", "event-" + n, "decision", "TEST", "padding", "x".repeat(4096)));
        }
        var receipt = new AuditCheckpoint().checkpoint(s, archive, "operator-1", "ticket-42");
        var restored = at(s, archive.resolveSibling("restored"));
        new AuditCheckpoint().restore(archive, restored, receipt.manifestSha256(), 1, "operator-2", "ticket-43");
        authorize(restored, receipt.manifestSha256(), 1);
        for (int n = 0; n < 2; n++) try (var journal = new AdmissionJournal(restored)) {
            assertThat(journal.readPolicy().revision()).isEqualTo(1);
            assertThat(journal.readAudits()).anyMatch(r -> "unfinished".equals(r.get("id")) && "ADMITTED".equals(r.get("decision")));
            assertThat(journal.readAudits().stream().filter(r -> "TEST".equals(r.get("decision")))).hasSize(35);
            assertThat(journal.readAudits()).anyMatch(r -> "RESTORE_COMPLETED".equals(r.get("decision")) && "operator-2".equals(r.get("actor")));
        }
    }
    @Test void interruptedOrFullCheckpointNeverPublishesAndSourceCanRestart() throws Exception {
        for (int failAt = 1; failAt <= 4; failAt++) {
            var s = settings(); try (var journal = new AdmissionJournal(s)) { }
            var archive = s.bootstrap().resolveSibling("archive"); int failureIndex = failAt; int[] writes = {0};
            var checkpoints = new AuditCheckpoint((path, bytes) -> {
                if (++writes[0] == failureIndex) {
                    AuditCheckpoint.writeForced(path, Arrays.copyOf(bytes, bytes.length / 2));
                    throw new IOException("simulated ENOSPC/interruption");
                }
                AuditCheckpoint.writeForced(path, bytes);
            });
            assertThatThrownBy(() -> checkpoints.checkpoint(s, archive, "operator", "ticket")).isInstanceOf(IOException.class);
            assertThatThrownBy(() -> new AuditCheckpoint().restore(archive, at(s, archive.resolveSibling("restored")), "0".repeat(64), 1, "operator", "ticket"))
                    .isInstanceOf(IOException.class);
            try (var journal = new AdmissionJournal(s)) {
                assertThat(journal.readPolicy().revision()).isEqualTo(1);
                assertThat(journal.readAudits()).anyMatch(r -> "CHECKPOINT_FAILED".equals(r.get("decision")));
            }
        }
    }
    @Test void unavailableArchiveAndActiveWriterFailWithoutReplacingEvidence() throws Exception {
        var s = settings(); var blocked = s.bootstrap().resolveSibling("blocked"); Files.writeString(blocked, "occupied");
        try (var journal = new AdmissionJournal(s)) {
            assertThatThrownBy(() -> new AuditCheckpoint().checkpoint(s, blocked.resolve("archive"), "operator", "ticket")).isInstanceOf(IOException.class);
        }
        assertThatThrownBy(() -> new AuditCheckpoint().checkpoint(s, blocked.resolve("archive"), "operator", "ticket")).isInstanceOf(IOException.class);
        assertThat(Files.readString(blocked)).isEqualTo("occupied");
        try (var journal = new AdmissionJournal(s)) { assertThat(journal.readPolicy().revision()).isEqualTo(1); }
    }
    @Test void corruptArchivesWrongReceiptsAndOldRevisionsCannotRestore() throws Exception {
        var s = settings(); try (var journal = new AdmissionJournal(s)) { }
        var archive = s.bootstrap().resolveSibling("archive");
        var receipt = new AuditCheckpoint().checkpoint(s, archive, "operator", "ticket");
        assertThatThrownBy(() -> new AuditCheckpoint().restore(archive, at(s, archive.resolveSibling("wrong-receipt")), "0".repeat(64), 1, "operator", "ticket")).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> new AuditCheckpoint().restore(archive, at(s, archive.resolveSibling("old-policy")), receipt.manifestSha256(), 2, "operator", "ticket")).isInstanceOf(IOException.class);
        Files.writeString(archive.resolve("audit.jsonl"), "corrupt", StandardOpenOption.APPEND);
        assertThatThrownBy(() -> new AuditCheckpoint().restore(archive, at(s, archive.resolveSibling("corrupt")), receipt.manifestSha256(), 1, "operator", "ticket")).isInstanceOf(IOException.class);
    }
    @Test void interruptedRestoreCannotBecomeAProvisionedGateway() throws Exception {
        for (int failAt = 1; failAt <= 5; failAt++) {
        var s = settings(); try (var journal = new AdmissionJournal(s)) { }
        var archive = s.bootstrap().resolveSibling("archive");
        var receipt = new AuditCheckpoint().checkpoint(s, archive, "operator", "ticket");
        var destination = at(s, archive.resolveSibling("partial")); int[] writes = {0}; int failureIndex = failAt;
        var checkpoints = new AuditCheckpoint((path, bytes) -> {
            if (++writes[0] == failureIndex) {
                AuditCheckpoint.writeForced(path, Arrays.copyOf(bytes, bytes.length / 2));
                throw new IOException("simulated full restore volume");
            }
            AuditCheckpoint.writeForced(path, bytes);
        });
        assertThatThrownBy(() -> checkpoints.restore(archive, destination, receipt.manifestSha256(), 1, "operator", "ticket")).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> new AdmissionJournal(destination)).isInstanceOf(IOException.class);
        assertThatThrownBy(() -> new AuditCheckpoint().restore(archive, destination, receipt.manifestSha256(), 1, "operator", "ticket")).isInstanceOf(IOException.class);
        }
    }
    @Test void capacityProjectionWarnsBeforeExhaustionWithoutIncreasingBudget() throws Exception {
        var s = settings();
        try (var journal = new AdmissionJournal(s)) {
            while ((Long) journal.storageStatus().get("auditBytes") < s.journalMaxBytes() * .81)
                journal.appendAudit(Map.of("id", UUID.randomUUID().toString(), "decision", "TEST", "padding", "x".repeat(4096)));
            var status = journal.capacityStatus(100, 200);
            assertThat(status.get("auditAlert")).isEqualTo("WARNING");
            assertThat(status.get("auditRequired90DayBytes")).isEqualTo(1555200000L);
            assertThat((Long) status.get("auditSecondsRemaining")).isBetween(0L, 800L);
            assertThat(journal.capacityStatus(0, 0).get("auditSecondsRemaining")).isEqualTo(-1L);
        }
    }
    @Test void restoredBackupCannotStartWithoutFreshIndependentAuthorityOrUndoLaterRevocation() throws Exception {
        var s = settings(); try (var journal = new AdmissionJournal(s)) { }
        var archive = s.bootstrap().resolveSibling("archive"); var restored = at(s, archive.resolveSibling("restored"));
        var receipt = new AuditCheckpoint().checkpoint(s, archive, "operator", "ticket");
        new AuditCheckpoint().restore(archive, restored, receipt.manifestSha256(), 1, "operator", "ticket");
        assertThatThrownBy(() -> new AdmissionJournal(restored)).isInstanceOf(IOException.class);
        authorize(restored, receipt.manifestSha256(), 2); // Independent authority knows about a later revocation.
        assertThatThrownBy(() -> new AdmissionJournal(restored)).isInstanceOf(IOException.class);
        authorize(restored, receipt.manifestSha256(), 1);
        Files.writeString(restored.recoveryAuthorization(), Files.readString(restored.recoveryAuthorization())
                .replaceAll("\"expiresAt\":\"[^\"]+\"", "\"expiresAt\":\"2000-01-01T00:00:00Z\""));
        assertThatThrownBy(() -> new AdmissionJournal(restored)).isInstanceOf(IOException.class);
        authorize(restored, receipt.manifestSha256(), 1);
        try (var journal = new AdmissionJournal(restored)) { assertThat(journal.readPolicy().revision()).isEqualTo(1); }
    }
    @Test void completedRestoreCannotBootstrapOverMissingBaseHistory() throws Exception {
        var s = settings(); try (var journal = new AdmissionJournal(s)) { }
        var archive = s.bootstrap().resolveSibling("archive"); var restored = at(s, archive.resolveSibling("restored"));
        var receipt = new AuditCheckpoint().checkpoint(s, archive, "operator", "ticket");
        new AuditCheckpoint().restore(archive, restored, receipt.manifestSha256(), 1, "operator", "ticket");
        authorize(restored, receipt.manifestSha256(), 1);
        for (String kind : List.of("audit", "policy")) Files.move(restored.journalDirectory().resolve(kind + ".jsonl"),
                restored.journalDirectory().resolve(kind + ".saved"));
        assertThatThrownBy(() -> new AdmissionJournal(restored)).isInstanceOf(IOException.class);
        assertThat(restored.journalDirectory().resolve("policy.jsonl")).doesNotExist();
        assertThat(restored.journalDirectory().resolve("audit.jsonl")).doesNotExist();
    }
}
