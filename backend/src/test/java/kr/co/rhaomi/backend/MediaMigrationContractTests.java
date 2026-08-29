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
class MediaMigrationContractTests {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void should_migrateExistingV1ThroughV4DatabaseToV5_when_mediaMigrationIsApplied() {
        var schema = schemaName();
        try {
            var throughV4 = flyway(schema, MigrationVersion.fromVersion("4"));
            assertEquals(4, throughV4.migrate().migrationsExecuted);
            assertNull(jdbcTemplate.queryForObject(
                    "SELECT to_regclass(?)", String.class, schema + ".media_assets"));

            var throughV5 = flyway(schema, null);
            assertEquals(1, throughV5.migrate().migrationsExecuted);
            assertNotNull(jdbcTemplate.queryForObject(
                    "SELECT to_regclass(?)", String.class, schema + ".media_assets"));
            assertEquals("5", jdbcTemplate.queryForObject(
                    "SELECT version FROM " + schema
                            + ".flyway_schema_history WHERE success = TRUE ORDER BY installed_rank DESC LIMIT 1",
                    String.class));
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void should_migrateCleanDatabaseFromV1ThroughV5_when_allMigrationsAreApplied() {
        var schema = schemaName();
        try {
            var flyway = flyway(schema, null);

            assertEquals(5, flyway.migrate().migrationsExecuted);
            assertNotNull(jdbcTemplate.queryForObject(
                    "SELECT to_regclass(?)", String.class, schema + ".media_assets"));
            assertEquals(5, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + schema
                            + ".flyway_schema_history WHERE success = TRUE AND version IS NOT NULL",
                    Integer.class));
        } finally {
            dropSchema(schema);
        }
    }

    private Flyway flyway(String schema, MigrationVersion target) {
        var configuration = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .defaultSchema(schema)
                .schemas(schema)
                .createSchemas(true)
                .cleanDisabled(false);
        if (target != null) {
            configuration.target(target);
        }
        return configuration.load();
    }

    private String schemaName() {
        return "media_migration_" + UUID.randomUUID().toString().replace("-", "");
    }

    private void dropSchema(String schema) {
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
}
