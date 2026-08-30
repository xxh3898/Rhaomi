package kr.co.rhaomi.publisher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.BooleanSupplier;
import kr.co.rhaomi.backend.publication.PublicationEventKind;
import kr.co.rhaomi.backend.publication.PublicationEventStatus;
import kr.co.rhaomi.backend.publication.PublicationSourceType;
import kr.co.rhaomi.backend.publication.PublicationState;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class PublisherExecutorLockLifetimeTest {

    private static final Instant NOW = Instant.parse("2035-01-01T00:00:00Z");
    private static final Duration RENEWAL_INTERVAL = Duration.ofSeconds(10);

    @TempDir
    Path tempDirectory;

    @Test
    void should_holdFilesystemLockUntilPhysicalExecutorTerminates_when_leaseIsLost()
            throws Exception {
        try (var fixture = fixture(PublicationBuildResult.SUCCESS, "lease-loss")) {
            var outcome = fixture.startRunNext();
            fixture.executor.awaitStarted();

            fixture.state.loseLease();
            fixture.clock.advance(RENEWAL_INTERVAL);
            fixture.executor.awaitInterrupted();

            var contender = new FileSystemPublicationExecutionLock(fixture.lockFile);
            try {
                assertLockUnavailableFor(contender, Duration.ofMillis(200));
                assertFalse(outcome.isDone());
                assertEquals(0, fixture.state.successCompletions.get());
                assertEquals(0, fixture.state.noopCompletions.get());
            } finally {
                fixture.executor.release();
            }

            fixture.executor.awaitExited();
            assertEquals(PublisherControlLoop.CycleOutcome.LEASE_LOST, outcome.get(5, TimeUnit.SECONDS));
            try (var ignored = contender.tryAcquire().orElseThrow()) {
                assertEquals(0, fixture.state.successCompletions.get());
                assertEquals(0, fixture.state.noopCompletions.get());
            }
        }
    }

    @Test
    void should_holdFilesystemLockPastShutdownTimeoutUntilPhysicalExecutorTerminates_when_stopping()
            throws Exception {
        try (var fixture = fixture(PublicationBuildResult.NO_PUBLIC_CHANGE, "shutdown")) {
            var lifecycle = new PublisherLifecycle(
                    fixture.loop,
                    fixture.stopSignal,
                    fixture.settings,
                    true);
            lifecycle.start();
            fixture.executor.awaitStarted();

            lifecycle.stop();
            fixture.executor.awaitInterrupted();

            var contender = new FileSystemPublicationExecutionLock(fixture.lockFile);
            try {
                assertLockUnavailableFor(contender, Duration.ofMillis(200));
                assertTrue(lifecycle.isRunning());
                assertEquals(0, fixture.state.successCompletions.get());
                assertEquals(0, fixture.state.noopCompletions.get());
            } finally {
                fixture.executor.release();
            }

            fixture.executor.awaitExited();
            awaitCondition(() -> !lifecycle.isRunning());
            try (var ignored = contender.tryAcquire().orElseThrow()) {
                assertEquals(0, fixture.state.successCompletions.get());
                assertEquals(0, fixture.state.noopCompletions.get());
            }
        }
    }

    private Fixture fixture(PublicationBuildResult lateResult, String suffix) {
        var clock = new MutableClock(NOW);
        var state = new TrackingState();
        var executor = new InterruptIgnoringExecutor(lateResult);
        var buildThreads = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("publisher-lock-test-build-", 0).factory());
        var loopThreads = Executors.newSingleThreadExecutor(
                Thread.ofPlatform().name("publisher-lock-test-loop-", 0).factory());
        var lockFile = tempDirectory.resolve("publisher-" + suffix + ".lock").toAbsolutePath();
        var stopSignal = new PublisherStopSignal();
        var settings = new PublisherSettings(
                "publisher-lock-lifetime-" + suffix,
                Duration.ofMillis(10),
                Duration.ofSeconds(30),
                RENEWAL_INTERVAL,
                Duration.ofMillis(100),
                lockFile);
        var loop = new PublisherControlLoop(
                state,
                new AsyncPublicationBuildTaskFactory(executor, buildThreads),
                new FileSystemPublicationExecutionLock(lockFile),
                clock,
                (duration, signal) -> {
                    clock.advance(duration);
                    return !signal.isRequested();
                },
                settings,
                stopSignal);
        return new Fixture(
                clock,
                state,
                executor,
                lockFile,
                stopSignal,
                settings,
                loop,
                buildThreads,
                loopThreads);
    }

    private static void awaitCondition(BooleanSupplier condition) throws Exception {
        var deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(10));
        }
        assertTrue(condition.getAsBoolean());
    }

    private static void assertLockUnavailableFor(
            FileSystemPublicationExecutionLock contender, Duration duration) {
        var deadline = System.nanoTime() + duration.toNanos();
        do {
            var acquired = contender.tryAcquire();
            if (acquired.isPresent()) {
                acquired.orElseThrow().close();
                fail("Filesystem lock was released before physical executor termination");
            }
            LockSupport.parkNanos(TimeUnit.MILLISECONDS.toNanos(5));
        } while (System.nanoTime() < deadline);
    }

    private static PublicationEventStatus processingClaim() {
        return new PublicationEventStatus(
                UUID.randomUUID(),
                PublicationEventKind.CONTENT_CHANGED,
                PublicationSourceType.BREED,
                UUID.randomUUID(),
                1,
                NOW,
                null,
                NOW,
                PublicationState.PROCESSING,
                1L,
                1,
                "publisher-lock-lifetime",
                NOW,
                NOW.plus(Duration.ofMinutes(10)),
                null,
                null,
                null,
                null);
    }

    private static final class MutableClock extends Clock {

        private final AtomicReference<Instant> instant;

        private MutableClock(Instant instant) {
            this.instant = new AtomicReference<>(instant);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return instant.get();
        }

        private void advance(Duration duration) {
            instant.updateAndGet(value -> value.plus(duration));
        }
    }

    private static final class TrackingState implements PublicationStateOperations {

        private final AtomicBoolean firstClaim = new AtomicBoolean(true);
        private final AtomicBoolean leaseLost = new AtomicBoolean();
        private final AtomicInteger successCompletions = new AtomicInteger();
        private final AtomicInteger noopCompletions = new AtomicInteger();

        private void loseLease() {
            leaseLost.set(true);
        }

        @Override
        public Optional<PublicationEventStatus> claimNext(
                String owner, Instant now, Duration leaseDuration) {
            return firstClaim.compareAndSet(true, false)
                    ? Optional.of(processingClaim())
                    : Optional.empty();
        }

        @Override
        public boolean renewLease(
                UUID eventId,
                long publishGeneration,
                String owner,
                Instant now,
                Duration leaseDuration) {
            return !leaseLost.get();
        }

        @Override
        public boolean completeSuccess(
                UUID eventId, long publishGeneration, String owner, Instant now) {
            successCompletions.incrementAndGet();
            return true;
        }

        @Override
        public boolean completeNoop(
                UUID eventId, long publishGeneration, String owner, Instant now) {
            noopCompletions.incrementAndGet();
            return true;
        }

        @Override
        public boolean recordTransientFailure(
                UUID eventId, long publishGeneration, String owner, Instant now) {
            return true;
        }

        @Override
        public boolean recordTerminalFailure(
                UUID eventId, long publishGeneration, String owner, Instant now) {
            return true;
        }

        @Override
        public boolean coalesceInto(
                UUID sourceEventId,
                long sourceGeneration,
                long targetGeneration,
                String owner,
                Instant now) {
            return true;
        }
    }

    private static final class InterruptIgnoringExecutor implements PublicationBuildExecutor {

        private final PublicationBuildResult lateResult;
        private final CountDownLatch started = new CountDownLatch(1);
        private final CountDownLatch interrupted = new CountDownLatch(1);
        private final CountDownLatch release = new CountDownLatch(1);
        private final CountDownLatch exited = new CountDownLatch(1);

        private InterruptIgnoringExecutor(PublicationBuildResult lateResult) {
            this.lateResult = lateResult;
        }

        @Override
        public PublicationBuildResult execute(long targetGeneration) {
            started.countDown();
            try {
                while (true) {
                    try {
                        release.await();
                        return lateResult;
                    } catch (InterruptedException exception) {
                        interrupted.countDown();
                    }
                }
            } finally {
                exited.countDown();
            }
        }

        private void awaitStarted() throws Exception {
            assertTrue(started.await(5, TimeUnit.SECONDS));
        }

        private void awaitInterrupted() throws Exception {
            assertTrue(interrupted.await(5, TimeUnit.SECONDS));
        }

        private void release() {
            release.countDown();
        }

        private void awaitExited() throws Exception {
            assertTrue(exited.await(5, TimeUnit.SECONDS));
        }
    }

    private static final class Fixture implements AutoCloseable {

        private final MutableClock clock;
        private final TrackingState state;
        private final InterruptIgnoringExecutor executor;
        private final Path lockFile;
        private final PublisherStopSignal stopSignal;
        private final PublisherSettings settings;
        private final PublisherControlLoop loop;
        private final ExecutorService buildThreads;
        private final ExecutorService loopThreads;

        private Fixture(
                MutableClock clock,
                TrackingState state,
                InterruptIgnoringExecutor executor,
                Path lockFile,
                PublisherStopSignal stopSignal,
                PublisherSettings settings,
                PublisherControlLoop loop,
                ExecutorService buildThreads,
                ExecutorService loopThreads) {
            this.clock = clock;
            this.state = state;
            this.executor = executor;
            this.lockFile = lockFile;
            this.stopSignal = stopSignal;
            this.settings = settings;
            this.loop = loop;
            this.buildThreads = buildThreads;
            this.loopThreads = loopThreads;
        }

        private Future<PublisherControlLoop.CycleOutcome> startRunNext() {
            return loopThreads.submit(loop::runNext);
        }

        @Override
        public void close() throws Exception {
            executor.release();
            stopSignal.requestStop();
            loopThreads.shutdownNow();
            buildThreads.shutdownNow();
            assertTrue(loopThreads.awaitTermination(5, TimeUnit.SECONDS));
            assertTrue(buildThreads.awaitTermination(5, TimeUnit.SECONDS));
        }
    }
}
