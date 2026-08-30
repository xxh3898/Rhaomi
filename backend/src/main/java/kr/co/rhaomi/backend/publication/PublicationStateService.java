package kr.co.rhaomi.backend.publication;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
public class PublicationStateService {

    private static final int MAX_OWNER_CODE_POINTS = 128;
    private static final int MAX_ATTEMPTS = 4;

    private final JdbcTemplate jdbcTemplate;

    public PublicationStateService(JdbcTemplate jdbcTemplate) {
        this.jdbcTemplate = jdbcTemplate;
    }

    @Transactional
    public Optional<PublicationEventStatus> claimNext(
            String owner, Instant now, Duration leaseDuration) {
        var normalizedOwner = validateOwner(owner);
        var normalizedNow = normalize(now);
        var leaseUntil = leaseUntil(normalizedNow, leaseDuration);

        var expired = findExpiredProcessing(normalizedNow);
        if (expired.isPresent()) {
            return Optional.of(recoverOrExhaust(
                    expired.orElseThrow(), normalizedOwner, normalizedNow, leaseUntil));
        }

        var retry = findDueRetry(normalizedNow);
        if (retry.isPresent()) {
            return Optional.of(claimRetry(
                    retry.orElseThrow(), normalizedOwner, normalizedNow, leaseUntil));
        }

        var pending = findDuePending(normalizedNow);
        if (pending.isEmpty()) {
            return Optional.empty();
        }
        var event = pending.orElseThrow();
        if (event.kind().isScheduled() && !matchesCurrentBoundary(event)) {
            return Optional.of(markStale(event.eventId(), normalizedNow));
        }
        return Optional.of(claimFresh(event.eventId(), normalizedOwner, normalizedNow, leaseUntil));
    }

    @Transactional
    public boolean renewLease(
            UUID eventId,
            long publishGeneration,
            String owner,
            Instant now,
            Duration leaseDuration) {
        var normalizedEventId = Objects.requireNonNull(eventId, "eventId");
        var normalizedGeneration = validateGeneration(publishGeneration);
        var normalizedOwner = validateOwner(owner);
        var normalizedNow = normalize(now);
        var newLeaseUntil = leaseUntil(normalizedNow, leaseDuration);
        return jdbcTemplate.update(
                        """
                        UPDATE publishing_outbox
                        SET lease_until = ?
                        WHERE id = ?
                          AND state = 'PROCESSING'
                          AND publish_generation = ?
                          AND claim_owner = ?
                          AND claimed_at <= ?
                          AND lease_until > ?
                        """,
                        offset(newLeaseUntil),
                        normalizedEventId,
                        normalizedGeneration,
                        normalizedOwner,
                        offset(normalizedNow),
                        offset(normalizedNow))
                == 1;
    }

    @Transactional
    public boolean completeSuccess(
            UUID eventId, long publishGeneration, String owner, Instant now) {
        return complete(
                eventId,
                publishGeneration,
                owner,
                now,
                PublicationState.SUCCEEDED,
                PublicationResultCode.SUCCESS);
    }

    @Transactional
    public boolean completeNoop(
            UUID eventId, long publishGeneration, String owner, Instant now) {
        return complete(
                eventId,
                publishGeneration,
                owner,
                now,
                PublicationState.NOOP,
                PublicationResultCode.NO_PUBLIC_CHANGE);
    }

    @Transactional
    public boolean recordTerminalFailure(
            UUID eventId, long publishGeneration, String owner, Instant now) {
        return complete(
                eventId,
                publishGeneration,
                owner,
                now,
                PublicationState.FAILED,
                PublicationResultCode.TERMINAL_FAILURE);
    }

    @Transactional
    public boolean recordTransientFailure(
            UUID eventId, long publishGeneration, String owner, Instant now) {
        var normalizedEventId = Objects.requireNonNull(eventId, "eventId");
        var normalizedGeneration = validateGeneration(publishGeneration);
        var normalizedOwner = validateOwner(owner);
        var normalizedNow = normalize(now);
        var event = findOwnedActiveProcessing(
                        normalizedEventId,
                        normalizedGeneration,
                        normalizedOwner,
                        normalizedNow)
                .orElse(null);
        if (event == null) {
            return false;
        }
        if (event.attemptCount() >= MAX_ATTEMPTS) {
            return finishLocked(
                    event.eventId(),
                    PublicationState.FAILED,
                    PublicationResultCode.RETRY_EXHAUSTED,
                    normalizedNow,
                    null);
        }

        var nextAttemptAt = normalizedNow.plus(retryDelay(event.attemptCount()));
        return jdbcTemplate.update(
                        """
                        UPDATE publishing_outbox
                        SET state = 'RETRY_WAIT',
                            claim_owner = NULL,
                            lease_until = NULL,
                            next_attempt_at = ?,
                            last_result_code = 'TRANSIENT_FAILURE'
                        WHERE id = ?
                          AND state = 'PROCESSING'
                        """,
                        offset(nextAttemptAt),
                        event.eventId())
                == 1;
    }

