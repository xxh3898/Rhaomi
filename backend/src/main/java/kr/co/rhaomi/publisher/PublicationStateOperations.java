package kr.co.rhaomi.publisher;

import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import kr.co.rhaomi.backend.publication.PublicationEventStatus;

public interface PublicationStateOperations {

    Optional<PublicationEventStatus> claimNext(String owner, Instant now, Duration leaseDuration);

    boolean renewLease(
            UUID eventId,
            long publishGeneration,
            String owner,
            Instant now,
            Duration leaseDuration);

    boolean completeSuccess(UUID eventId, long publishGeneration, String owner, Instant now);

    boolean completeNoop(UUID eventId, long publishGeneration, String owner, Instant now);

    boolean recordTransientFailure(
            UUID eventId, long publishGeneration, String owner, Instant now);

    boolean recordTerminalFailure(
            UUID eventId, long publishGeneration, String owner, Instant now);

    boolean coalesceInto(
            UUID sourceEventId,
            long sourceGeneration,
            long targetGeneration,
            String owner,
            Instant now);
}
