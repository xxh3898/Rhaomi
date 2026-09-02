package kr.co.rhaomi.publisher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import kr.co.rhaomi.backend.BackendApplication;
import kr.co.rhaomi.backend.publication.PublicationEventKind;
import kr.co.rhaomi.backend.publication.PublicationEventStatus;
import kr.co.rhaomi.backend.publication.PublicationSourceType;
import kr.co.rhaomi.backend.publication.PublicationState;
import kr.co.rhaomi.backend.publication.PublicationStateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(classes = BackendApplication.class)
@ActiveProfiles("test")
class PublisherControlLoopIntegrationTests {

    private static final Instant NOW = Instant.parse("2035-01-01T00:00:00.123456Z");
    private static final Duration LEASE = Duration.ofMinutes(5);

    @Autowired
    private PublicationStateService stateService;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @TempDir
    Path tempDirectory;

    @BeforeEach
    void clearStateBeforeTest() {
        clearState();
    }

    @AfterEach
    void clearStateAfterTest() {
        clearState();
    }

    @Test
    void should_allocateMonotonicGenerationsCoalesceLowerAndCompleteHighest_when_threeEventsBurst() {
        var firstId = insertImmediateEvent(1, NOW.minusSeconds(3));
        var secondId = insertImmediateEvent(2, NOW.minusSeconds(2));
        var thirdId = insertImmediateEvent(3, NOW.minusSeconds(1));
        var fixture = fixture("publisher-burst", new PublicationStateServiceAdapter(stateService));

        var result = fixture.loop.runNext();

        assertEquals(PublisherControlLoop.CycleOutcome.RESULT_RECORDED, result);
        assertEquals(PublicationState.COALESCED, status(firstId).state());
        assertEquals(2L, status(firstId).coalescedIntoGeneration());
        assertEquals(PublicationState.COALESCED, status(secondId).state());
        assertEquals(3L, status(secondId).coalescedIntoGeneration());
        assertEquals(PublicationState.SUCCEEDED, status(thirdId).state());
        assertEquals(3L, status(thirdId).publishGeneration());
        assertEquals(List.of(3L), fixture.taskFactory.generations);
        assertEquals(3L, currentGeneration());
    }

    @Test
    void should_finishStaleDueEventWithoutGenerationOrExecutor_when_sourceIsMissing() {
        var boundary = NOW.minusSeconds(1);
        var eventId = insertEvent(
                PublicationEventKind.NOTICE_PUBLISHED_AT_DUE,
                PublicationSourceType.NOTICE,
                UUID.randomUUID(),
                1,
                boundary,
                boundary);
        var fixture = fixture("publisher-stale", new PublicationStateServiceAdapter(stateService));

        var result = fixture.loop.runNext();

        assertEquals(PublisherControlLoop.CycleOutcome.STALE_TRIGGER, result);
        assertEquals(PublicationState.NOOP, status(eventId).state());
        assertEquals(null, status(eventId).publishGeneration());
        assertTrue(fixture.taskFactory.generations.isEmpty());
        assertEquals(0L, currentGeneration());
    }

    @Test
    void should_retrySameGenerationAndComplete_when_retryWaitBecomesDue() {
        var eventId = insertImmediateEvent(1, NOW.minusSeconds(1));
        var initial = stateService.claimNext("publisher-initial", NOW, LEASE).orElseThrow();
        var failedAt = NOW.plusSeconds(1);
        assertTrue(stateService.recordTransientFailure(
                eventId,
                initial.publishGeneration(),
                "publisher-initial",
                failedAt));
        var retryAt = failedAt.plus(Duration.ofMinutes(1));
        var fixture = fixture(
                "publisher-retry",
                new PublicationStateServiceAdapter(stateService),
                retryAt);

        var result = fixture.loop.runNext();

        assertEquals(PublisherControlLoop.CycleOutcome.RESULT_RECORDED, result);
        assertEquals(1L, status(eventId).publishGeneration());
        assertEquals(2, status(eventId).attemptCount());
        assertEquals(PublicationState.SUCCEEDED, status(eventId).state());
        assertEquals(List.of(1L), fixture.taskFactory.generations);
        assertEquals(1L, currentGeneration());
    }

    @Test
    void should_recoverSameGenerationForOtherOwner_when_processingLeaseExpired() {
        var eventId = insertImmediateEvent(1, NOW.minusSeconds(1));
        var initial = stateService
                .claimNext("publisher-before-crash", NOW, Duration.ofSeconds(5))
                .orElseThrow();
        var fixture = fixture(
                "publisher-recovery",
                new PublicationStateServiceAdapter(stateService),
                NOW.plusSeconds(6));

        var result = fixture.loop.runNext();

        assertEquals(PublisherControlLoop.CycleOutcome.RESULT_RECORDED, result);
        assertEquals(initial.publishGeneration(), status(eventId).publishGeneration());
        assertEquals(2, status(eventId).attemptCount());
        assertEquals(PublicationState.SUCCEEDED, status(eventId).state());
        assertEquals(1L, currentGeneration());
    }

