package kr.co.rhaomi.backend.publication;

import java.util.Objects;
import java.util.UUID;

public record ScheduledPublicationSourceEvent(
        PublicationSourceType sourceType,
        UUID sourceId,
        ScheduledPublicationEvent event) {

    public ScheduledPublicationSourceEvent {
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(sourceId, "sourceId");
        Objects.requireNonNull(event, "event");
    }
}
