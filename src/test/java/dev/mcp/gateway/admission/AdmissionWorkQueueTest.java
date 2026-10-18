package dev.mcp.gateway.admission;

import java.time.Duration;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import static org.assertj.core.api.Assertions.*;

class AdmissionWorkQueueTest {
    @Test void overloadRejectsWithoutRunningAndQueuedWorkSeesCurrentState() throws Exception {
        try (var queue = new AdmissionWorkQueue(1, Duration.ofSeconds(5))) {
            var started = new CountDownLatch(1); var release = new CountDownLatch(1);
            var allowed = new AtomicBoolean(true); var overflowRan = new AtomicBoolean();
            var first = queue.submit(() -> {
                started.countDown();
                try { if (!release.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("Test release timed out"); }
                catch (InterruptedException error) { throw new IllegalStateException(error); }
                return true;
            }).toFuture();
            assertThat(started.await(2, TimeUnit.SECONDS)).isTrue();
            try {
                var queued = queue.submit(allowed::get).toFuture();
                assertThatThrownBy(() -> queue.submit(() -> overflowRan.getAndSet(true)).block(Duration.ofSeconds(1))).isInstanceOf(AdmissionFailure.class);
                allowed.set(false); release.countDown();
                assertThat(first.get(2, TimeUnit.SECONDS)).isTrue();
                assertThat(queued.get(2, TimeUnit.SECONDS)).isFalse(); assertThat(overflowRan).isFalse();
            }
            finally { release.countDown(); }
        }
    }
    @Test void expiredQueueBudgetAndClosedQueueNeverExecuteWork() {
        var ran = new AtomicBoolean();
        try (var queue = new AdmissionWorkQueue(1, Duration.ofNanos(1))) {
            assertThatThrownBy(() -> queue.submit(() -> ran.getAndSet(true)).block(Duration.ofSeconds(1))).isInstanceOf(AdmissionFailure.class);
        }
        var queue = new AdmissionWorkQueue(); queue.close();
        assertThatThrownBy(() -> queue.submit(() -> ran.getAndSet(true)).block(Duration.ofSeconds(1))).isInstanceOf(AdmissionFailure.class);
        assertThat(ran).isFalse();
    }
    @Test void queueTimeoutRespondsWhileWorkerIsBusyAndDoesNotExecuteLater() throws Exception {
        try (var queue = new AdmissionWorkQueue(2, Duration.ofMillis(200))) {
            var started = new CountDownLatch(1); var release = new CountDownLatch(1); var ran = new AtomicBoolean();
            var first = queue.submit(() -> {
                started.countDown();
                try { if (!release.await(2, TimeUnit.SECONDS)) throw new IllegalStateException("Test release timed out"); }
                catch (InterruptedException error) { throw new IllegalStateException(error); }
                return true;
            }).toFuture();
            try {
                assertThat(started.await(1, TimeUnit.SECONDS)).isTrue();
                var timedOut = queue.submit(() -> ran.getAndSet(true)).toFuture();
                assertThatThrownBy(() -> timedOut.get(1, TimeUnit.SECONDS)).hasCauseInstanceOf(AdmissionFailure.class);
                assertThat(first.isDone()).isFalse(); release.countDown(); assertThat(first.get(1, TimeUnit.SECONDS)).isTrue();
                assertThat(queue.submit(() -> true).block(Duration.ofSeconds(1))).isTrue(); assertThat(ran).isFalse();
            }
            finally { release.countDown(); }
        }
    }
}