    @Test
    void should_neverEnterExecutorConcurrently_when_twoPublishersShareFilesystemLock()
            throws Exception {
        insertImmediateEvent(1, NOW.minusSeconds(2));
        insertImmediateEvent(2, NOW.minusSeconds(1));

        var bothClaimed = new CountDownLatch(2);
        var firstClaims = new FirstClaimOnlyStateOperations(
                new PublicationStateServiceAdapter(stateService), bothClaimed);
        var secondClaims = new FirstClaimOnlyStateOperations(
                new PublicationStateServiceAdapter(stateService), bothClaimed);

        var executorEntered = new CountDownLatch(1);
        var releaseExecutor = new CountDownLatch(1);
        var unavailableObserved = new CountDownLatch(1);
        var concurrent = new AtomicInteger();
        var maximumConcurrent = new AtomicInteger();
        var executorCalls = new AtomicInteger();
        var buildExecutorService = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().factory());
        var loopExecutor = Executors.newFixedThreadPool(2);
        try {
            var buildExecutor = (PublicationBuildExecutor) generation -> {
                executorCalls.incrementAndGet();
                var active = concurrent.incrementAndGet();
                maximumConcurrent.accumulateAndGet(active, Math::max);
                executorEntered.countDown();
                await(releaseExecutor);
                concurrent.decrementAndGet();
                return PublicationBuildResult.SUCCESS;
            };
            var taskFactory = new AsyncPublicationBuildTaskFactory(
                    buildExecutor, buildExecutorService);
            var lockFile = tempDirectory.resolve("shared-publisher.lock");
            var firstLock = new ObservedLock(
                    new FileSystemPublicationExecutionLock(lockFile), unavailableObserved);
            var secondLock = new ObservedLock(
                    new FileSystemPublicationExecutionLock(lockFile), unavailableObserved);
            var firstLoop = concurrentFixture(
                    "publisher-contender-a", firstClaims, taskFactory, firstLock);
            var secondLoop = concurrentFixture(
                    "publisher-contender-b", secondClaims, taskFactory, secondLock);

            var firstFuture = loopExecutor.submit(firstLoop::runNext);
            var secondFuture = loopExecutor.submit(secondLoop::runNext);

            assertTrue(executorEntered.await(10, TimeUnit.SECONDS));
            assertTrue(unavailableObserved.await(10, TimeUnit.SECONDS));
            releaseExecutor.countDown();
            var outcomes = List.of(firstFuture.get(), secondFuture.get());

            assertEquals(1, executorCalls.get());
            assertEquals(1, maximumConcurrent.get());
            assertTrue(outcomes.contains(PublisherControlLoop.CycleOutcome.RESULT_RECORDED));
            assertTrue(outcomes.contains(PublisherControlLoop.CycleOutcome.TRANSIENT_RECORDED));
            assertEquals(1, stateCount(PublicationState.SUCCEEDED));
            assertEquals(1, stateCount(PublicationState.RETRY_WAIT));
        } finally {
            releaseExecutor.countDown();
            loopExecutor.shutdownNow();
            buildExecutorService.shutdownNow();
        }
    }

    private IntegrationFixture fixture(String owner, PublicationStateOperations operations) {
        return fixture(owner, operations, NOW);
    }

    private IntegrationFixture fixture(
            String owner, PublicationStateOperations operations, Instant start) {
        var clock = new MutableClock(start);
        var taskFactory = new ImmediateTaskFactory();
        var loop = new PublisherControlLoop(
                operations,
                taskFactory,
                () -> Optional.of(() -> {}),
                clock,
                (duration, stopSignal) -> {
                    clock.advance(duration);
                    return true;
                },
                settings(owner),
                new PublisherStopSignal());
        return new IntegrationFixture(loop, taskFactory);
    }

    private PublisherControlLoop concurrentFixture(
            String owner,
            PublicationStateOperations operations,
            PublisherControlLoop.PublicationBuildTaskFactory taskFactory,
            PublicationExecutionLock lock) {
        var clock = new MutableClock(NOW);
        return new PublisherControlLoop(
                operations,
                taskFactory,
                lock,
                clock,
                (duration, stopSignal) -> {
                    clock.advance(duration);
                    return true;
                },
                settings(owner),
                new PublisherStopSignal());
    }

    private PublisherSettings settings(String owner) {
        return new PublisherSettings(
                owner,
                Duration.ofSeconds(5),
                LEASE,
                Duration.ofMinutes(1),
                Duration.ofSeconds(5),
                tempDirectory.resolve(owner + ".lock").toAbsolutePath());
    }

    private UUID insertImmediateEvent(long revision, Instant availableAt) {
        return insertEvent(
                PublicationEventKind.CONTENT_CHANGED,
                PublicationSourceType.BREED,
                UUID.randomUUID(),
                revision,
                availableAt,
                null);
    }

    private UUID insertEvent(
            PublicationEventKind kind,
            PublicationSourceType sourceType,
            UUID sourceId,
            long revision,
            Instant availableAt,
            Instant expectedBoundaryAt) {
        var eventId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO publishing_outbox (
                    id, kind, source_type, source_id, content_revision,
                    available_at, expected_boundary_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                eventId,
                kind.name(),
                sourceType.name(),
                sourceId,
                revision,
                offset(availableAt),
                expectedBoundaryAt == null ? null : offset(expectedBoundaryAt));
        return eventId;
    }

    private PublicationEventStatus status(UUID eventId) {
        return stateService.findStatus(eventId).orElseThrow();
    }

    private long currentGeneration() {
        return jdbcTemplate.queryForObject(
                "SELECT publish_generation FROM publish_generation_state WHERE singleton_key = 1",
                Long.class);
    }

    private int stateCount(PublicationState state) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM publishing_outbox WHERE state = ?",
                Integer.class,
                state.name());
    }

    private OffsetDateTime offset(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    private void clearState() {
        jdbcTemplate.update("DELETE FROM publishing_outbox");
        jdbcTemplate.update(
                "UPDATE publish_generation_state SET publish_generation = 0 WHERE singleton_key = 1");
        jdbcTemplate.update(
                "UPDATE content_revision_state SET content_revision = 0 WHERE singleton_key = 1");
    }

    private static void await(CountDownLatch latch) {
        try {
            if (!latch.await(10, TimeUnit.SECONDS)) {
                throw new IllegalStateException("Publisher concurrency fixture timed out");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("Publisher concurrency fixture interrupted");
        }
    }

    private record IntegrationFixture(
            PublisherControlLoop loop, ImmediateTaskFactory taskFactory) {}

    private static final class ImmediateTaskFactory
            implements PublisherControlLoop.PublicationBuildTaskFactory {

        private final List<Long> generations = new ArrayList<>();

        @Override
        public PublisherControlLoop.PublicationBuildTask start(long targetGeneration) {
            generations.add(targetGeneration);
            return new PublisherControlLoop.PublicationBuildTask() {
                @Override
                public PublisherControlLoop.BuildTaskPoll await(Duration timeout) {
                    return PublisherControlLoop.BuildTaskPoll.completed(
                            PublicationBuildResult.SUCCESS);
                }

                @Override
                public void cancel() {}

                @Override
                public void close() {}
            };
        }
    }

    private static final class MutableClock extends Clock {

        private Instant instant;

        private MutableClock(Instant instant) {
            this.instant = instant;
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
            return instant;
        }

        private void advance(Duration duration) {
            instant = instant.plus(duration);
        }
    }

    private static final class FirstClaimOnlyStateOperations
            implements PublicationStateOperations {

        private final PublicationStateOperations delegate;
        private final CountDownLatch bothClaimed;
        private boolean claimed;

        private FirstClaimOnlyStateOperations(
                PublicationStateOperations delegate, CountDownLatch bothClaimed) {
            this.delegate = delegate;
            this.bothClaimed = bothClaimed;
        }

        @Override
        public synchronized Optional<PublicationEventStatus> claimNext(
                String owner, Instant now, Duration leaseDuration) {
            if (claimed) {
                return Optional.empty();
            }
            claimed = true;
            var result = delegate.claimNext(owner, now, leaseDuration);
            bothClaimed.countDown();
            await(bothClaimed);
            return result;
        }

        @Override
        public boolean renewLease(
                UUID eventId,
                long publishGeneration,
                String owner,
                Instant now,
                Duration leaseDuration) {
            return delegate.renewLease(
                    eventId, publishGeneration, owner, now, leaseDuration);
        }

        @Override
        public boolean completeSuccess(
                UUID eventId, long publishGeneration, String owner, Instant now) {
            return delegate.completeSuccess(eventId, publishGeneration, owner, now);
        }

        @Override
        public boolean completeNoop(
                UUID eventId, long publishGeneration, String owner, Instant now) {
            return delegate.completeNoop(eventId, publishGeneration, owner, now);
        }

        @Override
        public boolean recordTransientFailure(
                UUID eventId, long publishGeneration, String owner, Instant now) {
            return delegate.recordTransientFailure(eventId, publishGeneration, owner, now);
        }

        @Override
        public boolean recordTerminalFailure(
                UUID eventId, long publishGeneration, String owner, Instant now) {
            return delegate.recordTerminalFailure(eventId, publishGeneration, owner, now);
        }

        @Override
        public boolean coalesceInto(
                UUID sourceEventId,
                long sourceGeneration,
                long targetGeneration,
                String owner,
                Instant now) {
            return delegate.coalesceInto(
                    sourceEventId, sourceGeneration, targetGeneration, owner, now);
        }
    }

    private static final class ObservedLock implements PublicationExecutionLock {

        private final PublicationExecutionLock delegate;
        private final CountDownLatch unavailableObserved;

        private ObservedLock(
                PublicationExecutionLock delegate, CountDownLatch unavailableObserved) {
            this.delegate = delegate;
            this.unavailableObserved = unavailableObserved;
        }

        @Override
        public Optional<Handle> tryAcquire() {
            var result = delegate.tryAcquire();
            if (result.isEmpty()) {
                unavailableObserved.countDown();
            }
            return result;
        }
    }
}
