package kr.co.rhaomi.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class PublicationStateDatabaseContractTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void resetPublicationState() {
        clearPublicationState();
    }

    @AfterEach
    void resetPublicationStateAfterTest() {
        clearPublicationState();
    }

    @Test
    void should_createV9GenerationAndStateSchema_when_flywayMigrates() {
        var generationColumns = columns("publish_generation_state");
        var generationNonNullableColumns = nonNullableColumns("publish_generation_state");
        var outboxColumns = columns("publishing_outbox");
        var outboxNonNullableColumns = nonNullableColumns("publishing_outbox");
        var constraints = jdbcTemplate.queryForList(
                        """
                        SELECT conname
                        FROM pg_constraint
                        WHERE conrelid IN (
                            'publish_generation_state'::regclass,
                            'publishing_outbox'::regclass
                        )
                        """,
                        String.class)
                .stream()
                .collect(Collectors.toSet());
        var indexes = Set.copyOf(jdbcTemplate.queryForList(
                """
                SELECT indexname
                FROM pg_indexes
                WHERE schemaname = 'public'
                  AND tablename IN ('publish_generation_state', 'publishing_outbox')
                """,
                String.class));
        var timestampPrecisions = jdbcTemplate.query(
                        """
                        SELECT column_name, datetime_precision
                        FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND table_name = 'publishing_outbox'
                          AND column_name IN (
                              'claimed_at', 'lease_until', 'next_attempt_at', 'completed_at'
                          )
                        """,
                        (resultSet, rowNumber) -> Map.entry(
                                resultSet.getString("column_name"),
                                resultSet.getInt("datetime_precision")))
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        assertEquals(Set.of("singleton_key", "publish_generation"), generationColumns);
        assertEquals(generationColumns, generationNonNullableColumns);
        assertTrue(outboxColumns.containsAll(Set.of(
                "state",
                "publish_generation",
                "attempt_count",
                "claim_owner",
                "claimed_at",
                "lease_until",
                "next_attempt_at",
                "completed_at",
                "last_result_code",
                "coalesced_into_generation")));
        assertTrue(outboxNonNullableColumns.containsAll(Set.of("state", "attempt_count")));
        assertTrue(constraints.containsAll(Set.of(
                "pk_publish_generation_state",
                "ck_publish_generation_state_singleton",
                "ck_publish_generation_state_generation",
                "uk_publishing_outbox_publish_generation",
                "ck_publishing_outbox_state",
                "ck_publishing_outbox_publish_generation",
                "ck_publishing_outbox_attempt_count",
                "ck_publishing_outbox_claim_owner",
                "ck_publishing_outbox_result_code",
                "ck_publishing_outbox_coalesced_generation",
                "ck_publishing_outbox_state_shape",
                "fk_publishing_outbox_coalesced_generation")));
        assertTrue(indexes.containsAll(Set.of(
                "ix_publishing_outbox_state_available",
                "ix_publishing_outbox_state_next_attempt",
                "ix_publishing_outbox_state_lease",
                "ix_publishing_outbox_coalesced_generation")));
        assertEquals(
                Map.of(
                        "claimed_at", 6,
                        "lease_until", 6,
                        "next_attempt_at", 6,
                        "completed_at", 6),
                timestampPrecisions);
        assertTrue(columnDefault("publishing_outbox", "state").contains("PENDING"));
        assertEquals("0", columnDefault("publishing_outbox", "attempt_count"));
        assertNull(columnDefault("publish_generation_state", "publish_generation"));
        assertEquals(0L, currentGeneration());
    }

    @Test
    void should_preserveV8InsertWithPendingDefaults_when_stateColumnsAreOmitted() {
        var eventId = insertPendingEvent();

        var state = jdbcTemplate.queryForMap(
                """
                SELECT state, publish_generation, attempt_count, claim_owner,
                       claimed_at, lease_until, next_attempt_at, completed_at,
                       last_result_code, coalesced_into_generation
                FROM publishing_outbox
                WHERE id = ?
                """,
                eventId);

        assertEquals("PENDING", state.get("state"));
        assertNull(state.get("publish_generation"));
        assertEquals(0, ((Number) state.get("attempt_count")).intValue());
        assertNull(state.get("claim_owner"));
        assertNull(state.get("claimed_at"));
        assertNull(state.get("lease_until"));
        assertNull(state.get("next_attempt_at"));
        assertNull(state.get("completed_at"));
        assertNull(state.get("last_result_code"));
        assertNull(state.get("coalesced_into_generation"));
    }

    @Test
    void should_enforceGenerationSingletonAndNonNegativeValue_when_databaseIsWrittenDirectly() {
        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        "INSERT INTO publish_generation_state (singleton_key, publish_generation) VALUES (2, 0)"));
        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        "UPDATE publish_generation_state SET publish_generation = -1 WHERE singleton_key = 1"));

        assertEquals(0L, currentGeneration());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM publish_generation_state", Integer.class));
    }

    @Test
    void should_rejectUnknownStateResultOwnerAndAttempt_when_databaseIsWrittenDirectly() {
        var eventId = insertPendingEvent();
        var now = OffsetDateTime.now(ZoneOffset.UTC);

        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        "UPDATE publishing_outbox SET state = 'UNKNOWN' WHERE id = ?", eventId));
        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        "UPDATE publishing_outbox SET last_result_code = 'raw exception' WHERE id = ?",
                        eventId));
        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        """
                        UPDATE publishing_outbox
                        SET state = 'PROCESSING', publish_generation = 1, attempt_count = 1,
                            claim_owner = ?, claimed_at = ?, lease_until = ?
                        WHERE id = ?
                        """,
                        "x".repeat(129),
                        now,
                        now.plusMinutes(1),
                        eventId));
        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        "UPDATE publishing_outbox SET attempt_count = 5 WHERE id = ?", eventId));

        assertEquals("PENDING", stateOf(eventId));
    }

    @Test
    void should_enforceStateSpecificNullabilityAndAttemptRanges_when_databaseIsWrittenDirectly() {
        var eventId = insertPendingEvent();
        var now = OffsetDateTime.now(ZoneOffset.UTC);

        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        "UPDATE publishing_outbox SET publish_generation = 1 WHERE id = ?", eventId));
        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        """
                        UPDATE publishing_outbox
                        SET state = 'PROCESSING', publish_generation = 1, attempt_count = 1,
                            claimed_at = ?, lease_until = ?
                        WHERE id = ?
                        """,
                        now,
                        now.plusMinutes(1),
                        eventId));

        makeProcessing(eventId, 1, "publisher-1", 1, now);
        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        """
                        UPDATE publishing_outbox
                        SET state = 'RETRY_WAIT', claim_owner = NULL, lease_until = NULL,
                            next_attempt_at = ?, attempt_count = 4,
                            last_result_code = 'TRANSIENT_FAILURE'
                        WHERE id = ?
                        """,
                        now.plusMinutes(1),
                        eventId));
        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        """
                        UPDATE publishing_outbox
                        SET state = 'SUCCEEDED', claim_owner = NULL, lease_until = NULL,
                            last_result_code = 'SUCCESS'
                        WHERE id = ?
                        """,
                        eventId));
        assertEquals("PROCESSING", stateOf(eventId));
    }

    @Test
    void should_allowGenerationlessStaleNoop_when_pendingScheduledEventIsTerminalized() {
        var eventId = insertPendingEvent();
        var completedAt = OffsetDateTime.now(ZoneOffset.UTC);

        assertEquals(1, jdbcTemplate.update(
                """
                UPDATE publishing_outbox
                SET state = 'NOOP', completed_at = ?, last_result_code = 'STALE_TRIGGER'
                WHERE id = ?
                """,
                completedAt,
                eventId));
        var row = jdbcTemplate.queryForMap(
                """
                SELECT state, publish_generation, attempt_count, completed_at, last_result_code
                FROM publishing_outbox
                WHERE id = ?
                """,
                eventId);
        assertEquals("NOOP", row.get("state"));
        assertNull(row.get("publish_generation"));
        assertEquals(0, ((Number) row.get("attempt_count")).intValue());
        assertEquals("STALE_TRIGGER", row.get("last_result_code"));
    }

    @Test
    void should_enforceUniqueGenerationAndExistingHigherCoalesceTarget_when_databaseIsWrittenDirectly() {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var lowerId = insertPendingEvent();
        var higherId = insertPendingEvent();
        makeProcessing(lowerId, 1, "publisher-1", 1, now);

        assertThrows(
                DataIntegrityViolationException.class,
                () -> makeProcessing(higherId, 1, "publisher-1", 1, now));
        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        """
                        UPDATE publishing_outbox
                        SET state = 'COALESCED', claim_owner = NULL, lease_until = NULL,
                            completed_at = ?, last_result_code = 'COALESCED',
                            coalesced_into_generation = 2
                        WHERE id = ?
                        """,
                        now,
                        lowerId));

        makeProcessing(higherId, 2, "publisher-1", 1, now);
        assertEquals(1, jdbcTemplate.update(
                """
                UPDATE publishing_outbox
                SET state = 'COALESCED', claim_owner = NULL, lease_until = NULL,
                    completed_at = ?, last_result_code = 'COALESCED',
                    coalesced_into_generation = 2
                WHERE id = ?
                """,
                now,
                lowerId));
        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        """
                        UPDATE publishing_outbox
                        SET state = 'COALESCED', claim_owner = NULL, lease_until = NULL,
                            completed_at = ?, last_result_code = 'COALESCED',
                            coalesced_into_generation = 1
                        WHERE id = ?
                        """,
                        now,
                        higherId));
    }

    private Set<String> columns(String table) {
        return jdbcTemplate.queryForList(
                        """
                        SELECT column_name
                        FROM information_schema.columns
                        WHERE table_schema = 'public' AND table_name = ?
                        """,
                        String.class,
                        table)
                .stream()
                .collect(Collectors.toSet());
    }

    private Set<String> nonNullableColumns(String table) {
        return jdbcTemplate.queryForList(
                        """
                        SELECT column_name
                        FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND table_name = ?
                          AND is_nullable = 'NO'
                        """,
                        String.class,
                        table)
                .stream()
                .collect(Collectors.toSet());
    }

    private String columnDefault(String table, String column) {
        return jdbcTemplate.queryForObject(
                """
                SELECT column_default
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = ?
                  AND column_name = ?
                """,
                String.class,
                table,
                column);
    }

    private UUID insertPendingEvent() {
        var eventId = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO publishing_outbox (
                    id, kind, source_type, source_id, content_revision,
                    available_at, expected_boundary_at
                ) VALUES (?, 'CONTENT_CHANGED', 'BREED', ?, 1, CURRENT_TIMESTAMP, NULL)
                """,
                eventId,
                UUID.randomUUID());
        return eventId;
    }

    private void makeProcessing(
            UUID eventId,
            long generation,
            String owner,
            int attemptCount,
            OffsetDateTime claimedAt) {
        jdbcTemplate.update(
                """
                UPDATE publishing_outbox
                SET state = 'PROCESSING', publish_generation = ?, attempt_count = ?,
                    claim_owner = ?, claimed_at = ?, lease_until = ?
                WHERE id = ?
                """,
                generation,
                attemptCount,
                owner,
                claimedAt,
                claimedAt.plusMinutes(1),
                eventId);
    }

    private String stateOf(UUID eventId) {
        return jdbcTemplate.queryForObject(
                "SELECT state FROM publishing_outbox WHERE id = ?", String.class, eventId);
    }

    private long currentGeneration() {
        return jdbcTemplate.queryForObject(
                "SELECT publish_generation FROM publish_generation_state WHERE singleton_key = 1",
                Long.class);
    }

    private void clearPublicationState() {
        jdbcTemplate.update("DELETE FROM publishing_outbox");
        jdbcTemplate.update(
                "UPDATE publish_generation_state SET publish_generation = 0 WHERE singleton_key = 1");
    }
}
