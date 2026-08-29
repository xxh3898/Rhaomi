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
class ShopSettingsMediaMigrationContractTests {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    void should_preserveExistingShopRowWithNullMediaRelations_when_v7MigrationIsApplied() {
        var schema = schemaName();
        try {
            var throughV6 = flyway(schema, MigrationVersion.fromVersion("6"));
            assertEquals(6, throughV6.migrate().migrationsExecuted);
            var actorId = UUID.randomUUID();
            var settingsId = UUID.randomUUID();
            jdbcTemplate.update(
                    "INSERT INTO " + schema
                            + ".admin_users (id, email, password_hash) VALUES (?, ?, ?)",
                    actorId,
                    "shop-media-migration@example.com",
                    "test-password-hash");
            jdbcTemplate.update(
                    """
                    INSERT INTO %s.shop_settings (
                        id, shop_name, region_label, business_type, phone, address,
                        opening_time, closing_time, parking_available, created_by, updated_by
                    ) VALUES (?, '라오미펫', '서울', '애견미용', '02-1234-5678', '서울시 어딘가',
                              '10:00', '19:00', FALSE, ?, ?)
                    """.formatted(schema),
                    settingsId,
                    actorId,
                    actorId);

            var throughV7 = flyway(schema, MigrationVersion.fromVersion("7"));
            assertEquals(1, throughV7.migrate().migrationsExecuted);

            var row = jdbcTemplate.queryForMap(
                    """
                    SELECT hero_image_id, hero_image_alt_text,
                           groomer_image_id, groomer_image_alt_text, og_image_id
                    FROM %s.shop_settings
                    WHERE id = ?
                    """.formatted(schema),
                    settingsId);
            assertNull(row.get("hero_image_id"));
            assertNull(row.get("hero_image_alt_text"));
            assertNull(row.get("groomer_image_id"));
            assertNull(row.get("groomer_image_alt_text"));
            assertNull(row.get("og_image_id"));
            assertEquals("7", latestVersion(schema));
        } finally {
            dropSchema(schema);
        }
    }

    @Test
    void should_migrateCleanDatabaseFromV1ThroughV7_when_allMigrationsAreApplied() {
        var schema = schemaName();
        try {
            var flyway = flyway(schema, MigrationVersion.fromVersion("7"));

            assertEquals(7, flyway.migrate().migrationsExecuted);
            assertNotNull(jdbcTemplate.queryForObject(
                    "SELECT to_regclass(?)", String.class, schema + ".shop_settings"));
            assertEquals(7, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM " + schema
                            + ".flyway_schema_history WHERE success = TRUE AND version IS NOT NULL",
                    Integer.class));
            assertEquals("7", latestVersion(schema));
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
        return "shop_media_migration_" + UUID.randomUUID().toString().replace("-", "");
    }

    private void dropSchema(String schema) {
        jdbcTemplate.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
}
