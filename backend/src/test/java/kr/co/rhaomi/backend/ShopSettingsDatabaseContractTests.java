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
    void should_createV4ShopSettingsSchemaWithExactTypesAndNamedConstraints_when_flywayMigrates() {
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

        assertTrue(versions.containsAll(Set.of("1", "2", "3", "4")));
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
                "fk_shop_settings_created_by",
                "fk_shop_settings_updated_by")));
        assertEquals(
                Map.of(
                        "opening_time", new TemporalType("time without time zone", 0),
                        "closing_time", new TemporalType("time without time zone", 0),
                        "created_at", new TemporalType("timestamp with time zone", 6),
                        "updated_at", new TemporalType("timestamp with time zone", 6)),
                temporalTypes);
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

    private void clearFixtures() {
        jdbcTemplate.update("DELETE FROM shop_settings");
        adminUserRepository.findByEmail(ADMIN_EMAIL).ifPresent(adminUserRepository::delete);
        adminUserRepository.flush();
    }

    private record TemporalType(String dataType, int precision) {}
}
