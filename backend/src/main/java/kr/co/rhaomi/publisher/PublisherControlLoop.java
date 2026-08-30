package kr.co.rhaomi.publisher;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import kr.co.rhaomi.backend.publication.PublicationEventStatus;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public final class PublisherControlLoop {

    private static final Logger LOGGER = LoggerFactory.getLogger(PublisherControlLoop.class);

    private final PublicationStateOperations stateOperations;
    private final PublicationBuildTaskFactory taskFactory;
    private final PublicationExecutionLock executionLock;
    private final Clock clock;
    private final PublisherSleeper sleeper;
    private final PublisherSettings settings;
    private final PublisherStopSignal stopSignal;

    public PublisherControlLoop(
            PublicationStateOperations stateOperations,
            PublicationBuildTaskFactory taskFactory,
            PublicationExecutionLock executionLock,
            Clock clock,
            PublisherSleeper sleeper,
            PublisherSettings settings,
            PublisherStopSignal stopSignal) {
        this.stateOperations = stateOperations;
        this.taskFactory = taskFactory;
        this.executionLock = executionLock;
        this.clock = clock;
        this.sleeper = sleeper;
        this.settings = settings;
        this.stopSignal = stopSignal;
    }

    public void run() {
        while (!stopSignal.isRequested()) {
            try {
                runNext();
            } catch (RuntimeException exception) {
                LOGGER.warn("Publisher control iteration failed [PUBLISHER_INTERNAL]");
                sleeper.await(settings.idlePollInterval(), stopSignal);
            }
        }
    }

    CycleOutcome runNext() {
        if (stopSignal.isRequested()) {
            return CycleOutcome.STOPPED;
        }

        var claim = stateOperations.claimNext(
                settings.owner(), clock.instant(), settings.leaseDuration());
        if (claim.isEmpty()) {
            sleeper.await(settings.idlePollInterval(), stopSignal);
            return stopSignal.isRequested() ? CycleOutcome.STOPPED : CycleOutcome.IDLE;
        }

        var first = claim.orElseThrow();
        if (first.publishGeneration() == null) {
            return CycleOutcome.STALE_TRIGGER;
        }
        return debounceAndExecute(first);
    }

    private CycleOutcome debounceAndExecute(PublicationEventStatus first) {
        var target = first;
        var firstClaimedAt = Objects.requireNonNull(first.claimedAt(), "claimedAt");
        var boundary = firstClaimedAt.plus(PublisherSettings.DEBOUNCE_WINDOW);
        var nextRenewalAt = firstClaimedAt.plus(settings.leaseRenewalInterval());

        while (true) {
            if (stopSignal.isRequested()) {
                return CycleOutcome.STOPPED;
            }

            var actualNow = clock.instant();
            if (!actualNow.isBefore(nextRenewalAt)) {
                if (!renew(target, actualNow)) {
                    return CycleOutcome.LEASE_LOST;
                }
                nextRenewalAt = actualNow.plus(settings.leaseRenewalInterval());
            }

            var claimAt = actualNow.isAfter(boundary) ? boundary : actualNow;
            var claim = stateOperations.claimNext(
                    settings.owner(), claimAt, settings.leaseDuration());
            if (claim.isPresent()) {
                var candidate = claim.orElseThrow();
                if (candidate.publishGeneration() == null) {
                    continue;
                }

                var coalesced = coalesce(target, candidate, claimAt);
                target = coalesced.target();
                if (!coalesced.success()) {
                    return recordTransient(target, claimAt);
                }
                nextRenewalAt = Objects.requireNonNull(target.claimedAt(), "claimedAt")
                        .plus(settings.leaseRenewalInterval());
                continue;
            }

            if (!claimAt.isBefore(boundary)) {
                break;
            }

            var wait = minimum(
                    settings.idlePollInterval(),
                    Duration.between(actualNow, boundary),
                    Duration.between(actualNow, nextRenewalAt));
            sleeper.await(wait, stopSignal);
        }

        if (stopSignal.isRequested()) {
            return CycleOutcome.STOPPED;
        }

        var beforeLock = clock.instant();
        if (!renew(target, beforeLock)) {
            return CycleOutcome.LEASE_LOST;
        }

        ExecutionOutcome executionOutcome;
        try {
            var acquired = executionLock.tryAcquire();
            if (acquired.isEmpty()) {
                return recordTransient(target, clock.instant());
            }
            try (var ignored = acquired.orElseThrow()) {
                if (stopSignal.isRequested()) {
                    return CycleOutcome.STOPPED;
                }
                executionOutcome = executeWithLease(target);
            }
        } catch (RuntimeException exception) {
            if (stopSignal.isRequested()) {
                return CycleOutcome.STOPPED;
            }
            return recordTransient(target, clock.instant());
        }

        return switch (executionOutcome.kind()) {
            case STOPPED -> CycleOutcome.STOPPED;
            case LEASE_LOST -> CycleOutcome.LEASE_LOST;
            case SAFE_FAILURE -> recordTransient(target, clock.instant());
            case RESULT -> applyResult(target, executionOutcome.result(), clock.instant());
        };
    }

    private CoalesceOutcome coalesce(
            PublicationEventStatus current,
            PublicationEventStatus candidate,
            Instant now) {
        var currentGeneration = generation(current);
        var candidateGeneration = generation(candidate);
        if (candidateGeneration > currentGeneration) {
            var success = stateOperations.coalesceInto(
                    current.eventId(),
                    currentGeneration,
                    candidateGeneration,
                    settings.owner(),
                    now);
            return new CoalesceOutcome(candidate, success);
        }
        if (candidateGeneration < currentGeneration) {
            var success = stateOperations.coalesceInto(
                    candidate.eventId(),
                    candidateGeneration,
                    currentGeneration,
                    settings.owner(),
                    now);
            return new CoalesceOutcome(current, success);
        }
        return new CoalesceOutcome(current, false);
    }

    private ExecutionOutcome executeWithLease(PublicationEventStatus target) {
        try (var task = taskFactory.start(generation(target))) {
            var nextRenewalAt = clock.instant().plus(settings.leaseRenewalInterval());
            while (true) {
                if (stopSignal.isRequested()) {
                    task.cancel();
                    return ExecutionOutcome.stopped();
                }

                var now = clock.instant();
                if (!now.isBefore(nextRenewalAt)) {
                    if (!renew(target, now)) {
                        task.cancel();
                        return ExecutionOutcome.leaseLost();
                    }
                    nextRenewalAt = now.plus(settings.leaseRenewalInterval());
                }

                var wait = minimum(
                        settings.idlePollInterval(),
                        Duration.between(now, nextRenewalAt));
                var polled = task.await(wait);
                if (!polled.completed()) {
                    continue;
                }
                if (stopSignal.isRequested()) {
                    task.cancel();
                    return ExecutionOutcome.stopped();
                }
                if (!renew(target, clock.instant())) {
                    return ExecutionOutcome.leaseLost();
                }
                return ExecutionOutcome.result(polled.result());
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            stopSignal.requestStop();
            return ExecutionOutcome.stopped();
        } catch (RuntimeException exception) {
            return ExecutionOutcome.safeFailure();
        }
    }

    private boolean renew(PublicationEventStatus target, Instant now) {
        return stateOperations.renewLease(
                target.eventId(),
                generation(target),
                settings.owner(),
                now,
                settings.leaseDuration());
    }

    private CycleOutcome applyResult(
            PublicationEventStatus target,
            PublicationBuildResult result,
            Instant now) {
        var generation = generation(target);
        var applied = switch (result) {
            case SUCCESS -> stateOperations.completeSuccess(
                    target.eventId(), generation, settings.owner(), now);
            case NO_PUBLIC_CHANGE -> stateOperations.completeNoop(
                    target.eventId(), generation, settings.owner(), now);
            case TRANSIENT_FAILURE -> stateOperations.recordTransientFailure(
                    target.eventId(), generation, settings.owner(), now);
            case TERMINAL_FAILURE -> stateOperations.recordTerminalFailure(
                    target.eventId(), generation, settings.owner(), now);
        };
        return applied ? CycleOutcome.RESULT_RECORDED : CycleOutcome.LEASE_LOST;
    }

    private CycleOutcome recordTransient(PublicationEventStatus target, Instant now) {
        var applied = stateOperations.recordTransientFailure(
                target.eventId(), generation(target), settings.owner(), now);
        return applied ? CycleOutcome.TRANSIENT_RECORDED : CycleOutcome.LEASE_LOST;
    }

    private long generation(PublicationEventStatus status) {
        return Objects.requireNonNull(status.publishGeneration(), "publishGeneration");
    }

    private Duration minimum(Duration first, Duration... remaining) {
        var result = first;
        for (var candidate : remaining) {
            if (!candidate.isNegative() && !candidate.isZero() && candidate.compareTo(result) < 0) {
                result = candidate;
            }
        }
        return result;
    }

    enum CycleOutcome {
        IDLE,
        STALE_TRIGGER,
        RESULT_RECORDED,
        TRANSIENT_RECORDED,
        LEASE_LOST,
        STOPPED
    }

    @FunctionalInterface
    interface PublisherSleeper {

        boolean await(Duration duration, PublisherStopSignal stopSignal);
    }

    @FunctionalInterface
    interface PublicationBuildTaskFactory {

        PublicationBuildTask start(long targetGeneration);
    }

    interface PublicationBuildTask extends AutoCloseable {

        BuildTaskPoll await(Duration timeout) throws InterruptedException;

        void cancel();

        @Override
        void close();
    }

    record BuildTaskPoll(boolean completed, PublicationBuildResult result) {

        BuildTaskPoll {
            if (completed != (result != null)) {
                throw new IllegalArgumentException("Invalid build task poll result");
            }
        }

        static BuildTaskPoll pending() {
            return new BuildTaskPoll(false, null);
        }

        static BuildTaskPoll completed(PublicationBuildResult result) {
            return new BuildTaskPoll(true, Objects.requireNonNull(result, "result"));
        }
    }

    static final class PublicationBuildExecutionException extends RuntimeException {

        PublicationBuildExecutionException() {
            super("Publication build execution failed");
        }
    }

    private record CoalesceOutcome(PublicationEventStatus target, boolean success) {}

    private enum ExecutionKind {
        RESULT,
        SAFE_FAILURE,
        LEASE_LOST,
        STOPPED
    }

    private record ExecutionOutcome(ExecutionKind kind, PublicationBuildResult result) {

        private static ExecutionOutcome result(PublicationBuildResult result) {
            return new ExecutionOutcome(ExecutionKind.RESULT, result);
        }

        private static ExecutionOutcome safeFailure() {
            return new ExecutionOutcome(ExecutionKind.SAFE_FAILURE, null);
        }

        private static ExecutionOutcome leaseLost() {
            return new ExecutionOutcome(ExecutionKind.LEASE_LOST, null);
        }

        private static ExecutionOutcome stopped() {
            return new ExecutionOutcome(ExecutionKind.STOPPED, null);
        }
    }
}
