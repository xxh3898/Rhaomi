package kr.co.rhaomi.publisher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import kr.co.rhaomi.backend.publication.PublicationEventKind;
import kr.co.rhaomi.backend.publication.PublicationEventStatus;
import kr.co.rhaomi.backend.publication.PublicationSourceType;
import kr.co.rhaomi.backend.publication.PublicationState;
import org.junit.jupiter.api.Test;

class PublisherControlLoopTest {

    private static final Instant NOW = Instant.parse("2035-01-01T00:00:00Z");
    private static final String OWNER = "publisher-unit";

    @Test
    void should_waitOnceWithoutBusySpin_when_noClaimExists() {
        var fixture = fixture();

        var result = fixture.loop.runNext();

        assertEquals(PublisherControlLoop.CycleOutcome.IDLE, result);
        assertEquals(List.of(NOW), fixture.state.claimTimes);
        assertEquals(List.of(Duration.ofSeconds(5)), fixture.sleeper.waits);
        assertEquals(NOW.plusSeconds(5), fixture.clock.instant());
        assertTrue(fixture.tasks.startedGenerations.isEmpty());
    }

    @Test
    void should_skipExecutor_when_claimIsGenerationlessStaleNoop() {
        var fixture = fixture();
        fixture.state.claims.add(staleClaim());

        var result = fixture.loop.runNext();

        assertEquals(PublisherControlLoop.CycleOutcome.STALE_TRIGGER, result);
        assertTrue(fixture.tasks.startedGenerations.isEmpty());
        assertTrue(fixture.state.transitions.isEmpty());
    }

    @Test
    void should_useFixedThirtySecondWindowFromFirstClaim_when_singleGenerationIsAccepted() {
        var fixture = fixture();
        fixture.state.claims.add(processingClaim(1, NOW));

        var result = fixture.loop.runNext();

        assertEquals(PublisherControlLoop.CycleOutcome.RESULT_RECORDED, result);
        assertEquals(List.of(1L), fixture.tasks.startedGenerations);
        assertEquals(PublisherSettings.DEBOUNCE_WINDOW, fixture.sleeper.totalWait());
        assertEquals(NOW.plusSeconds(30), fixture.state.claimTimes.getLast());
        assertEquals("SUCCESS", fixture.state.transitions.getLast().kind());
    }

    @Test
    void should_coalesceLowerClaimsAndExecuteOnlyHighestGeneration_when_burstArrives() {
        var fixture = fixture();
        fixture.state.claims.add(processingClaim(1, NOW));
        fixture.state.claims.add(processingClaim(2, NOW));
        fixture.state.claims.add(processingClaim(3, NOW));

        var result = fixture.loop.runNext();

        assertEquals(PublisherControlLoop.CycleOutcome.RESULT_RECORDED, result);
        assertEquals(List.of(1L, 2L), fixture.state.coalesces.stream()
                .map(CoalesceCall::sourceGeneration)
                .toList());
        assertEquals(List.of(2L, 3L), fixture.state.coalesces.stream()
                .map(CoalesceCall::targetGeneration)
                .toList());
        assertEquals(2, fixture.state.coalesces.size());
        assertEquals(List.of(3L), fixture.tasks.startedGenerations);
        assertEquals(3L, fixture.state.transitions.getLast().generation());
    }

    @Test
    void should_keepHigherTargetAndCoalesceRecoveredLowerGeneration_when_lowerClaimArrivesLater() {
        var fixture = fixture();
        fixture.state.claims.add(processingClaim(3, NOW));
        fixture.state.claims.add(processingClaim(1, NOW));

        var result = fixture.loop.runNext();

        assertEquals(PublisherControlLoop.CycleOutcome.RESULT_RECORDED, result);
        assertEquals(List.of(new CoalesceCall(1, 3)), fixture.state.coalesces);
        assertEquals(List.of(3L), fixture.tasks.startedGenerations);
    }

