package kr.co.rhaomi.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import javax.sql.DataSource;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class PublicationClaimMigrationContractTests {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void should_migrateExistingV1ThroughV8DatabaseToV9_when_claimStateMigrationIsApplied() {
        var schema = schemaName();
        try {
            var throughV8 = flyway(schema, MigrationVersion.fromVersion("8"));
            assertEquals(8, throughV8.migrate().migrationsExecuted);
            assertNull(jdbcTemplate.queryForObject(
                    "SELECT to_regclass(?)", String.class, schema + ".publish_generation_state"));
            assertEquals(0, jdbcTemplate.queryForObject(
                    """
                    SELECT COUNT(*)
                    FROM information_schema.columns
                    WHERE table_schema = ?
                      AND table_name = 'publishing_outbox'
                      AND column_name = 'state'
                    """,
                    Integer.class,
                    schema));

            var eventId = UUID.randomUUID();
            jdbcTemplate.update(
                    """
                    INSERT INTO %s.publishing_outbox (
                        id, kind, source_type, source_id, content_revision,
                        available_at, expected_boundary_at
                    ) VALUES (?, 'CONTENT_CHANGED', 'BREED', ?, 1, ?, NULL)
                    """.formatted(schema),
                    eventId,
                    UUID.randomUUID(),
                    OffsetDateTime.now(ZoneOffset.UTC));

            var throughV9 = flyway(schema, MigrationVersion.fromVersion("9"));
            assertEquals(1, throughV9.migrate().migrationsExecuted);
            assertNotNull(jdbcTemplate.queryForObject(
                    "SELECT to_regclass(?)", String.class, schema + ".publish_generation_state"));
            assertEquals(0L, jdbcTemplate.queryForObject(
                    "SELECT publish_generation FROM " + schema
                            + ".publish_generation_state WHERE singleton_key = 1",
                    Long.class));
            assertEquals("PENDING", jdbcTemplate.queryForObject(
                    "SELECT state FROM " + schema + ".publishing_outbox WHERE id = ?",
                    String.class,
                    eventId));
            assertEquals(0, jdbcTemplate.queryForObject(
                    "SELECT attempt_count FROM " + schema + ".publishing_outbox WHERE id = ?",
                    Integer.class,
                    eventId));
            assertNull(jdbcTemplate.queryForObject(
                    "SELECT publish_generation FROM " + schema
                            + ".publishing_outbox WHERE id = ?",
                    Long.class,
                    eventId));
            assertEquals("9", latestVersion(schema));
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void should_migrateCleanDatabaseFromV1ThroughV9_when_allMigrationsAreApplied() {
        var schema = schemaName();
        try {
            var flyway = flyway(schema, MigrationVersion.fromVersion("9"));

            assertEquals(9, flyway.migrate().migrationsExecuted);
            assertNotNull(jdbcTemplate.queryForObject(
                    "SELECT to_regclass(?)", String.class, schema + ".publish_generation_state"));
            assertNotNull(jdbcTemplate.queryForObject(
                    "SELECT to_regclass(?)", String.class, schema + ".publishing_outbox"));
            assertEquals(9, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + schema
                            + ".flyway_schema_history WHERE success = TRUE AND version IS NOT NULL",
                    Integer.class));
            assertEquals("9", latestVersion(schema));
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void should_validateJpaMappingsAgainstV9_when_applicationContextStarts() {
        assertEquals("9", jdbcTemplate.queryForObject(
                """
                SELECT version
                FROM flyway_schema_history
                WHERE success = TRUE
                  AND version IS NOT NULL
                ORDER BY installed_rank DESC
                LIMIT 1
                """,
                String.class));
    }

    private Flyway flyway(String schema, MigrationVersion target) {
        return Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .defaultSchema(schema)
                .schemas(schema)
                .createSchemas(true)
                .cleanDisabled(false)
                .target(target)
                .load();
    }

    private String latestVersion(String schema) {
        return jdbcTemplate.queryForObject(
                "SELECT version FROM " + schema
                        + ".flyway_schema_history WHERE success = TRUE ORDER BY installed_rank DESC LIMIT 1",
                String.class);
    }

    private String schemaName() {
        return "publication_claim_migration_" + UUID.randomUUID().toString().replace("-", "");
    }

    private void dropSchema(String schema) {
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
}
