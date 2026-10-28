package dev.mcp.gateway.admission;

import java.time.Duration;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;
import reactor.core.scheduler.Schedulers;

/** Dedicated bounded disk work. Queue residence never supplies authorization. */
final class AdmissionWorkQueue implements AutoCloseable {
    private final Scheduler scheduler;
    private final AtomicInteger waiting = new AtomicInteger();
    int queuedTasks() { return waiting.get(); }
    private final long maxWaitNanos;
    AdmissionWorkQueue() { this(256, Duration.ofSeconds(5)); }
    AdmissionWorkQueue(int capacity, Duration maxWait) {
        scheduler = Schedulers.newBoundedElastic(1, capacity, "gateway-admission", 60, true);
        maxWaitNanos = maxWait.toNanos();
    }
    <T> Mono<T> submit(Supplier<T> work) {
        return Mono.create(sink -> {
            long queued = System.nanoTime();
            waiting.incrementAndGet();
            var state = new AtomicInteger(); // 0 queued, 1 executing, 2 finished/cancelled
            var task = new AtomicReference<Disposable>();
            var timeout = Schedulers.parallel().schedule(() -> {
                if (state.compareAndSet(0, 2)) {
                    waiting.decrementAndGet();
                    var scheduled = task.get(); if (scheduled != null) scheduled.dispose();
                    sink.error(AdmissionFailure.unavailable());
                }
            }, maxWaitNanos, TimeUnit.NANOSECONDS);
            sink.onCancel(() -> {
                timeout.dispose();
                if (state.compareAndSet(0, 2)) {
                    waiting.decrementAndGet();
                    var scheduled = task.get(); if (scheduled != null) scheduled.dispose();
                }
                // Never interrupt a forced write already in progress. Its outcome remains durable/unknown.
            });
            try {
                var scheduled = scheduler.schedule(() -> {
                    if (!state.compareAndSet(0, 1)) return;
                    waiting.decrementAndGet();
                    timeout.dispose();
                    try {
                        if (System.nanoTime() - queued >= maxWaitNanos) throw AdmissionFailure.unavailable();
                        sink.success(work.get());
                    }
                    catch (Throwable error) { sink.error(error); }
                    finally { state.set(2); }
                });
                task.set(scheduled);
                if (state.get() == 2) scheduled.dispose();
            }
            catch (RejectedExecutionException rejected) {
                if (state.compareAndSet(0, 2)) waiting.decrementAndGet(); timeout.dispose(); sink.error(AdmissionFailure.unavailable());
            }
        });
    }
    @Override public void close() { scheduler.dispose(); }
}
