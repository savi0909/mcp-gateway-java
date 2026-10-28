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
    @Test void versionedTimingTightensFallbackAndSurvivesPolicyCopiesAndSerialization() throws Exception {
        var p = AdmissionFixtures.policy("https://authority.example");
        var tightened = new AdmissionPolicy(2, p.organizations(), p.tenants(), p.humans(), p.applications(), p.servers(), p.capabilities(), p.catalogs(), p.delegations(), p.grants(), p.credentials(), p.resources(), new AdmissionPolicy.Timing(10, 120));
        var directory = AdmissionFixtures.directory(); var bootstrap = directory.resolve("bootstrap.json");
        java.nio.file.Files.writeString(bootstrap, AdmissionFixtures.JSON.writeValueAsString(p));
        var settings = new AdmissionSettings(bootstrap, directory.resolve("journals"), Duration.ofSeconds(30), java.util.Map.of());
        try (var journal = new AdmissionJournal(settings)) { journal.writePolicy(tightened); }
        try (var journal = new AdmissionJournal(settings)) { assertThat(journal.readPolicy().timing()).isEqualTo(tightened.timing()); }
        var source = new AtomicReference<>(tightened); var clock = new AtomicLong(1);
        var cache = new VerifiedPolicyCache(() -> { if (source.get() == null) throw new IOException(); return source.get(); }, Duration.ofSeconds(30), clock::get);
        cache.current(); source.set(null); clock.addAndGet(Duration.ofSeconds(10).toNanos() - 1);
        assertThat(cache.current()).isEqualTo(tightened); clock.incrementAndGet();
        assertThatThrownBy(cache::current).isInstanceOf(AdmissionFailure.class);
        var copy = tightened.next(p.humans(), p.applications(), p.servers(), p.capabilities(), p.catalogs(), p.delegations(), p.grants()).withCredentials(p.credentials()).withResources(p.resources());
        assertThat(copy.timing()).isEqualTo(tightened.timing());
        assertThat(AdmissionFixtures.JSON.readValue(AdmissionFixtures.JSON.writeValueAsString(copy), AdmissionPolicy.class)).isEqualTo(copy);
        var legacy = AdmissionFixtures.JSON.readTree(AdmissionFixtures.JSON.writeValueAsString(p));
        ((tools.jackson.databind.node.ObjectNode) legacy).remove("timing");
        assertThat(AdmissionFixtures.JSON.treeToValue(legacy, AdmissionPolicy.class).timing()).isEqualTo(AdmissionPolicy.Timing.defaults());
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
