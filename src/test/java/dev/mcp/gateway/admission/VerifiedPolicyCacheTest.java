package dev.mcp.gateway.admission;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class VerifiedPolicyCacheTest {
    @Test void retainedPolicyExpiresExactlyAtBoundAndFailuresNeverRenewIt() throws Exception {
        var policy = AdmissionFixtures.policy("https://authority.example"); var clock = new AtomicLong(1);
        var source = new AtomicReference<AdmissionPolicy>(policy);
        var cache = new VerifiedPolicyCache(() -> { if (source.get() == null) throw new IOException(); return source.get(); }, Duration.ofSeconds(30), clock::get);
        assertThat(cache.current()).isEqualTo(policy); source.set(null);
        clock.addAndGet(Duration.ofSeconds(29).toNanos()); assertThat(cache.current()).isEqualTo(policy);
        clock.addAndGet(Duration.ofSeconds(1).toNanos() - 1); assertThat(cache.current()).isEqualTo(policy);
        clock.incrementAndGet(); assertThatThrownBy(cache::current).isInstanceOf(AdmissionFailure.class);
        source.set(policy); assertThat(cache.current()).isEqualTo(policy);
    }
    @Test void rollbackConflictAndInvalidationCannotRestoreOlderAuthority() throws Exception {
        var first = AdmissionFixtures.policy("https://authority.example");
        var second = first.next(first.humans(), first.applications(), first.servers(), first.capabilities(), first.catalogs(), first.delegations(), first.grants());
        var source = new AtomicReference<>(second); var cache = new VerifiedPolicyCache(source::get, Duration.ofSeconds(30), () -> 1);
        cache.current(); source.set(first); assertThatThrownBy(cache::current).isInstanceOf(AdmissionFailure.class);
        cache.invalidate(); assertThatThrownBy(cache::current).isInstanceOf(AdmissionFailure.class);
        source.set(second); assertThat(cache.current()).isEqualTo(second);
        source.set(new AdmissionPolicy(2, java.util.Map.of("organization-north", "SUSPENDED", "organization-south", "ACTIVE"), second.tenants(), second.humans(), second.applications(), second.servers(), second.capabilities(), second.catalogs(), second.delegations(), second.grants(), second.credentials(), second.resources()));
        assertThatThrownBy(cache::current).isInstanceOf(AdmissionFailure.class);
    }
    @Test void invalidFreshPolicyNeverUsesFallbackAndClockReversalFailsClosed() throws Exception {
        var p = AdmissionFixtures.policy("https://authority.example"); var clock = new AtomicLong(100); var failing = new AtomicLong();
        var cache = new VerifiedPolicyCache(() -> { if (failing.get() == 1) throw new IllegalArgumentException(); if (failing.get() == 2) throw new IOException(); return p; }, Duration.ofSeconds(30), clock::get);
        cache.current(); failing.set(1); assertThatThrownBy(cache::current).isInstanceOf(AdmissionFailure.class);
        failing.set(2); assertThatThrownBy(cache::current).isInstanceOf(AdmissionFailure.class);
        failing.set(0); cache.current(); failing.set(2); clock.set(99); assertThatThrownBy(cache::current).isInstanceOf(AdmissionFailure.class);
        assertThatThrownBy(() -> new VerifiedPolicyCache(() -> p, Duration.ofSeconds(31), clock::get)).isInstanceOf(IllegalArgumentException.class);
    }
    @Test void provisioningIsDurableRestartDoesNotReseedAndSecondWriterIsRejected() throws Exception {
        var dir = AdmissionFixtures.directory(); var seed = dir.resolve("bootstrap.json"); var p = AdmissionFixtures.policy("https://authority.example");
        java.nio.file.Files.writeString(seed, AdmissionFixtures.JSON.writeValueAsString(p));
        var settings = new AdmissionSettings(seed, dir.resolve("journals"), Duration.ofSeconds(30), java.util.Map.of());
        try (var journal = new AdmissionJournal(settings)) {
            assertThat(journal.readAudits()).hasSize(1);
            assertThatThrownBy(() -> new AdmissionJournal(settings)).isInstanceOf(IOException.class);
            journal.writePolicy(p.next(p.humans(), p.applications(), p.servers(), p.capabilities(), p.catalogs(), p.delegations(), p.grants()));
        }
        try (var journal = new AdmissionJournal(settings)) { assertThat(journal.readPolicy().revision()).isEqualTo(2); assertThat(journal.readAudits()).hasSize(1); }
        java.nio.file.Files.writeString(dir.resolve("journals/policy.jsonl"), "truncated", java.nio.file.StandardOpenOption.APPEND);
        assertThatThrownBy(() -> new AdmissionJournal(settings)).isInstanceOf(IOException.class);
    }
}