    @Test
    void should_includeExactBoundaryAndExcludeAfterBoundary_when_eventsBecomeDueAroundWindowEnd() {
        var fixture = fixture();
        var firstId = UUID.randomUUID();
        var boundaryId = UUID.randomUUID();
        var firstReturned = new boolean[1];
        var boundaryReturned = new boolean[1];
        fixture.state.claimProvider = now -> {
            if (!firstReturned[0]) {
                firstReturned[0] = true;
                return Optional.of(processingClaim(firstId, 1, NOW));
            }
            if (!boundaryReturned[0] && !now.isBefore(NOW.plusSeconds(30))) {
                boundaryReturned[0] = true;
                return Optional.of(processingClaim(boundaryId, 2, NOW.plusSeconds(30)));
            }
            return Optional.empty();
        };

        var result = fixture.loop.runNext();

        assertEquals(PublisherControlLoop.CycleOutcome.RESULT_RECORDED, result);
        assertTrue(boundaryReturned[0]);
        assertEquals(List.of(2L), fixture.tasks.startedGenerations);
        assertTrue(fixture.state.claimTimes.stream()
                .noneMatch(time -> time.isAfter(NOW.plusSeconds(30))));
        assertEquals(
                NOW.plusSeconds(30), fixture.state.claimTimes.getLast());
    }

    @Test
    void should_renewLeaseDuringDebounceAndExecutor_when_buildRemainsPending() {
        var fixture = fixture(settings(
                Duration.ofSeconds(1), Duration.ofSeconds(10), Duration.ofSeconds(2)));
        fixture.state.claims.add(processingClaim(1, NOW));
        fixture.tasks.tasks.add(new ScriptedTask(
                fixture.clock,
                List.of(
                        PublisherControlLoop.BuildTaskPoll.pending(),
                        PublisherControlLoop.BuildTaskPoll.pending(),
                        PublisherControlLoop.BuildTaskPoll.pending(),
                        PublisherControlLoop.BuildTaskPoll.completed(
                                PublicationBuildResult.SUCCESS))));

        var result = fixture.loop.runNext();

        assertEquals(PublisherControlLoop.CycleOutcome.RESULT_RECORDED, result);
        assertTrue(fixture.state.renewTimes.stream()
                .anyMatch(time -> time.isAfter(NOW.plusSeconds(30))));
        assertEquals("SUCCESS", fixture.state.transitions.getLast().kind());
    }

    @Test
    void should_notCompleteOrExecute_when_leaseIsLostBeforeLock() {
        var fixture = fixture();
        fixture.state.claims.add(processingClaim(1, NOW));
        fixture.state.renewResults.add(true);
        fixture.state.renewResults.add(false);

        var result = fixture.loop.runNext();

        assertEquals(PublisherControlLoop.CycleOutcome.LEASE_LOST, result);
        assertTrue(fixture.tasks.startedGenerations.isEmpty());
        assertTrue(fixture.state.transitions.isEmpty());
    }

    @Test
    void should_cancelAndNotComplete_when_leaseIsLostDuringExecutor() {
        var fixture = fixture();
        fixture.state.claims.add(processingClaim(1, NOW));
        fixture.state.renewResults.add(true);
        fixture.state.renewResults.add(true);
        fixture.state.renewResults.add(false);
        var task = new ScriptedTask(
                fixture.clock,
                List.of(
                        PublisherControlLoop.BuildTaskPoll.pending(),
                        PublisherControlLoop.BuildTaskPoll.pending(),
                        PublisherControlLoop.BuildTaskPoll.pending(),
                        PublisherControlLoop.BuildTaskPoll.pending(),
                        PublisherControlLoop.BuildTaskPoll.pending(),
                        PublisherControlLoop.BuildTaskPoll.pending(),
                        PublisherControlLoop.BuildTaskPoll.completed(
                                PublicationBuildResult.SUCCESS)));
        fixture.tasks.tasks.add(task);

        var result = fixture.loop.runNext();

        assertEquals(PublisherControlLoop.CycleOutcome.LEASE_LOST, result);
        assertTrue(task.cancelled);
        assertTrue(fixture.state.transitions.isEmpty());
        assertEquals(1, fixture.lock.closeCount);
    }

    @Test
    void should_recordSuccess_when_executorReturnsSuccess() {
        assertResultMapping(PublicationBuildResult.SUCCESS, "SUCCESS");
    }

    @Test
    void should_recordNoop_when_executorReturnsNoPublicChange() {
        assertResultMapping(PublicationBuildResult.NO_PUBLIC_CHANGE, "NOOP");
    }

    @Test
    void should_recordTransientFailure_when_executorReturnsTransientFailure() {
        assertResultMapping(PublicationBuildResult.TRANSIENT_FAILURE, "TRANSIENT");
    }

