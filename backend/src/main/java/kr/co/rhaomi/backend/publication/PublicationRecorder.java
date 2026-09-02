package kr.co.rhaomi.backend.publication;

import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@Component
public class PublicationRecorder {

    private final JdbcTemplate jdbcTemplate;

    public PublicationRecorder(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public long record(
            PublicationSourceType sourceType,
            UUID sourceId,
            boolean contentChanged,
            ScheduledPublicationEvent... scheduledEvents) {
        Objects.requireNonNull(sourceType, "sourceType");
        Objects.requireNonNull(sourceId, "sourceId");
        var events = Arrays.copyOf(
                Objects.requireNonNull(scheduledEvents, "scheduledEvents"),
                scheduledEvents.length);
        for (var event : events) {
            validateSource(event, sourceType);
        }

        var revision = jdbcTemplate.queryForObject(
                """
                UPDATE content_revision_state
                SET content_revision = content_revision + 1
                WHERE singleton_key = 1
                RETURNING content_revision
                """,
                Long.class);
        if (revision == null) {
            throw new IllegalStateException("Content revision allocation failed");
        }

        if (contentChanged) {
            insertImmediate(sourceType, sourceId, revision);
        }
        for (var event : events) {
            insertScheduled(sourceType, sourceId, revision, event);
        }
        return revision;
    }

    private void insertImmediate(PublicationSourceType sourceType, UUID sourceId, long revision) {
        jdbcTemplate.update(
                """
                INSERT INTO publishing_outbox (
                    id, kind, source_type, source_id, content_revision,
                    available_at, expected_boundary_at
                ) VALUES (?, 'CONTENT_CHANGED', ?, ?, ?, CURRENT_TIMESTAMP, NULL)
                """,
                UUID.randomUUID(),
                sourceType.name(),
                sourceId,
                revision);
    }

    private void insertScheduled(
            PublicationSourceType sourceType,
            UUID sourceId,
            long revision,
            ScheduledPublicationEvent event) {
        var boundary = event.expectedBoundaryAt().atOffset(ZoneOffset.UTC);
        jdbcTemplate.update(
                """
                INSERT INTO publishing_outbox (
                    id, kind, source_type, source_id, content_revision,
                    available_at, expected_boundary_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                event.kind().name(),
                sourceType.name(),
                sourceId,
                revision,
                boundary,
                boundary);
    }

    private void validateSource(
            ScheduledPublicationEvent event, PublicationSourceType sourceType) {
        var valid = switch (event.kind()) {
            case NOTICE_PUBLISHED_AT_DUE, NOTICE_EXPIRES_AT_DUE ->
                sourceType == PublicationSourceType.NOTICE;
            case GALLERY_PUBLISHED_AT_DUE ->
                sourceType == PublicationSourceType.GALLERY_ITEM;
            case CONTENT_CHANGED -> false;
        };
        if (!valid) {
            throw new IllegalArgumentException("Scheduled event source does not match its kind");
        }
    }
}
