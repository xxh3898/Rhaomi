package kr.co.rhaomi.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
class ContentDatabaseContractTests {

    private static final String ADMIN_EMAIL = "content.database@example.com";
    private static final String ADMIN_PASSWORD = "local-database-password-123!";

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
    void should_createV2SchemaAndNamedConstraints_when_flywayMigrates() {
        var versions = jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = TRUE ORDER BY installed_rank",
                String.class);
        var tables = Set.copyOf(jdbcTemplate.queryForList(
                """
                SELECT table_name
                FROM information_schema.tables
                WHERE table_schema = 'public' AND table_name IN ('breeds', 'services')
                """,
                String.class));
        var constraints = jdbcTemplate.queryForList(
                        """
                        SELECT conname
                        FROM pg_constraint
                        WHERE conrelid IN ('breeds'::regclass, 'services'::regclass)
                        """,
                        String.class)
                .stream()
                .collect(Collectors.toSet());

        assertTrue(versions.containsAll(Set.of("1", "2")));
        assertEquals(Set.of("breeds", "services"), tables);
        assertTrue(constraints.containsAll(Set.of(
                "pk_breeds",
                "ck_breeds_status",
                "ck_breeds_name_not_blank",
                "ck_breeds_slug_format",
                "ck_breeds_sort_order",
                "uk_breeds_slug",
                "fk_breeds_created_by",
                "fk_breeds_updated_by",
                "pk_services",
                "ck_services_status",
                "ck_services_name_not_blank",
                "ck_services_slug_format",
                "ck_services_sort_order",
                "ck_services_published_fields",
                "uk_services_slug",
                "fk_services_created_by",
                "fk_services_updated_by")));
    }

    @Test
    void should_enforceV2Constraints_when_applicationValidationIsBypassed() {
        insertBreed(UUID.randomUUID(), "draft", "푸들", "poodle", 100, admin.getId());

        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertBreed(UUID.randomUUID(), "draft", "다른 푸들", "poodle", 100, admin.getId()));
        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertBreed(UUID.randomUUID(), "invalid", "말티즈", "maltese", 100, admin.getId()));
        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertBreed(UUID.randomUUID(), "draft", "말티즈", "maltese", -1, admin.getId()));
        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertBreed(UUID.randomUUID(), "draft", "말티즈", "maltese", 100, UUID.randomUUID()));
        assertThrows(
                DataIntegrityViolationException.class,
                () -> jdbcTemplate.update(
                        """
                        INSERT INTO services (
                            id, status, name, slug, description, price_text, sort_order,
                            created_at, updated_at, created_by, updated_by
                        ) VALUES (?, 'published', '전체미용', 'full-grooming', NULL, NULL, 100,
                                  CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?)
                        """,
                        UUID.randomUUID(),
                        admin.getId(),
                        admin.getId()));
    }

    private void insertBreed(UUID id, String status, String name, String slug, int sortOrder, UUID actorId) {
        jdbcTemplate.update(
                """
                INSERT INTO breeds (
                    id, status, name, slug, description, sort_order,
                    created_at, updated_at, created_by, updated_by
                ) VALUES (?, ?, ?, ?, NULL, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?)
                """,
                id,
                status,
                name,
                slug,
                sortOrder,
                actorId,
                actorId);
    }

    private void clearFixtures() {
        jdbcTemplate.update("DELETE FROM services");
        jdbcTemplate.update("DELETE FROM breeds");
        adminUserRepository.findByEmail(ADMIN_EMAIL).ifPresent(adminUserRepository::delete);
        adminUserRepository.flush();
    }
}