    @Test
    void should_recordTerminalFailure_when_executorReturnsTerminalFailure() {
        assertResultMapping(PublicationBuildResult.TERMINAL_FAILURE, "TERMINAL");
    }

    @Test
    void should_recordSafeTransientCategory_when_executorThrowsInternalException() {
        var fixture = fixture();
        fixture.state.claims.add(processingClaim(1, NOW));
        fixture.tasks.tasks.add(new ThrowingTask());

        var result = fixture.loop.runNext();

        assertEquals(PublisherControlLoop.CycleOutcome.TRANSIENT_RECORDED, result);
        assertEquals("TRANSIENT", fixture.state.transitions.getLast().kind());
        assertEquals(1, fixture.lock.closeCount);
    }

    @Test
    void should_notExecuteAndRecordTransientFailure_when_globalLockIsUnavailable() {
        var fixture = fixture();
        fixture.state.claims.add(processingClaim(1, NOW));
        fixture.lock.available = false;

        var result = fixture.loop.runNext();

        assertEquals(PublisherControlLoop.CycleOutcome.TRANSIENT_RECORDED, result);
        assertTrue(fixture.tasks.startedGenerations.isEmpty());
        assertEquals("TRANSIENT", fixture.state.transitions.getLast().kind());
    }

    @Test
    void should_notClaim_when_shutdownWasRequestedBeforeIteration() {
        var fixture = fixture();
        fixture.stopSignal.requestStop();

        var result = fixture.loop.runNext();

        assertEquals(PublisherControlLoop.CycleOutcome.STOPPED, result);
        assertTrue(fixture.state.claimTimes.isEmpty());
        assertTrue(fixture.tasks.startedGenerations.isEmpty());
    }

    @Test
    void should_leaveClaimActiveWithoutCompletion_when_shutdownOccursDuringDebounce() {
        var fixture = fixture();
        fixture.state.claims.add(processingClaim(1, NOW));
        fixture.sleeper.stopOnNextWait = true;

        var result = fixture.loop.runNext();

        assertEquals(PublisherControlLoop.CycleOutcome.STOPPED, result);
        assertTrue(fixture.tasks.startedGenerations.isEmpty());
        assertTrue(fixture.state.transitions.isEmpty());
    }

    @Test
    void should_cancelBuildWithoutCompletion_when_shutdownOccursDuringExecutor() {
        var fixture = fixture();
        fixture.state.claims.add(processingClaim(1, NOW));
        var task = new ScriptedTask(
                fixture.clock, List.of(PublisherControlLoop.BuildTaskPoll.pending()));
        task.stopSignal = fixture.stopSignal;
        fixture.tasks.tasks.add(task);

        var result = fixture.loop.runNext();

        assertEquals(PublisherControlLoop.CycleOutcome.STOPPED, result);
        assertTrue(task.cancelled);
        assertTrue(fixture.state.transitions.isEmpty());
        assertEquals(1, fixture.lock.closeCount);
    }

    @Test
    void should_notExecute_when_coalesceMutationIsRejected() {
        var fixture = fixture();
        fixture.state.claims.add(processingClaim(1, NOW));
        fixture.state.claims.add(processingClaim(2, NOW));
        fixture.state.coalesceResult = false;

        var result = fixture.loop.runNext();

        assertEquals(PublisherControlLoop.CycleOutcome.TRANSIENT_RECORDED, result);
        assertTrue(fixture.tasks.startedGenerations.isEmpty());
        assertEquals(2L, fixture.state.transitions.getLast().generation());
    }

    @Test
    void should_notReportSuccess_when_stateCompletionRejectsMutation() {
        var fixture = fixture();
        fixture.state.claims.add(processingClaim(1, NOW));
        fixture.state.transitionResult = false;

        var result = fixture.loop.runNext();

        assertEquals(PublisherControlLoop.CycleOutcome.LEASE_LOST, result);
        assertEquals("SUCCESS", fixture.state.transitions.getLast().kind());
    }

    private void assertResultMapping(PublicationBuildResult result, String expectedTransition) {
        var fixture = fixture();
        fixture.state.claims.add(processingClaim(1, NOW));
        fixture.tasks.tasks.add(new ScriptedTask(
                fixture.clock,
                List.of(PublisherControlLoop.BuildTaskPoll.completed(result))));

        var outcome = fixture.loop.runNext();

        assertEquals(PublisherControlLoop.CycleOutcome.RESULT_RECORDED, outcome);
        assertEquals(expectedTransition, fixture.state.transitions.getLast().kind());
        assertEquals(1L, fixture.state.transitions.getLast().generation());
        assertEquals(1, fixture.lock.closeCount);
    }