    @Transactional
    public boolean coalesceInto(
            UUID sourceEventId,
            long sourceGeneration,
            long targetGeneration,
            String owner,
            Instant now) {
        var normalizedSourceEventId = Objects.requireNonNull(sourceEventId, "sourceEventId");
        var normalizedSourceGeneration = validateGeneration(sourceGeneration);
        var normalizedTargetGeneration = validateGeneration(targetGeneration);
        if (normalizedTargetGeneration <= normalizedSourceGeneration) {
            return false;
        }
        var normalizedOwner = validateOwner(owner);
        var normalizedNow = normalize(now);

        var source = findOwnedActiveProcessing(
                        normalizedSourceEventId,
                        normalizedSourceGeneration,
                        normalizedOwner,
                        normalizedNow)
                .orElse(null);
        if (source == null) {
            return false;
        }
        var target = findOwnedActiveProcessingByGeneration(
                        normalizedTargetGeneration, normalizedOwner, normalizedNow)
                .orElse(null);
        if (target == null) {
            return false;
        }

        return finishLocked(
                source.eventId(),
                PublicationState.COALESCED,
                PublicationResultCode.COALESCED,
                normalizedNow,
                normalizedTargetGeneration);
    }

    @Transactional(readOnly = true)
    public Optional<PublicationEventStatus> findStatus(UUID eventId) {
        return findById(Objects.requireNonNull(eventId, "eventId"));
    }

    private PublicationEventStatus recoverOrExhaust(
            PublicationEventStatus event,
            String owner,
            Instant now,
            Instant leaseUntil) {
        if (event.attemptCount() >= MAX_ATTEMPTS) {
            if (!finishLocked(
                    event.eventId(),
                    PublicationState.FAILED,
                    PublicationResultCode.RETRY_EXHAUSTED,
                    now,
                    null)) {
                throw new IllegalStateException("Expired publication attempt could not be exhausted");
            }
            return requireStatus(event.eventId());
        }

        var updated = jdbcTemplate.update(
                """
                UPDATE publishing_outbox
                SET attempt_count = attempt_count + 1,
                    claim_owner = ?,
                    claimed_at = ?,
                    lease_until = ?,
                    last_result_code = 'LEASE_EXPIRED'
                WHERE id = ?
                  AND state = 'PROCESSING'
                  AND lease_until <= ?
                """,
                owner,
                offset(now),
                offset(leaseUntil),
                event.eventId(),
                offset(now));
        if (updated != 1) {
            throw new IllegalStateException("Expired publication attempt could not be recovered");
        }
        return requireStatus(event.eventId());
    }

    private PublicationEventStatus claimRetry(
            PublicationEventStatus event,
            String owner,
            Instant now,
            Instant leaseUntil) {
        var updated = jdbcTemplate.update(
                """
                UPDATE publishing_outbox
                SET state = 'PROCESSING',
                    attempt_count = attempt_count + 1,
                    claim_owner = ?,
                    claimed_at = ?,
                    lease_until = ?,
                    next_attempt_at = NULL
                WHERE id = ?
                  AND state = 'RETRY_WAIT'
                  AND next_attempt_at <= ?
                """,
                owner,
                offset(now),
                offset(leaseUntil),
                event.eventId(),
                offset(now));
        if (updated != 1) {
            throw new IllegalStateException("Publication retry could not be claimed");
        }
        return requireStatus(event.eventId());
    }

    private PublicationEventStatus claimFresh(
            UUID eventId, String owner, Instant now, Instant leaseUntil) {
        var generation = jdbcTemplate.queryForObject(
                """
                UPDATE publish_generation_state
                SET publish_generation = publish_generation + 1
                WHERE singleton_key = 1
                RETURNING publish_generation
                """,
                Long.class);
        if (generation == null) {
            throw new IllegalStateException("Publish generation allocation failed");
        }

        var updated = jdbcTemplate.update(
                """
                UPDATE publishing_outbox
                SET state = 'PROCESSING',
                    publish_generation = ?,
                    attempt_count = 1,
                    claim_owner = ?,
                    claimed_at = ?,
                    lease_until = ?
                WHERE id = ?
                  AND state = 'PENDING'
                """,
                generation,
                owner,
                offset(now),
                offset(leaseUntil),
                eventId);
        if (updated != 1) {
            throw new IllegalStateException("Pending publication event could not be claimed");
        }
        return requireStatus(eventId);
    }

