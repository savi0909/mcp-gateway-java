package dev.mcp.gateway.admission;

import java.io.IOException;
import java.time.Duration;
import java.util.function.LongSupplier;

/** Cached reads never renew verification age. Invalid/replayed authority invalidates the cache. */
public final class VerifiedPolicyCache {
    @FunctionalInterface public interface Source { AdmissionPolicy read() throws IOException; }
    private final Source source;
    private final LongSupplier ticker;
    private final long maxAge;
    private AdmissionPolicy cached;
    private AdmissionPolicy highWater;
    private long verifiedAt;
    public VerifiedPolicyCache(Source source, Duration maxAge, LongSupplier ticker) {
        AdmissionPolicy.require(maxAge != null && !maxAge.isZero() && !maxAge.isNegative()
                && maxAge.compareTo(Duration.ofSeconds(30)) <= 0);
        this.source = source; this.ticker = ticker; this.maxAge = maxAge.toNanos();
    }
    public synchronized AdmissionPolicy current() {
        try {
            return refresh();
        }
        catch (IOException unavailable) {
            long elapsed = ticker.getAsLong() - verifiedAt;
            if (cached != null && elapsed >= 0 && elapsed < maxAge) return cached;
            throw AdmissionFailure.unavailable();
        }
        catch (RuntimeException invalid) { cached = null; throw AdmissionFailure.unavailable(); }
    }
    public synchronized AdmissionPolicy authoritative() {
        try { return refresh(); }
        catch (IOException failure) { throw AdmissionFailure.unavailable(); }
        catch (RuntimeException invalid) { cached = null; throw AdmissionFailure.unavailable(); }
    }
    private AdmissionPolicy refresh() throws IOException {
        AdmissionPolicy fresh = source.read();
        if (fresh == null || highWater != null && (fresh.revision() < highWater.revision()
                || fresh.revision() == highWater.revision() && !fresh.equals(highWater))) {
            cached = null; throw AdmissionFailure.unavailable();
        }
        cached = fresh; highWater = fresh; verifiedAt = ticker.getAsLong(); return fresh;
    }
    public synchronized void invalidate() { cached = null; }
}
