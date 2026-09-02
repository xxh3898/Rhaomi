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
class AdminWebAuthnMigrationContractTests {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void should_migrateExistingV1ThroughV9DatabaseToV10_when_webauthnMigrationIsApplied() {
        var schema = schemaName();
        try {
            var throughV9 = flyway(schema, MigrationVersion.fromVersion("9"));
            assertEquals(9, throughV9.migrate().migrationsExecuted);
            assertNull(jdbcTemplate.queryForObject(
                    "SELECT to_regclass(?)",
                    String.class,
                    schema + ".admin_webauthn_credentials"));

            var throughV10 = flyway(schema, MigrationVersion.fromVersion("10"));
            assertEquals(1, throughV10.migrate().migrationsExecuted);
            assertNotNull(jdbcTemplate.queryForObject(
                    "SELECT to_regclass(?)",
                    String.class,
                    schema + ".admin_webauthn_credentials"));
            assertNotNull(jdbcTemplate.queryForObject(
                    "SELECT to_regclass(?)",
                    String.class,
                    schema + ".admin_recovery_codes"));
            assertEquals("10", latestVersion(schema));
            assertEquals(0, secretColumnCount(schema));
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void should_migrateCleanDatabaseFromV1ThroughV10_when_allMigrationsAreApplied() {
        var schema = schemaName();
        try {
            var flyway = flyway(schema, MigrationVersion.fromVersion("10"));

            assertEquals(10, flyway.migrate().migrationsExecuted);
            assertEquals(10, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + schema
                            + ".flyway_schema_history WHERE success = TRUE AND version IS NOT NULL",
                    Integer.class));
            assertEquals("10", latestVersion(schema));
            assertEquals(0, secretColumnCount(schema));
        } finally {
            dropSchema(schema);
        }
    }

    private int secretColumnCount(String schema) {
        return jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = ?
                  AND table_name IN ('admin_webauthn_credentials', 'admin_recovery_codes')
                  AND (column_name LIKE '%private%'
                    OR column_name IN ('recovery_code', 'code_plaintext'))
                """,
                Integer.class,
                schema);
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
        return "admin_webauthn_migration_" + UUID.randomUUID().toString().replace("-", "");
    }

    private void dropSchema(String schema) {
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
}
