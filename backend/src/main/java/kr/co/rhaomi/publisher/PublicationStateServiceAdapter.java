package kr.co.rhaomi.publisher;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import kr.co.rhaomi.backend.publication.PublicationEventStatus;
import kr.co.rhaomi.backend.publication.PublicationStateService;

final class PublicationStateServiceAdapter implements PublicationStateOperations {

    private final PublicationStateService stateService;

    PublicationStateServiceAdapter(PublicationStateService stateService) {
        this.stateService = stateService;
    }

    @Override
    public Optional<PublicationEventStatus> claimNext(
            String owner, Instant now, Duration leaseDuration) {
        return stateService.claimNext(owner, now, leaseDuration);
    }

    @Override
    public boolean renewLease(
            UUID eventId,
            long publishGeneration,
            String owner,
            Instant now,
            Duration leaseDuration) {
        return stateService.renewLease(eventId, publishGeneration, owner, now, leaseDuration);
    }

    @Override
    public boolean completeSuccess(
            UUID eventId, long publishGeneration, String owner, Instant now) {
        return stateService.completeSuccess(eventId, publishGeneration, owner, now);
    }

    @Override
    public boolean completeNoop(
            UUID eventId, long publishGeneration, String owner, Instant now) {
        return stateService.completeNoop(eventId, publishGeneration, owner, now);
    }

    @Override
    public boolean recordTransientFailure(
            UUID eventId, long publishGeneration, String owner, Instant now) {
        return stateService.recordTransientFailure(eventId, publishGeneration, owner, now);
    }

    @Override
    public boolean recordTerminalFailure(
            UUID eventId, long publishGeneration, String owner, Instant now) {
        return stateService.recordTerminalFailure(eventId, publishGeneration, owner, now);
    }

    @Override
    public boolean coalesceInto(
            UUID sourceEventId,
            long sourceGeneration,
            long targetGeneration,
            String owner,
            Instant now) {
        return stateService.coalesceInto(
                sourceEventId, sourceGeneration, targetGeneration, owner, now);
    }
}
