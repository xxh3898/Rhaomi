package kr.co.rhaomi.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.OffsetDateTime;
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
class NoticeDatabaseContractTests {

    private static final String ADMIN_EMAIL = "notice.database@example.com";
    private static final String ADMIN_PASSWORD = "local-notice-database-password-123!";

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
    void should_createV3NoticeSchemaAndNamedConstraints_when_flywayMigrates() {
        var versions = jdbcTemplate.queryForList(
                "SELECT version FROM flyway_schema_history WHERE success = TRUE ORDER BY installed_rank",
                String.class);
        var columns = jdbcTemplate.queryForList(
                        """
                        SELECT column_name
                        FROM information_schema.columns
                        WHERE table_schema = 'public' AND table_name = 'notices'
                        """,
                        String.class)
                .stream()
                .collect(Collectors.toSet());
        var constraints = jdbcTemplate.queryForList(
                        """
                        SELECT conname
                        FROM pg_constraint
                        WHERE conrelid = 'notices'::regclass
                        """,
                        String.class)
                .stream()
                .collect(Collectors.toSet());

        assertTrue(versions.containsAll(Set.of("1", "2", "3")));
        assertEquals(
                Set.of(
                        "id",
                        "status",
                        "title",
                        "slug",
                        "summary",
                        "body_markdown",
                        "pinned",
                        "published_at",
                        "expires_at",
                        "created_at",
                        "updated_at",
                        "created_by",
                        "updated_by"),
                columns);
        assertTrue(constraints.containsAll(Set.of(
                "pk_notices",
                "uk_notices_slug",
                "ck_notices_status",
                "ck_notices_title_not_blank",
                "ck_notices_slug_format",
                "ck_notices_published_fields",
                "ck_notices_window",
                "fk_notices_created_by",
                "fk_notices_updated_by")));
    }

    @Test
    void should_applyDraftAndPinnedDefaults_when_databaseInsertOmitsDefaults() {
        var id = UUID.randomUUID();

        jdbcTemplate.update(
                """
                INSERT INTO notices (
                    id, title, slug, created_by, updated_by
                ) VALUES (?, '휴무 안내', 'holiday-notice', ?, ?)
                """,
                id,
                admin.getId(),
                admin.getId());

        assertEquals("draft", jdbcTemplate.queryForObject(
                "SELECT status FROM notices WHERE id = ?", String.class, id));
        assertEquals(false, jdbcTemplate.queryForObject(
                "SELECT pinned FROM notices WHERE id = ?", Boolean.class, id));
        assertTrue(jdbcTemplate.queryForObject(
                "SELECT created_at = updated_at FROM notices WHERE id = ?", Boolean.class, id));
    }

    @Test
    void should_enforceNoticeIntegrity_when_applicationValidationIsBypassed() {
        insertNotice(
                UUID.randomUUID(),
                "draft",
                "첫 공지",
                "first-notice",
                null,
                null,
                admin.getId());

        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertNotice(
                        UUID.randomUUID(),
                        "draft",
                        "중복 공지",
                        "first-notice",
                        null,
                        null,
                        admin.getId()));
        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertNotice(
                        UUID.randomUUID(),
                        "invalid",
                        "상태 오류",
                        "invalid-status",
                        null,
                        null,
                        admin.getId()));
        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertNotice(
                        UUID.randomUUID(),
                        "draft",
                        "   ",
                        "blank-title",
                        null,
                        null,
                        admin.getId()));
        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertNotice(
                        UUID.randomUUID(),
                        "draft",
                        "slug 오류",
                        "Invalid-Slug",
                        null,
                        null,
                        admin.getId()));
        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertNotice(
                        UUID.randomUUID(),
                        "draft",
                        "actor 오류",
                        "invalid-actor",
                        null,
                        null,
                        UUID.randomUUID()));
    }

    @Test
    void should_enforcePublishedFieldsAndNoticeWindow_when_applicationValidationIsBypassed() {
        var publishedAt = OffsetDateTime.parse("2026-09-01T00:00:00Z");

        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertNotice(
                        UUID.randomUUID(),
                        "published",
                        "본문 없음",
                        "missing-body",
                        null,
                        publishedAt,
                        admin.getId()));
        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertNotice(
                        UUID.randomUUID(),
                        "published",
                        "게시 시각 없음",
                        "missing-published-at",
                        "게시 본문",
                        null,
                        admin.getId()));
        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertNoticeWithWindow(
                        UUID.randomUUID(),
                        "draft",
                        "시작 없음",
                        "expires-without-start",
                        null,
                        OffsetDateTime.parse("2026-09-02T00:00:00Z")));
        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertNoticeWithWindow(
                        UUID.randomUUID(),
                        "archived",
                        "같은 종료",
                        "equal-window",
                        publishedAt,
                        publishedAt));
        assertThrows(
                DataIntegrityViolationException.class,
                () -> insertNoticeWithWindow(
                        UUID.randomUUID(),
                        "draft",
                        "역전 종료",
                        "reversed-window",
                        publishedAt,
                        publishedAt.minusSeconds(1)));
    }

    private void insertNotice(
            UUID id,
            String status,
            String title,
            String slug,
            String bodyMarkdown,
            OffsetDateTime publishedAt,
            UUID actorId) {
        jdbcTemplate.update(
                """
                INSERT INTO notices (
                    id, status, title, slug, summary, body_markdown, pinned,
                    published_at, expires_at, created_at, updated_at, created_by, updated_by
                ) VALUES (?, ?, ?, ?, NULL, ?, FALSE, ?, NULL,
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?)
                """,
                id,
                status,
                title,
                slug,
                bodyMarkdown,
                publishedAt,
                actorId,
                actorId);
    }

    private void insertNoticeWithWindow(
            UUID id,
            String status,
            String title,
            String slug,
            OffsetDateTime publishedAt,
            OffsetDateTime expiresAt) {
        jdbcTemplate.update(
                """
                INSERT INTO notices (
                    id, status, title, slug, summary, body_markdown, pinned,
                    published_at, expires_at, created_at, updated_at, created_by, updated_by
                ) VALUES (?, ?, ?, ?, NULL, NULL, FALSE, ?, ?,
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?)
                """,
                id,
                status,
                title,
                slug,
                publishedAt,
                expiresAt,
                admin.getId(),
                admin.getId());
    }

    private void clearFixtures() {
        jdbcTemplate.update("DELETE FROM notices");
        adminUserRepository.findByEmail(ADMIN_EMAIL).ifPresent(adminUserRepository::delete);
        adminUserRepository.flush();
    }
}
