package kr.co.rhaomi.publisher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import kr.co.rhaomi.backend.publication.PublicationEventStatus;
import org.junit.jupiter.api.Test;

class PublisherLifecycleTest {

    @Test
    void should_stopIdleWorkerWithoutStartingAnotherClaim_when_lifecycleStops()
            throws Exception {
        var claimed = new CountDownLatch(1);
        var claimCount = new AtomicInteger();
        var state = new EmptyStateOperations(claimed, claimCount);
        var stopSignal = new PublisherStopSignal();
        var settings = new PublisherSettings(
                "publisher-lifecycle",
                Duration.ofMinutes(1),
                Duration.ofMinutes(2),
                Duration.ofSeconds(30),
                Duration.ofSeconds(2),
                Path.of("/tmp/rhaomi-publisher-lifecycle.lock"));
        var loop = new PublisherControlLoop(
                state,
                generation -> {
                    throw new AssertionError("Idle publisher must not start executor");
                },
                () -> Optional.of(() -> {}),
                Clock.systemUTC(),
                (duration, signal) -> signal.await(duration),
                settings,
                stopSignal);
        var lifecycle = new PublisherLifecycle(loop, stopSignal, settings, true);

        lifecycle.start();
        assertTrue(claimed.await(5, TimeUnit.SECONDS));
        lifecycle.stop();

        assertFalse(lifecycle.isRunning());
        assertTrue(stopSignal.isRequested());
        assertEquals(1, claimCount.get());
    }

    private static final class EmptyStateOperations implements PublicationStateOperations {

        private final CountDownLatch claimed;
        private final AtomicInteger claimCount;

        private EmptyStateOperations(CountDownLatch claimed, AtomicInteger claimCount) {
            this.claimed = claimed;
            this.claimCount = claimCount;
        }

        @Override
        public Optional<PublicationEventStatus> claimNext(
                String owner, Instant now, Duration leaseDuration) {
            claimCount.incrementAndGet();
            claimed.countDown();
            return Optional.empty();
        }

        @Override
        public boolean renewLease(
                UUID eventId,
                long publishGeneration,
                String owner,
                Instant now,
                Duration leaseDuration) {
            return false;
        }

        @Override
        public boolean completeSuccess(
                UUID eventId, long publishGeneration, String owner, Instant now) {
            return false;
        }

        @Override
        public boolean completeNoop(
                UUID eventId, long publishGeneration, String owner, Instant now) {
            return false;
        }

        @Override
        public boolean recordTransientFailure(
                UUID eventId, long publishGeneration, String owner, Instant now) {
            return false;
        }

        @Override
        public boolean recordTerminalFailure(
                UUID eventId, long publishGeneration, String owner, Instant now) {
            return false;
        }

        @Override
        public boolean coalesceInto(
                UUID sourceEventId,
                long sourceGeneration,
                long targetGeneration,
                String owner,
                Instant now) {
            return false;
        }
    }
}