    private PublicationEventStatus markStale(UUID eventId, Instant now) {
        var updated = jdbcTemplate.update(
                """
                UPDATE publishing_outbox
                SET state = 'NOOP',
                    completed_at = ?,
                    last_result_code = 'STALE_TRIGGER'
                WHERE id = ?
                  AND state = 'PENDING'
                """,
                offset(now),
                eventId);
        if (updated != 1) {
            throw new IllegalStateException("Stale publication event could not be completed");
        }
        return requireStatus(eventId);
    }

    private boolean complete(
            UUID eventId,
            long publishGeneration,
            String owner,
            Instant now,
            PublicationState state,
            PublicationResultCode resultCode) {
        var normalizedEventId = Objects.requireNonNull(eventId, "eventId");
        var normalizedGeneration = validateGeneration(publishGeneration);
        var normalizedOwner = validateOwner(owner);
        var normalizedNow = normalize(now);
        var event = findOwnedActiveProcessing(
                        normalizedEventId,
                        normalizedGeneration,
                        normalizedOwner,
                        normalizedNow)
                .orElse(null);
        return event != null
                && finishLocked(event.eventId(), state, resultCode, normalizedNow, null);
    }

    private boolean finishLocked(
            UUID eventId,
            PublicationState state,
            PublicationResultCode resultCode,
            Instant completedAt,
            Long coalescedIntoGeneration) {
        return jdbcTemplate.update(
                        """
                        UPDATE publishing_outbox
                        SET state = ?,
                            claim_owner = NULL,
                            lease_until = NULL,
                            next_attempt_at = NULL,
                            completed_at = ?,
                            last_result_code = ?,
                            coalesced_into_generation = ?
                        WHERE id = ?
                          AND state = 'PROCESSING'
                        """,
                        state.name(),
                        offset(completedAt),
                        resultCode.name(),
                        coalescedIntoGeneration,
                        eventId)
                == 1;
    }

