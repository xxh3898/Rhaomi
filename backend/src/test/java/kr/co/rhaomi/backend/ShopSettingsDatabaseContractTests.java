package kr.co.rhaomi.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.LocalTime;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import kr.co.rhaomi.backend.admin.AdminUser;
import kr.co.rhaomi.backend.admin.AdminUserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class ShopSettingsDatabaseContractTests {

    private static final String ADMIN_EMAIL = "shop.database@example.com";
    private static final String ADMIN_PASSWORD = "local-shop-database-password-123!";
    private static final String HASH = "a".repeat(64);

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AdminUserRepository adminUserRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private AdminUser admin;

    @BeforeEach
    void setUpFixture() {
        clearFixtures();
        admin = adminUserRepository.saveAndFlush(
                AdminUser.create(ADMIN_EMAIL, passwordEncoder.encode(ADMIN_PASSWORD)));
    }

    @AfterEach
    void clearFixtureAfterTest() {
        clearFixtures();
    }

    @Test
    void should_createShopSettingsSchemaWithExactTypesAndNamedConstraints_when_flywayMigrates() {
        var versions = jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = TRUE ORDER BY installed_rank",
                String.class);
        var columns = jdbcTemplate.queryForList(
                        """
                        SELECT column_name
                        FROM information_schema.columns
                        WHERE table_schema = 'public' AND table_name = 'shop_settings'
                        """,
                        String.class)
                .stream()
                .collect(Collectors.toSet());
        var constraints = jdbcTemplate.queryForList(
                        """
                        SELECT conname
                        FROM pg_constraint
                        WHERE conrelid = 'shop_settings'::regclass
                        """,
                        String.class)
                .stream()
                .collect(Collectors.toSet());
        var temporalTypes = jdbcTemplate.query(
                        """
                        SELECT column_name, data_type, datetime_precision
                        FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND table_name = 'shop_settings'
                          AND column_name IN (
                              'opening_time', 'closing_time', 'created_at', 'updated_at'
                          )
                        """,
                        (resultSet, rowNumber) -> Map.entry(
                                resultSet.getString("column_name"),
                                new TemporalType(
                                        resultSet.getString("data_type"),
                                        resultSet.getInt("datetime_precision"))))
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        var mediaColumnTypes = jdbcTemplate.query(
                        """
                        SELECT column_name, data_type, character_maximum_length
                        FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND table_name = 'shop_settings'
                          AND column_name IN (
                              'hero_image_id', 'hero_image_alt_text',
                              'groomer_image_id', 'groomer_image_alt_text', 'og_image_id'
                          )
                        """,
                        (resultSet, rowNumber) -> Map.entry(
                                resultSet.getString("column_name"),
                                new ColumnType(
                                        resultSet.getString("data_type"),
                                        resultSet.getObject("character_maximum_length", Integer.class))))
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));
        var mediaForeignKeyDeleteActions = jdbcTemplate.query(
                        """
                        SELECT conname, confdeltype
                        FROM pg_constraint
                        WHERE conrelid = 'shop_settings'::regclass
                          AND conname IN (
                              'fk_shop_settings_hero_image',
                              'fk_shop_settings_groomer_image',
                              'fk_shop_settings_og_image'
                          )
                        """,
                        (resultSet, rowNumber) -> Map.entry(
                                resultSet.getString("conname"),
                                resultSet.getString("confdeltype")))
                .stream()
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue));

        assertTrue(versions.containsAll(Set.of("1", "2", "3", "4", "5", "6", "7")));
        assertEquals(
                Set.of(
                        "id",
                        "singleton_key",
                        "shop_name",
                        "region_label",
                        "business_type",
                        "phone",
                        "address",
                        "opening_time",
                        "closing_time",
                        "closed_weekday",
                        "parking_available",
                        "parking_note",
                        "hero_title",
                        "hero_description",
                        "groomer_name",
                        "groomer_intro",
                        "reservation_notice",
                        "hero_image_id",
                        "hero_image_alt_text",
                        "groomer_image_id",
                        "groomer_image_alt_text",
                        "og_image_id",
                        "instagram_url",
                        "naver_blog_url",
                        "naver_map_url",
                        "kakao_map_url",
                        "naver_talktalk_url",
                        "kakao_channel_url",
                        "created_at",
                        "updated_at",
                        "created_by",
                        "updated_by"),
                columns);
        assertTrue(constraints.containsAll(Set.of(
                "pk_shop_settings",
                "uk_shop_settings_singleton_key",
                "ck_shop_settings_singleton_key",
                "ck_shop_settings_shop_name_not_blank",
                "ck_shop_settings_region_label_not_blank",
                "ck_shop_settings_business_type_not_blank",
                "ck_shop_settings_phone_not_blank",
                "ck_shop_settings_address_not_blank",
                "ck_shop_settings_business_hours",
                "ck_shop_settings_closed_weekday",
                "fk_shop_settings_hero_image",
                "fk_shop_settings_groomer_image",
                "fk_shop_settings_og_image",
                "ck_shop_settings_hero_image_alt_pair",
                "ck_shop_settings_groomer_image_alt_pair",
                "fk_shop_settings_created_by",
                "fk_shop_settings_updated_by")));
        assertEquals(
                Map.of(
                        "opening_time", new TemporalType("time without time zone", 0),
                        "closing_time", new TemporalType("time without time zone", 0),
                        "created_at", new TemporalType("timestamp with time zone", 6),
                        "updated_at", new TemporalType("timestamp with time zone", 6)),
                temporalTypes);
        assertEquals(
                Map.of(
                        "hero_image_id", new ColumnType("uuid", null),
                        "hero_image_alt_text", new ColumnType("character varying", 300),
                        "groomer_image_id", new ColumnType("uuid", null),
                        "groomer_image_alt_text", new ColumnType("character varying", 300),
                        "og_image_id", new ColumnType("uuid", null)),
                mediaColumnTypes);
        assertEquals(
                Map.of(
                        "fk_shop_settings_hero_image", "r",
                        "fk_shop_settings_groomer_image", "r",
                        "fk_shop_settings_og_image", "r"),
                mediaForeignKeyDeleteActions);
    }

    @Test
    void should_rejectInvalidImageAltPairs_when_applicationValidationIsBypassed() {
        var mediaId = insertMedia("active");
        var invalidRelations = Set.of(
                new MediaRelations(mediaId, null, null, null, null),
                new MediaRelations(null, "Hero", null, null, null),
                new MediaRelations(mediaId, "\t\n ", null, null, null),
                new MediaRelations(null, null, mediaId, null, null),
                new MediaRelations(null, null, null, "프로필", null),
                new MediaRelations(null, null, mediaId, "\t\n ", null));

        for (var relations : invalidRelations) {
            assertThrows(
                    DataIntegrityViolationException.class,
                    () -> insertShopSettingsWithMedia(UUID.randomUUID(), relations),
                    relations.toString());
            assertEquals(0, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM shop_settings", Integer.class));
        }
    }

    @Test
    void should_allowOneAssetForEveryRoleAndRestrictHardDelete_when_relationsAreValid() {
        var mediaId = insertMedia("active");
        insertShopSettingsWithMedia(
                UUID.randomUUID(),
                new MediaRelations(mediaId, "Hero", mediaId, "프로필", mediaId));

        var relationIds = jdbcTemplate.queryForMap(
                """
                SELECT hero_image_id, groomer_image_id, og_image_id
                FROM shop_settings
                """);
        assertEquals(mediaId, relationIds.get("hero_image_id"));
        assertEquals(mediaId, relationIds.get("groomer_image_id"));
        assertEquals(mediaId, relationIds.get("og_image_id"));
        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update("DELETE FROM media_assets WHERE id = ?", mediaId));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM media_assets WHERE id = ?", Integer.class, mediaId));
    }

    @Test
    void should_enforceSingletonKeyAndOneRow_when_applicationValidationIsBypassed() {
        insertShopSettings(
                UUID.randomUUID(), true, requiredFields(), LocalTime.of(10, 0), LocalTime.of(19, 0),
                "MONDAY", admin.getId(), admin.getId());

        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertShopSettings(
                        UUID.randomUUID(),
                        true,
                        requiredFields(),
                        LocalTime.of(9, 0),
                        LocalTime.of(18, 0),
                        null,
                        admin.getId(),
                        admin.getId()));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM shop_settings", Integer.class));

        jdbcTemplate.update("DELETE FROM shop_settings");
        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertShopSettings(
                        UUID.randomUUID(),
                        false,
                        requiredFields(),
                        LocalTime.of(10, 0),
                        LocalTime.of(19, 0),
                        null,
                        admin.getId(),
                        admin.getId()));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM shop_settings", Integer.class));
    }

    @Test
    void should_applyTrueSingletonAndAuditDefaults_when_databaseInsertOmitsDefaults() {
        var id = UUID.randomUUID();

        jdbcTemplate.update(
                """
                INSERT INTO shop_settings (
                    id, shop_name, region_label, business_type, phone, address,
                    opening_time, closing_time, parking_available, created_by, updated_by
                ) VALUES (?, '라오미펫', '서울', '애견미용', '02-1234-5678', '서울시 어딘가',
                          '10:00', '19:00', FALSE, ?, ?)
                """,
                id,
                admin.getId(),
                admin.getId());

        assertEquals(true, jdbcTemplate.queryForObject(
                "SELECT singleton_key FROM shop_settings WHERE id = ?", Boolean.class, id));
        assertEquals(true, jdbcTemplate.queryForObject(
                "SELECT created_at = updated_at FROM shop_settings WHERE id = ?", Boolean.class, id));
    }

    @Test
    void should_rejectWhitespaceOnlyRequiredText_when_directInsertBypassesApplication() {
        for (var field : Set.of("shop_name", "region_label", "business_type", "phone", "address")) {
            var values = requiredFields();
            values.put(field, "\t\n ");

            assertThrows(
                    DataIntegrityViolationException.class,
                    () -> insertShopSettings(
                            UUID.randomUUID(),
                            true,
                            values,
                            LocalTime.of(10, 0),
                            LocalTime.of(19, 0),
                            null,
                            admin.getId(),
                            admin.getId()),
                    field);
            assertEquals(0, jdbcTemplate.queryForObject(
                    "SELECT COUNT(*) FROM shop_settings", Integer.class), field);
        }
    }

    @Test
    void should_rejectInvalidHoursWeekdayAndActors_when_directInsertBypassesApplication() {
        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertShopSettings(
                        UUID.randomUUID(),
                        true,
                        requiredFields(),
                        LocalTime.of(10, 0),
                        LocalTime.of(10, 0),
                        null,
                        admin.getId(),
                        admin.getId()));
        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertShopSettings(
                        UUID.randomUUID(),
                        true,
                        requiredFields(),
                        LocalTime.of(19, 0),
                        LocalTime.of(10, 0),
                        null,
                        admin.getId(),
                        admin.getId()));
        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertShopSettings(
                        UUID.randomUUID(),
                        true,
                        requiredFields(),
                        LocalTime.of(10, 0),
                        LocalTime.of(19, 0),
                        "HOLIDAY",
                        admin.getId(),
                        admin.getId()));
        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertShopSettings(
                        UUID.randomUUID(),
                        true,
                        requiredFields(),
                        LocalTime.of(10, 0),
                        LocalTime.of(19, 0),
                        null,
                        UUID.randomUUID(),
                        admin.getId()));
        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertShopSettings(
                        UUID.randomUUID(),
                        true,
                        requiredFields(),
                        LocalTime.of(10, 0),
                        LocalTime.of(19, 0),
                        null,
                        admin.getId(),
                        UUID.randomUUID()));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM shop_settings", Integer.class));
    }

    private Map<String, String> requiredFields() {
        return new java.util.HashMap<>(Map.of(
                "shop_name", "라오미펫",
                "region_label", "서울",
                "business_type", "애견미용",
                "phone", "02-1234-5678",
                "address", "서울시 어딘가"));
    }

    private void insertShopSettings(
            UUID id,
            boolean singletonKey,
            Map<String, String> requiredFields,
            LocalTime openingTime,
            LocalTime closingTime,
            String closedWeekday,
            UUID createdBy,
            UUID updatedBy) {
        jdbcTemplate.update(
                """
                INSERT INTO shop_settings (
                    id, singleton_key, shop_name, region_label, business_type, phone, address,
                    opening_time, closing_time, closed_weekday, parking_available,
                    created_at, updated_at, created_by, updated_by
                ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, FALSE,
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?)
                """,
                id,
                singletonKey,
                requiredFields.get("shop_name"),
                requiredFields.get("region_label"),
                requiredFields.get("business_type"),
                requiredFields.get("phone"),
                requiredFields.get("address"),
                openingTime,
                closingTime,
                closedWeekday,
                createdBy,
                updatedBy);
    }

    private void insertShopSettingsWithMedia(UUID id, MediaRelations relations) {
        jdbcTemplate.update(
                """
                INSERT INTO shop_settings (
                    id, shop_name, region_label, business_type, phone, address,
                    opening_time, closing_time, parking_available,
                    hero_image_id, hero_image_alt_text,
                    groomer_image_id, groomer_image_alt_text, og_image_id,
                    created_by, updated_by
                ) VALUES (?, '라오미펫', '서울', '애견미용', '02-1234-5678', '서울시 어딘가',
                          '10:00', '19:00', FALSE, ?, ?, ?, ?, ?, ?, ?)
                """,
                id,
                relations.heroImageId(),
                relations.heroImageAltText(),
                relations.groomerImageId(),
                relations.groomerImageAltText(),
                relations.ogImageId(),
                admin.getId(),
                admin.getId());
    }

    private UUID insertMedia(String status) {
        var id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO media_assets (
                    id, status, source_content_type, content_type, file_extension,
                    storage_key, source_byte_size, byte_size, width, height, sha256,
                    created_by, updated_by
                ) VALUES (?, ?, 'image/jpeg', 'image/jpeg', 'jpg', ?,
                          100, 100, 4, 3, ?, ?, ?)
                """,
                id,
                status,
                "masters/" + id.toString().substring(0, 2) + "/" + id + ".jpg",
                HASH,
                admin.getId(),
                admin.getId());
        return id;
    }

    private void clearFixtures() {
        jdbcTemplate.update("DELETE FROM shop_settings");
        jdbcTemplate.update("DELETE FROM media_assets");
        adminUserRepository.findByEmail(ADMIN_EMAIL).ifPresent(adminUserRepository::delete);
        adminUserRepository.flush();
    }

    private record TemporalType(String dataType, int precision) {}

    private record ColumnType(String dataType, Integer maximumLength) {}

    private record MediaRelations(
            UUID heroImageId,
            String heroImageAltText,
            UUID groomerImageId,
            String groomerImageAltText,
            UUID ogImageId) {}
}
