package kr.co.rhaomi.backend.publication;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.Objects;

public record ScheduledPublicationEvent(
        PublicationEventKind kind, Instant expectedBoundaryAt) {

    public ScheduledPublicationEvent {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(expectedBoundaryAt, "expectedBoundaryAt");
        if (!kind.isScheduled()) {
            throw new IllegalArgumentException("Scheduled event kind is required");
        }
        expectedBoundaryAt = expectedBoundaryAt.truncatedTo(ChronoUnit.MICROS);
    }
}
