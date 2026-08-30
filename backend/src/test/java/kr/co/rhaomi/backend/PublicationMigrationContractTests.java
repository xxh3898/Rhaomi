package kr.co.rhaomi.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

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
class PublicationMigrationContractTests {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void should_migrateExistingV1ThroughV7DatabaseToV8_when_publicationMigrationIsApplied() {
        var schema = schemaName();
        try {
            var throughV7 = flyway(schema, MigrationVersion.fromVersion("7"));
            assertEquals(7, throughV7.migrate().migrationsExecuted);
            assertNull(jdbcTemplate.queryForObject(
                    "SELECT to_regclass(?)", String.class, schema + ".content_revision_state"));
            assertNull(jdbcTemplate.queryForObject(
                    "SELECT to_regclass(?)", String.class, schema + ".publishing_outbox"));

            var throughV8 = flyway(schema, MigrationVersion.fromVersion("8"));
            assertEquals(1, throughV8.migrate().migrationsExecuted);
            assertNotNull(jdbcTemplate.queryForObject(
                    "SELECT to_regclass(?)", String.class, schema + ".content_revision_state"));
            assertNotNull(jdbcTemplate.queryForObject(
                    "SELECT to_regclass(?)", String.class, schema + ".publishing_outbox"));
            assertEquals(0L, jdbcTemplate.queryForObject(
                    "SELECT content_revision FROM " + schema
                            + ".content_revision_state WHERE singleton_key = 1",
                    Long.class));
            assertEquals("8", latestVersion(schema));
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void should_migrateCleanDatabaseFromV1ThroughV8_when_allMigrationsAreApplied() {
        var schema = schemaName();
        try {
            var flyway = flyway(schema, MigrationVersion.fromVersion("8"));

            assertEquals(8, flyway.migrate().migrationsExecuted);
            assertNotNull(jdbcTemplate.queryForObject(
                    "SELECT to_regclass(?)", String.class, schema + ".content_revision_state"));
            assertNotNull(jdbcTemplate.queryForObject(
                    "SELECT to_regclass(?)", String.class, schema + ".publishing_outbox"));
            assertEquals(8, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + schema
                            + ".flyway_schema_history WHERE success = TRUE AND version IS NOT NULL",
                    Integer.class));
            assertEquals("8", latestVersion(schema));
        } finally {
            dropSchema(schema);
        }
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
        return "publication_migration_" + UUID.randomUUID().toString().replace("-", "");
    }

    private void dropSchema(String schema) {
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
}
