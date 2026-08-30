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
class PublicationDatabaseContractTests {

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
    void should_createV8SchemaWithExactColumnsConstraintsAndIndexes_when_flywayMigrates() {
        var versions = jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = TRUE ORDER BY installed_rank",
                String.class);
        var revisionColumns = columns("content_revision_state");
        var outboxColumns = columns("publishing_outbox");
        var revisionNonNullableColumns = nonNullableColumns("content_revision_state");
        var outboxNonNullableColumns = nonNullableColumns("publishing_outbox");
        var constraints = jdbcTemplate.queryForList(
                        """
                        SELECT conname
                        FROM pg_constraint
                        WHERE conrelid IN (
                            'content_revision_state'::regclass,
                            'publishing_outbox'::regclass
                        )
                        """,
                        String.class)
                .stream()
                .collect(Collectors.toSet());
        var timestampPrecisions = jdbcTemplate.query(
                        """
                        SELECT column_name, datetime_precision
                        FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND table_name = 'publishing_outbox'
                          AND column_name IN (
                              'available_at', 'expected_boundary_at', 'created_at'
                          )
                        """,
                        (resultSet, rowNumber) -> Map.entry(
                                resultSet.getString("column_name"),
                                resultSet.getInt("datetime_precision")))
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        var indexes = Set.copyOf(jdbcTemplate.queryForList(
                """
                SELECT indexname
                FROM pg_indexes
                WHERE schemaname = 'public' AND tablename = 'publishing_outbox'
                """,
                String.class));

        assertTrue(versions.contains("8"));
        assertEquals(Set.of("singleton_key", "content_revision"), revisionColumns);
        assertEquals(Set.of("singleton_key", "content_revision"), revisionNonNullableColumns);
        assertEquals(
                Set.of(
                        "id",
                        "kind",
                        "source_type",
                        "source_id",
                        "content_revision",
                        "available_at",
                        "expected_boundary_at",
                        "created_at"),
                outboxColumns);
        assertEquals(
                Set.of(
                        "id",
                        "kind",
                        "source_type",
                        "source_id",
                        "content_revision",
                        "available_at",
                        "created_at"),
                outboxNonNullableColumns);
        assertTrue(constraints.containsAll(Set.of(
                "pk_content_revision_state",
                "ck_content_revision_state_singleton",
                "ck_content_revision_state_revision",
                "pk_publishing_outbox",
                "ck_publishing_outbox_kind",
                "ck_publishing_outbox_source_type",
                "ck_publishing_outbox_revision",
                "ck_publishing_outbox_boundary",
                "ck_publishing_outbox_source_kind")));
        assertEquals(
                Map.of("available_at", 6, "expected_boundary_at", 6, "created_at", 6),
                timestampPrecisions);
        assertTrue(indexes.containsAll(Set.of(
                "ix_publishing_outbox_available",
                "ix_publishing_outbox_source",
                "ix_publishing_outbox_content_revision")));
        assertNull(jdbcTemplate.queryForObject(
                """
                SELECT column_default
                FROM information_schema.columns
                WHERE table_schema = 'public'
                  AND table_name = 'content_revision_state'
                  AND column_name = 'content_revision'
                """,
                String.class));
        assertEquals(0L, currentRevision());
    }

    @Test
    void should_enforceSingletonAndNonNegativeRevision_when_databaseIsWrittenDirectly() {
        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        "INSERT INTO content_revision_state (singleton_key, content_revision) VALUES (2, 0)"));
        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        "UPDATE content_revision_state SET content_revision = -1 WHERE singleton_key = 1"));

        assertEquals(0L, currentRevision());
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM content_revision_state", Integer.class));
    }

    @Test
    void should_enforceTypedEventAndBoundaryContracts_when_databaseIsWrittenDirectly() {
        var now = OffsetDateTime.now(ZoneOffset.UTC);
        var sourceId = UUID.randomUUID();
        insertEvent("CONTENT_CHANGED", "BREED", sourceId, 1, now, null);
        insertEvent(
                "NOTICE_PUBLISHED_AT_DUE", "NOTICE", sourceId, 2, now.plusDays(1), now.plusDays(1));

        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertEvent("UNKNOWN", "BREED", sourceId, 3, now, null));
        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertEvent("CONTENT_CHANGED", "UNKNOWN", sourceId, 3, now, null));
        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertEvent("CONTENT_CHANGED", "BREED", sourceId, 0, now, null));
        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertEvent("CONTENT_CHANGED", "BREED", sourceId, 3, now, now));
        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertEvent("NOTICE_EXPIRES_AT_DUE", "NOTICE", sourceId, 3, now, null));
        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertEvent(
                        "NOTICE_EXPIRES_AT_DUE",
                        "NOTICE",
                        sourceId,
                        3,
                        now,
                        now.plusSeconds(1)));
        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertEvent(
                        "GALLERY_PUBLISHED_AT_DUE",
                        "NOTICE",
                        sourceId,
                        3,
                        now,
                        now));

        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM publishing_outbox", Integer.class));
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM publishing_outbox WHERE created_at IS NOT NULL",
                Integer.class));
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

    private void insertEvent(
            String kind,
            String sourceType,
            UUID sourceId,
            long revision,
            OffsetDateTime availableAt,
            OffsetDateTime expectedBoundaryAt) {
        jdbcTemplate.update(
                """
                INSERT INTO publishing_outbox (
                    id, kind, source_type, source_id, content_revision,
                    available_at, expected_boundary_at
                ) VALUES (?, ?, ?, ?, ?, ?, ?)
                """,
                UUID.randomUUID(),
                kind,
                sourceType,
                sourceId,
                revision,
                availableAt,
                expectedBoundaryAt);
    }

    private long currentRevision() {
        return jdbcTemplate.queryForObject(
                "SELECT content_revision FROM content_revision_state WHERE singleton_key = 1",
                Long.class);
    }

    private void clearPublicationState() {
        jdbcTemplate.update("DELETE FROM publishing_outbox");
        jdbcTemplate.update(
                "UPDATE content_revision_state SET content_revision = 0 WHERE singleton_key = 1");
    }
}
