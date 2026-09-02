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
class GalleryMigrationContractTests {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void should_migrateExistingV1ThroughV5DatabaseToV6_when_galleryMigrationIsApplied() {
        var schema = schemaName();
        try {
            var throughV5 = flyway(schema, MigrationVersion.fromVersion("5"));
            assertEquals(5, throughV5.migrate().migrationsExecuted);
            assertNull(jdbcTemplate.queryForObject(
                    "SELECT to_regclass(?)", String.class, schema + ".gallery_items"));

            var throughV6 = flyway(schema, MigrationVersion.fromVersion("6"));
            assertEquals(1, throughV6.migrate().migrationsExecuted);
            assertNotNull(jdbcTemplate.queryForObject(
                    "SELECT to_regclass(?)", String.class, schema + ".gallery_items"));
            assertEquals("6", jdbcTemplate.queryForObject(
                    "SELECT version FROM " + schema
                            + ".flyway_schema_history WHERE success = TRUE ORDER BY installed_rank DESC LIMIT 1",
                    String.class));
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void should_migrateCleanDatabaseFromV1ThroughV6_when_allMigrationsAreApplied() {
        var schema = schemaName();
        try {
            var flyway = flyway(schema, MigrationVersion.fromVersion("6"));

            assertEquals(6, flyway.migrate().migrationsExecuted);
            assertNotNull(jdbcTemplate.queryForObject(
                    "SELECT to_regclass(?)", String.class, schema + ".gallery_items"));
            assertEquals(6, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + schema
                            + ".flyway_schema_history WHERE success = TRUE AND version IS NOT NULL",
                    Integer.class));
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

    private String schemaName() {
        return "gallery_migration_" + UUID.randomUUID().toString().replace("-", "");
    }

    private void dropSchema(String schema) {
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
}