    private Fixture fixture() {
        return fixture(settings(
                Duration.ofSeconds(5), Duration.ofMinutes(2), Duration.ofSeconds(30)));
    }

    private Fixture fixture(PublisherSettings settings) {
        var clock = new MutableClock(NOW);
        var state = new FakeState();
        var tasks = new RecordingTaskFactory(clock);
        var lock = new RecordingLock();
        var stopSignal = new PublisherStopSignal();
        var sleeper = new AdvancingSleeper(clock, stopSignal);
        var loop = new PublisherControlLoop(
                state, tasks, lock, clock, sleeper, settings, stopSignal);
        return new Fixture(clock, state, tasks, lock, stopSignal, sleeper, loop);
    }

    private PublisherSettings settings(
            Duration poll, Duration lease, Duration renewal) {
        return new PublisherSettings(
                OWNER,
                poll,
                lease,
                renewal,
                Duration.ofSeconds(1),
                Path.of("/tmp/rhaomi-publisher-unit.lock"));
    }

    private static PublicationEventStatus processingClaim(long generation, Instant claimedAt) {
        return processingClaim(UUID.randomUUID(), generation, claimedAt);
    }

    private static PublicationEventStatus processingClaim(
            UUID eventId, long generation, Instant claimedAt) {
        return new PublicationEventStatus(
                eventId,
                PublicationEventKind.CONTENT_CHANGED,
                PublicationSourceType.BREED,
                UUID.randomUUID(),
                generation,
                claimedAt,
                null,
                claimedAt,
                PublicationState.PROCESSING,
                generation,
                1,
                OWNER,
                claimedAt,
                claimedAt.plus(Duration.ofMinutes(10)),
                null,
                null,
                null,
                null);
    }

    private static PublicationEventStatus staleClaim() {
        return new PublicationEventStatus(
                UUID.randomUUID(),
                PublicationEventKind.NOTICE_EXPIRES_AT_DUE,
                PublicationSourceType.NOTICE,
                UUID.randomUUID(),
                1,
                NOW,
                NOW,
                NOW,
                PublicationState.NOOP,
                null,
                0,
                null,
                null,
                null,
                null,
                NOW,
                kr.co.rhaomi.backend.publication.PublicationResultCode.STALE_TRIGGER,
                null);
    }

    private record Fixture(
            MutableClock clock,
            FakeState state,
            RecordingTaskFactory tasks,
            RecordingLock lock,
            PublisherStopSignal stopSignal,
            AdvancingSleeper sleeper,
            PublisherControlLoop loop) {}

    private record Transition(String kind, long generation) {}

    private record CoalesceCall(long sourceGeneration, long targetGeneration) {}

    @FunctionalInterface
    private interface ClaimProvider {

        Optional<PublicationEventStatus> claim(Instant now);
    }

    private static final class FakeState implements PublicationStateOperations {

        private final Deque<PublicationEventStatus> claims = new ArrayDeque<>();
        private final Deque<Boolean> renewResults = new ArrayDeque<>();
        private final List<Instant> claimTimes = new ArrayList<>();
        private final List<Instant> renewTimes = new ArrayList<>();
        private final List<Transition> transitions = new ArrayList<>();
        private final List<CoalesceCall> coalesces = new ArrayList<>();
        private ClaimProvider claimProvider;
        private boolean transitionResult = true;
        private boolean coalesceResult = true;

        @Override
        public Optional<PublicationEventStatus> claimNext(
                String owner, Instant now, Duration leaseDuration) {
            claimTimes.add(now);
            if (claimProvider != null) {
                return claimProvider.claim(now);
            }
            return Optional.ofNullable(claims.pollFirst());
        }

        @Override
        public boolean renewLease(
                UUID eventId,
                long publishGeneration,
                String owner,
                Instant now,
                Duration leaseDuration) {
            renewTimes.add(now);
            return renewResults.isEmpty() || renewResults.removeFirst();
        }

        @Override
        public boolean completeSuccess(
                UUID eventId, long publishGeneration, String owner, Instant now) {
            transitions.add(new Transition("SUCCESS", publishGeneration));
            return transitionResult;
        }

