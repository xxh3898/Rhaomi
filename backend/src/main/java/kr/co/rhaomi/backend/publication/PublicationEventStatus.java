package kr.co.rhaomi.backend.publication;

import java.time.Instant;
import java.util.UUID;

public record PublicationEventStatus(
        UUID eventId,
        PublicationEventKind kind,
        PublicationSourceType sourceType,
        UUID sourceId,
        long contentRevision,
        Instant availableAt,
        Instant expectedBoundaryAt,
        Instant createdAt,
        PublicationState state,
        Long publishGeneration,
        int attemptCount,
        String claimOwner,
        Instant claimedAt,
        Instant leaseUntil,
        Instant nextAttemptAt,
        Instant completedAt,
        PublicationResultCode lastResultCode,
        Long coalescedIntoGeneration) {}