    private boolean matchesCurrentBoundary(PublicationEventStatus event) {
        var boundary = Objects.requireNonNull(
                event.expectedBoundaryAt(), "expectedBoundaryAt");
        return switch (event.kind()) {
            case NOTICE_PUBLISHED_AT_DUE -> existsCurrentNoticeBoundary(
                    event.sourceId(), "published_at", boundary);
            case NOTICE_EXPIRES_AT_DUE -> existsCurrentNoticeBoundary(
                    event.sourceId(), "expires_at", boundary);
            case GALLERY_PUBLISHED_AT_DUE -> Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                    """
                    SELECT EXISTS (
                        SELECT 1
                        FROM gallery_items
                        WHERE id = ?
                          AND status = 'published'
                          AND published_at = ?
                    )
                    """,
                    Boolean.class,
                    event.sourceId(),
                    offset(boundary)));
            case CONTENT_CHANGED -> true;
        };
    }

    private boolean existsCurrentNoticeBoundary(
            UUID sourceId, String boundaryColumn, Instant boundary) {
        var sql = """
                SELECT EXISTS (
                    SELECT 1
                    FROM notices
                    WHERE id = ?
                      AND status = 'published'
                      AND %s = ?
                )
                """.formatted(boundaryColumn);
        return Boolean.TRUE.equals(jdbcTemplate.queryForObject(
                sql, Boolean.class, sourceId, offset(boundary)));
    }

    private Optional<PublicationEventStatus> findExpiredProcessing(Instant now) {
        return first(jdbcTemplate.query(
                """
                SELECT *
                FROM publishing_outbox
                WHERE state = 'PROCESSING'
                  AND lease_until <= ?
                ORDER BY lease_until ASC, id ASC
                FOR UPDATE SKIP LOCKED
                LIMIT 1
                """,
                this::mapStatus,
                offset(now)));
    }

    private Optional<PublicationEventStatus> findDueRetry(Instant now) {
        return first(jdbcTemplate.query(
                """
                SELECT *
                FROM publishing_outbox
                WHERE state = 'RETRY_WAIT'
                  AND next_attempt_at <= ?
                ORDER BY next_attempt_at ASC, id ASC
                FOR UPDATE SKIP LOCKED
                LIMIT 1
                """,
                this::mapStatus,
                offset(now)));
    }

    private Optional<PublicationEventStatus> findDuePending(Instant now) {
        return first(jdbcTemplate.query(
                """
                SELECT *
                FROM publishing_outbox
                WHERE state = 'PENDING'
                  AND available_at <= ?
                ORDER BY available_at ASC, id ASC
                FOR UPDATE SKIP LOCKED
                LIMIT 1
                """,
                this::mapStatus,
                offset(now)));
    }

    private Optional<PublicationEventStatus> findOwnedActiveProcessing(
            UUID eventId, long generation, String owner, Instant now) {
        return first(jdbcTemplate.query(
                """
                SELECT *
                FROM publishing_outbox
                WHERE id = ?
                  AND state = 'PROCESSING'
                  AND publish_generation = ?
                  AND claim_owner = ?
                  AND claimed_at <= ?
                  AND lease_until > ?
                FOR UPDATE
                """,
                this::mapStatus,
                eventId,
                generation,
                owner,
                offset(now),
                offset(now)));
    }

    private Optional<PublicationEventStatus> findOwnedActiveProcessingByGeneration(
            long generation, String owner, Instant now) {
        return first(jdbcTemplate.query(
                """
                SELECT *
                FROM publishing_outbox
                WHERE state = 'PROCESSING'
                  AND publish_generation = ?
                  AND claim_owner = ?
                  AND claimed_at <= ?
                  AND lease_until > ?
                FOR UPDATE
                """,
                this::mapStatus,
                generation,
                owner,
                offset(now),
                offset(now)));
    }

    private Optional<PublicationEventStatus> findById(UUID eventId) {
        return first(jdbcTemplate.query(
                "SELECT * FROM publishing_outbox WHERE id = ?", this::mapStatus, eventId));
    }

    private PublicationEventStatus requireStatus(UUID eventId) {
        return findById(eventId)
                .orElseThrow(() -> new IllegalStateException("Publication event disappeared"));
    }

    private PublicationEventStatus mapStatus(ResultSet resultSet, int rowNumber)
            throws SQLException {
        return new PublicationEventStatus(
                resultSet.getObject("id", UUID.class),
                PublicationEventKind.valueOf(resultSet.getString("kind")),
                PublicationSourceType.valueOf(resultSet.getString("source_type")),
                resultSet.getObject("source_id", UUID.class),
                resultSet.getLong("content_revision"),
                instant(resultSet, "available_at"),
                nullableInstant(resultSet, "expected_boundary_at"),
                instant(resultSet, "created_at"),
                PublicationState.valueOf(resultSet.getString("state")),
                nullableLong(resultSet, "publish_generation"),
                resultSet.getInt("attempt_count"),
                resultSet.getString("claim_owner"),
                nullableInstant(resultSet, "claimed_at"),
                nullableInstant(resultSet, "lease_until"),
                nullableInstant(resultSet, "next_attempt_at"),
                nullableInstant(resultSet, "completed_at"),
                nullableResultCode(resultSet.getString("last_result_code")),
                nullableLong(resultSet, "coalesced_into_generation"));
    }

    private String validateOwner(String owner) {
        Objects.requireNonNull(owner, "owner");
        var codePointCount = owner.codePointCount(0, owner.length());
        var containsControl = owner.codePoints().anyMatch(Character::isISOControl);
        if (owner.isBlank()
                || !owner.equals(owner.strip())
                || codePointCount > MAX_OWNER_CODE_POINTS
                || containsControl) {
            throw new IllegalArgumentException("Invalid publication claim owner");
        }
        return owner;
    }

    private long validateGeneration(long generation) {
        if (generation <= 0) {
            throw new IllegalArgumentException("Positive publish generation is required");
        }
        return generation;
    }

    private Instant normalize(Instant value) {
        return Objects.requireNonNull(value, "now").truncatedTo(ChronoUnit.MICROS);
    }

    private Instant leaseUntil(Instant now, Duration duration) {
        Objects.requireNonNull(duration, "leaseDuration");
        if (duration.isZero() || duration.isNegative()) {
            throw new IllegalArgumentException("Positive lease duration is required");
        }
        return now.plus(duration).truncatedTo(ChronoUnit.MICROS);
    }

    private Duration retryDelay(int attemptCount) {
        return switch (attemptCount) {
            case 1 -> Duration.ofMinutes(1);
            case 2 -> Duration.ofMinutes(5);
            case 3 -> Duration.ofMinutes(15);
            default -> throw new IllegalStateException("No retry delay for attempt " + attemptCount);
        };
    }

    private OffsetDateTime offset(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    private Instant instant(ResultSet resultSet, String column) throws SQLException {
        return resultSet.getObject(column, OffsetDateTime.class).toInstant();
    }

    private Instant nullableInstant(ResultSet resultSet, String column) throws SQLException {
        var value = resultSet.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private Long nullableLong(ResultSet resultSet, String column) throws SQLException {
        var value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private PublicationResultCode nullableResultCode(String value) {
        return value == null ? null : PublicationResultCode.valueOf(value);
    }

    private Optional<PublicationEventStatus> first(List<PublicationEventStatus> events) {
        return events.stream().findFirst();
    }
}