        @Override
        public boolean completeNoop(
                UUID eventId, long publishGeneration, String owner, Instant now) {
            transitions.add(new Transition("NOOP", publishGeneration));
            return transitionResult;
        }

        @Override
        public boolean recordTransientFailure(
                UUID eventId, long publishGeneration, String owner, Instant now) {
            transitions.add(new Transition("TRANSIENT", publishGeneration));
            return transitionResult;
        }

        @Override
        public boolean recordTerminalFailure(
                UUID eventId, long publishGeneration, String owner, Instant now) {
            transitions.add(new Transition("TERMINAL", publishGeneration));
            return transitionResult;
        }

        @Override
        public boolean coalesceInto(
                UUID sourceEventId,
                long sourceGeneration,
                long targetGeneration,
                String owner,
                Instant now) {
            coalesces.add(new CoalesceCall(sourceGeneration, targetGeneration));
            return coalesceResult;
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

    private static final class AdvancingSleeper
            implements PublisherControlLoop.PublisherSleeper {

        private final MutableClock clock;
        private final PublisherStopSignal stopSignal;
        private final List<Duration> waits = new ArrayList<>();
        private boolean stopOnNextWait;

        private AdvancingSleeper(MutableClock clock, PublisherStopSignal stopSignal) {
            this.clock = clock;
            this.stopSignal = stopSignal;
        }

        @Override
        public boolean await(Duration duration, PublisherStopSignal ignored) {
            waits.add(duration);
            if (stopOnNextWait) {
                stopOnNextWait = false;
                stopSignal.requestStop();
                return false;
            }
            clock.advance(duration);
            return true;
        }

        private Duration totalWait() {
            return waits.stream().reduce(Duration.ZERO, Duration::plus);
        }
    }

    private static final class RecordingTaskFactory
            implements PublisherControlLoop.PublicationBuildTaskFactory {

        private final MutableClock clock;
        private final List<Long> startedGenerations = new ArrayList<>();
        private final Deque<PublisherControlLoop.PublicationBuildTask> tasks =
                new ArrayDeque<>();

        private RecordingTaskFactory(MutableClock clock) {
            this.clock = clock;
        }

        @Override
        public PublisherControlLoop.PublicationBuildTask start(long targetGeneration) {
            startedGenerations.add(targetGeneration);
            var task = tasks.pollFirst();
            return task == null
                    ? new ScriptedTask(
                            clock,
                            List.of(PublisherControlLoop.BuildTaskPoll.completed(
                                    PublicationBuildResult.SUCCESS)))
                    : task;
        }
    }

    private static final class ScriptedTask
            implements PublisherControlLoop.PublicationBuildTask {

        private final MutableClock clock;
        private final Deque<PublisherControlLoop.BuildTaskPoll> polls;
        private PublisherStopSignal stopSignal;
        private boolean cancelled;

        private ScriptedTask(
                MutableClock clock, List<PublisherControlLoop.BuildTaskPoll> polls) {
            this.clock = clock;
            this.polls = new ArrayDeque<>(polls);
        }

        @Override
        public PublisherControlLoop.BuildTaskPoll await(Duration timeout) {
            var result = polls.isEmpty()
                    ? PublisherControlLoop.BuildTaskPoll.pending()
                    : polls.removeFirst();
            if (!result.completed()) {
                clock.advance(timeout);
                if (stopSignal != null) {
                    stopSignal.requestStop();
                }
            }
            return result;
        }

        @Override
        public void cancel() {
            cancelled = true;
        }

        @Override
        public void close() {
            if (!polls.isEmpty()) {
                cancelled = true;
            }
        }
    }

    private static final class ThrowingTask
            implements PublisherControlLoop.PublicationBuildTask {

        @Override
        public PublisherControlLoop.BuildTaskPoll await(Duration timeout) {
            throw new PublisherControlLoop.PublicationBuildExecutionException();
        }

        @Override
        public void cancel() {}

        @Override
        public void close() {}
    }

    private static final class RecordingLock implements PublicationExecutionLock {

        private boolean available = true;
        private int closeCount;

        @Override
        public Optional<Handle> tryAcquire() {
            if (!available) {
                return Optional.empty();
            }
            return Optional.of(() -> closeCount++);
        }
    }
}
