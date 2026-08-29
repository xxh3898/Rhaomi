package kr.co.rhaomi.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.UUID;
import java.util.regex.Pattern;
import kr.co.rhaomi.backend.admin.AdminUser;
import kr.co.rhaomi.backend.admin.AdminUserRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class NoticeAdminApiContractTests {

    private static final String ADMIN_A_EMAIL = "notice.admin.a@example.com";
    private static final String ADMIN_B_EMAIL = "notice.admin.b@example.com";
    private static final String ADMIN_PASSWORD = "local-notice-password-123!";
    private static final Pattern CSRF_TOKEN_PATTERN =
            Pattern.compile("\\\"token\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern ID_PATTERN =
            Pattern.compile("\\\"id\\\"\\s*:\\s*\\\"([0-9a-f-]{36})\\\"");

    @LocalServerPort
    private int port;

    @Autowired
    private AdminUserRepository adminUserRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    private AdminUser adminA;
    private AdminUser adminB;

    @BeforeEach
    void setUpFixtures() {
        clearFixtures();
        adminA = createAdmin(ADMIN_A_EMAIL);
        adminB = createAdmin(ADMIN_B_EMAIL);
    }

    @AfterEach
    void clearFixturesAfterTest() {
        clearFixtures();
    }

    @Test
    void should_rejectAnonymousMissingCsrfAndPublicRead_when_noticeEndpointsAreProtected()
            throws Exception {
        var anonymous = newClient();

        assertEquals(401, get(anonymous, "/api/admin/notices").statusCode());
        assertEquals(403, postJson(
                        anonymous,
                        "/api/admin/notices",
                        noticeCreate("휴무 안내", "holiday-notice", null, null, false, null, null),
                        null)
                .statusCode());
        var anonymousCsrf = fetchCsrf(anonymous);
        assertEquals(401, postJson(
                        anonymous,
                        "/api/admin/notices",
                        noticeCreate("휴무 안내", "holiday-notice", null, null, false, null, null),
                        anonymousCsrf)
                .statusCode());

        var authenticated = login(ADMIN_A_EMAIL);
        assertEquals(403, postJson(
                        authenticated.client(),
                        "/api/admin/notices",
                        noticeCreate("휴무 안내", "holiday-notice", null, null, false, null, null),
                        null)
                .statusCode());
        assertFalse(isSuccess(get(anonymous, "/api/notices").statusCode()));
        assertFalse(isSuccess(get(authenticated.client(), "/api/notices").statusCode()));
    }

    @Test
    void should_createDraftWithNormalizedFieldsAndServerAudit_when_authenticatedWithCsrf()
            throws Exception {
        var session = login(ADMIN_A_EMAIL);

        var omittedPinned = postJson(
                session.client(),
                "/api/admin/notices",
                """
                {
                  "title": "  여름 휴무 안내  ",
                  "slug": "summer-holiday",
                  "summary": "   ",
                  "bodyMarkdown": "   ",
                  "publishedAt": "2030-08-29T09:00:00+09:00",
                  "expiresAt": "2030-08-30T09:00:00+09:00"
                }
                """,
                session.csrfToken());
        var nullPinned = postJson(
                session.client(),
                "/api/admin/notices",
                noticeCreate("임시 공지", "temporary-notice", null, null, null, null, null),
                session.csrfToken());

        assertEquals(201, omittedPinned.statusCode());
        assertEquals("/api/admin/notices/" + extractId(omittedPinned.body()),
                omittedPinned.headers().firstValue("Location").orElseThrow());
        assertTrue(omittedPinned.body().contains("\"status\":\"draft\""));
        assertTrue(omittedPinned.body().contains("\"title\":\"여름 휴무 안내\""));
        assertTrue(omittedPinned.body().contains("\"summary\":null"));
        assertTrue(omittedPinned.body().contains("\"bodyMarkdown\":null"));
        assertTrue(omittedPinned.body().contains("\"pinned\":false"));
        assertTrue(omittedPinned.body().contains("\"publishedAt\":\"2030-08-29T00:00:00Z\""));
        assertTrue(omittedPinned.body().contains("\"createdBy\":\"" + adminA.getId() + "\""));
        assertTrue(omittedPinned.body().contains("\"updatedBy\":\"" + adminA.getId() + "\""));
        assertEquals(201, nullPinned.statusCode());
        assertTrue(nullPinned.body().contains("\"pinned\":false"));

        var id = extractId(omittedPinned.body());
        assertEquals("draft", jdbcTemplate.queryForObject(
                "SELECT status FROM notices WHERE id = ?", String.class, id));
        assertEquals(adminA.getId(), jdbcTemplate.queryForObject(
                "SELECT created_by FROM notices WHERE id = ?", UUID.class, id));
        assertNotNull(jdbcTemplate.queryForObject(
                "SELECT created_at FROM notices WHERE id = ?", OffsetDateTime.class, id));
    }

    @Test
    void should_normalizeTimestampPrecisionAndRoundTrip_when_createAndUpdateUseSubMicrosecondInput()
            throws Exception {
        var session = login(ADMIN_A_EMAIL);

        var collapsedWindow = createRawNotice(
                session,
                noticeCreate(
                        "100ns 기간",
                        "collapsed-window",
                        null,
                        null,
                        false,
                        "2030-01-01T00:00:00.123456700Z",
                        "2030-01-01T00:00:00.123456800Z"));

        assertEquals(422, collapsedWindow.statusCode());
        assertTrue(collapsedWindow.body().contains("NOTICE_WINDOW_INVALID"));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notices WHERE slug = 'collapsed-window'", Integer.class));

        var created = createRawNotice(
                session,
                noticeCreate(
                        "1µs 기간",
                        "one-microsecond-window",
                        null,
                        null,
                        false,
                        "2030-01-01T00:00:00.123456789Z",
                        "2030-01-01T00:00:00.123457789Z"));

        assertEquals(201, created.statusCode());
        assertTrue(created.body().contains("\"publishedAt\":\"2030-01-01T00:00:00.123456Z\""));
        assertTrue(created.body().contains("\"expiresAt\":\"2030-01-01T00:00:00.123457Z\""));

        var id = extractId(created.body());
        var createdAgain = get(session.client(), "/api/admin/notices/" + id);
        var createdState = readState(id);

        assertEquals(200, createdAgain.statusCode());
        assertTrue(createdAgain.body().contains("\"publishedAt\":\"2030-01-01T00:00:00.123456Z\""));
        assertTrue(createdAgain.body().contains("\"expiresAt\":\"2030-01-01T00:00:00.123457Z\""));
        assertEquals(OffsetDateTime.parse("2030-01-01T00:00:00.123456Z"), createdState.publishedAt());
        assertEquals(OffsetDateTime.parse("2030-01-01T00:00:00.123457Z"), createdState.expiresAt());

        var collapsedUpdate = putJson(
                session.client(),
                "/api/admin/notices/" + id,
                noticeUpdate(
                        "published",
                        "100ns 수정",
                        null,
                        "게시 본문",
                        true,
                        "2031-02-03T04:05:06.234567100Z",
                        "2031-02-03T04:05:06.234567200Z"),
                session.csrfToken());

        assertEquals(422, collapsedUpdate.statusCode());
        assertTrue(collapsedUpdate.body().contains("NOTICE_WINDOW_INVALID"));
        assertEquals(createdState, readState(id));

        var updated = putJson(
                session.client(),
                "/api/admin/notices/" + id,
                noticeUpdate(
                        "published",
                        "microsecond 수정",
                        null,
                        "게시 본문",
                        false,
                        "2031-02-03T04:05:06.654321999Z",
                        "2031-02-03T04:05:06.654322999Z"),
                session.csrfToken());
        var updatedAgain = get(session.client(), "/api/admin/notices/" + id);
        var updatedState = readState(id);

        assertEquals(200, updated.statusCode());
        assertEquals(200, updatedAgain.statusCode());
        for (var response : new String[] {updated.body(), updatedAgain.body()}) {
            assertTrue(response.contains("\"publishedAt\":\"2031-02-03T04:05:06.654321Z\""));
            assertTrue(response.contains("\"expiresAt\":\"2031-02-03T04:05:06.654322Z\""));
        }
        assertEquals(OffsetDateTime.parse("2031-02-03T04:05:06.654321Z"), updatedState.publishedAt());
        assertEquals(OffsetDateTime.parse("2031-02-03T04:05:06.654322Z"), updatedState.expiresAt());
    }

    @Test
    void should_listWithExplicitNullLastAndAllTieBreakers_when_noticeStatesDiffer() throws Exception {
        var session = login(ADMIN_A_EMAIL);
        var tieA = UUID.fromString("00000000-0000-0000-0000-000000000001");
        var tieB = UUID.fromString("00000000-0000-0000-0000-000000000002");

        insertNotice(
                UUID.fromString("00000000-0000-0000-0000-000000000010"),
                "pinned-old",
                true,
                OffsetDateTime.parse("2026-01-01T00:00:00Z"),
                OffsetDateTime.parse("2026-01-01T00:00:00Z"));
        insertNotice(
                UUID.fromString("00000000-0000-0000-0000-000000000020"),
                "published-new",
                false,
                OffsetDateTime.parse("2026-03-01T00:00:00Z"),
                OffsetDateTime.parse("2026-01-01T00:00:00Z"));
        insertNotice(
                UUID.fromString("00000000-0000-0000-0000-000000000030"),
                "published-old",
                false,
                OffsetDateTime.parse("2026-02-01T00:00:00Z"),
                OffsetDateTime.parse("2026-03-01T00:00:00Z"));
        insertNotice(
                UUID.fromString("00000000-0000-0000-0000-000000000040"),
                "null-new",
                false,
                null,
                OffsetDateTime.parse("2026-04-02T00:00:00Z"));
        insertNotice(tieB, "null-tie-b", false, null, OffsetDateTime.parse("2026-04-01T00:00:00Z"));
        insertNotice(tieA, "null-tie-a", false, null, OffsetDateTime.parse("2026-04-01T00:00:00Z"));

        var list = get(session.client(), "/api/admin/notices");

        assertEquals(200, list.statusCode());
        assertOrdered(
                list.body(),
                "pinned-old",
                "published-new",
                "published-old",
                "null-new",
                "null-tie-a",
                "null-tie-b");
    }

    @Test
    void should_preserveSlugAndCreatedAudit_when_secondAdminUpdatesNotice() throws Exception {
        var creator = login(ADMIN_A_EMAIL);
        var created = createNotice(creator, "가을 영업 안내", "autumn-hours");
        var id = extractId(created.body());
        var beforeCreatedAt = jdbcTemplate.queryForObject(
                "SELECT created_at FROM notices WHERE id = ?", OffsetDateTime.class, id);
        var updater = login(ADMIN_B_EMAIL);

        var updated = putJson(
                updater.client(),
                "/api/admin/notices/" + id,
                noticeUpdate(
                        "published",
                        "  가을 영업시간 안내  ",
                        "운영시간 변경",
                        "## 안내\n변경된 시간을 확인해 주세요.",
                        true,
                        "2031-09-01T09:00:00+09:00",
                        "2031-10-01T09:00:00+09:00"),
                updater.csrfToken());
        var byId = get(updater.client(), "/api/admin/notices/" + id);

        assertEquals(200, updated.statusCode());
        assertEquals(200, byId.statusCode());
        assertTrue(updated.body().contains("\"status\":\"published\""));
        assertTrue(updated.body().contains("\"title\":\"가을 영업시간 안내\""));
        assertTrue(updated.body().contains("\"slug\":\"autumn-hours\""));
        assertTrue(updated.body().contains("\"createdBy\":\"" + adminA.getId() + "\""));
        assertTrue(updated.body().contains("\"updatedBy\":\"" + adminB.getId() + "\""));
        assertEquals("autumn-hours", jdbcTemplate.queryForObject(
                "SELECT slug FROM notices WHERE id = ?", String.class, id));
        assertEquals(beforeCreatedAt, jdbcTemplate.queryForObject(
                "SELECT created_at FROM notices WHERE id = ?", OffsetDateTime.class, id));
        assertEquals(adminA.getId(), jdbcTemplate.queryForObject(
                "SELECT created_by FROM notices WHERE id = ?", UUID.class, id));
        assertEquals(adminB.getId(), jdbcTemplate.queryForObject(
                "SELECT updated_by FROM notices WHERE id = ?", UUID.class, id));
    }

    @Test
    void should_rejectInvalidSlugDuplicateMassAssignmentAndMalformedTimestamp_when_creatingNotice()
            throws Exception {
        var session = login(ADMIN_A_EMAIL);

        assertEquals(400, createRawNotice(session, noticeCreate(
                        "공지", "Invalid-Slug", null, null, false, null, null))
                .statusCode());
        assertEquals(400, createRawNotice(session, noticeCreate(
                        "공지", " invalid-slug ", null, null, false, null, null))
                .statusCode());
        assertEquals(400, createRawNotice(session, noticeCreate(
                        "공지", "timestamp-error", null, null, false, "not-a-time", null))
                .statusCode());

        assertEquals(201, createNotice(session, "첫 공지", "unique-notice").statusCode());
        var duplicate = createNotice(session, "중복 공지", "unique-notice");
        assertEquals(409, duplicate.statusCode());
        assertTrue(duplicate.body().contains("SLUG_CONFLICT"));

        var injected = """
                {
                  "id": "%s",
                  "status": "published",
                  "title": "주입 공지",
                  "slug": "injected-notice",
                  "summary": null,
                  "bodyMarkdown": "본문",
                  "pinned": false,
                  "publishedAt": "2026-08-29T00:00:00Z",
                  "expiresAt": null,
                  "createdBy": "%s"
                }
                """.formatted(UUID.randomUUID(), adminB.getId());
        var injectionResponse = createRawNotice(session, injected);

        assertEquals(400, injectionResponse.statusCode());
        assertTrue(injectionResponse.body().contains("INVALID_REQUEST"));
        assertEquals(0, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notices WHERE slug = 'injected-notice'", Integer.class));
    }

    @Test
    void should_returnFixedInvalidRequestWithoutExceptionDetail_when_uuidPathIsMalformed()
            throws Exception {
        var session = login(ADMIN_A_EMAIL);

        for (var path : new String[] {
            "/api/admin/notices/not-a-uuid",
            "/api/admin/breeds/not-a-uuid",
            "/api/admin/services/not-a-uuid"
        }) {
            var response = get(session.client(), path);

            assertEquals(400, response.statusCode(), path + " " + response.body());
            assertTrue(response.body().contains("\"code\":\"INVALID_REQUEST\""), response.body());
            assertFalse(response.body().contains("not-a-uuid"), response.body());
            assertFalse(response.body().contains("MethodArgumentTypeMismatchException"), response.body());
            assertFalse(response.body().contains("java.lang"), response.body());
        }
    }

    @Test
    void should_enforceRequestLengthBoundaries_when_noticeTextReachesLimits() throws Exception {
        var session = login(ADMIN_A_EMAIL);
        var accepted = createRawNotice(
                session,
                noticeCreate(
                        "가".repeat(200),
                        "a".repeat(160),
                        "나".repeat(300),
                        "다".repeat(50_000),
                        false,
                        null,
                        null));

        assertEquals(201, accepted.statusCode());
        assertEquals(400, createRawNotice(session, noticeCreate(
                        "가".repeat(201), "title-too-long", null, null, false, null, null))
                .statusCode());
        assertEquals(400, createRawNotice(session, noticeCreate(
                        "slug 길이", "a".repeat(161), null, null, false, null, null))
                .statusCode());
        assertEquals(400, createRawNotice(session, noticeCreate(
                        "요약 길이", "summary-too-long", "나".repeat(301), null, false, null, null))
                .statusCode());
        assertEquals(400, createRawNotice(session, noticeCreate(
                        "본문 길이", "body-too-long", null, "다".repeat(50_001), false, null, null))
                .statusCode());
    }

    @Test
    void should_validatePublishedFinalStateAndPreserveEntireRow_when_validationFails()
            throws Exception {
        var session = login(ADMIN_A_EMAIL);
        var created = createNotice(session, "게시 검증", "publish-validation");
        var id = extractId(created.body());
        var before = readState(id);

        var missingFields = putJson(
                session.client(),
                "/api/admin/notices/" + id,
                noticeUpdate("published", "변경 제목", "변경 요약", null, true, null, null),
                session.csrfToken());

        assertEquals(422, missingFields.statusCode());
        assertTrue(missingFields.body().contains("PUBLISH_VALIDATION_FAILED"));
        assertEquals(before, readState(id));

        var partial = putJson(
                session.client(),
                "/api/admin/notices/" + id,
                "{\"status\":\"published\"}",
                session.csrfToken());
        assertEquals(400, partial.statusCode());
        assertEquals(before, readState(id));

        var futurePublished = putJson(
                session.client(),
                "/api/admin/notices/" + id,
                noticeUpdate(
                        "published",
                        "게시 검증",
                        null,
                        "게시 본문",
                        false,
                        "2035-01-01T00:00:00Z",
                        null),
                session.csrfToken());
        assertEquals(200, futurePublished.statusCode());
        assertTrue(futurePublished.body().contains("\"publishedAt\":\"2035-01-01T00:00:00Z\""));
    }

    @Test
    void should_rejectInvalidWindowForEveryStatusAndPreserveAudit_when_updateIsInvalid()
            throws Exception {
        var session = login(ADMIN_A_EMAIL);

        var expiresOnly = createRawNotice(
                session,
                noticeCreate(
                        "종료만 있는 공지",
                        "expires-only",
                        null,
                        null,
                        false,
                        null,
                        "2030-01-02T00:00:00Z"));
        assertEquals(422, expiresOnly.statusCode());
        assertTrue(expiresOnly.body().contains("NOTICE_WINDOW_INVALID"));

        var created = createNotice(session, "기간 검증", "window-validation");
        var id = extractId(created.body());
        var before = readState(id);

        for (var status : new String[] {"draft", "published", "archived"}) {
            var invalidWindow = putJson(
                    session.client(),
                    "/api/admin/notices/" + id,
                    noticeUpdate(
                            status,
                            "변경 시도",
                            "변경 요약",
                            "게시 본문",
                            true,
                            "2030-01-02T00:00:00Z",
                            "2030-01-02T00:00:00Z"),
                    session.csrfToken());

            assertEquals(422, invalidWindow.statusCode());
            assertTrue(invalidWindow.body().contains("NOTICE_WINDOW_INVALID"));
            assertEquals(before, readState(id));
        }

        var reversedWindow = putJson(
                session.client(),
                "/api/admin/notices/" + id,
                noticeUpdate(
                        "published",
                        "역전 기간",
                        "변경 요약",
                        "게시 본문",
                        true,
                        "2030-01-03T00:00:00Z",
                        "2030-01-02T23:59:59.999999Z"),
                session.csrfToken());

        assertEquals(422, reversedWindow.statusCode());
        assertTrue(reversedWindow.body().contains("NOTICE_WINDOW_INVALID"));
        assertEquals(before, readState(id));
    }

    @Test
    void should_rejectImmutableFieldsAndUnsupportedMutations_withoutChangingNotice() throws Exception {
        var session = login(ADMIN_A_EMAIL);
        var created = createNotice(session, "변경 금지", "immutable-notice");
        var id = extractId(created.body());
        var before = readState(id);
        var injectedUpdate = """
                {
                  "status": "draft",
                  "title": "변경 시도",
                  "slug": "renamed-notice",
                  "summary": null,
                  "bodyMarkdown": null,
                  "pinned": false,
                  "publishedAt": null,
                  "expiresAt": null,
                  "updatedAt": "2030-01-01T00:00:00Z"
                }
                """;

        var injection = putJson(
                session.client(), "/api/admin/notices/" + id, injectedUpdate, session.csrfToken());
        var patch = request(
                session.client(),
                "PATCH",
                "/api/admin/notices/" + id,
                noticeUpdate("archived", "변경 금지", null, null, false, null, null),
                session.csrfToken());
        var delete = request(
                session.client(), "DELETE", "/api/admin/notices/" + id, null, session.csrfToken());

        assertEquals(400, injection.statusCode());
        assertFalse(isSuccess(patch.statusCode()));
        assertFalse(isSuccess(delete.statusCode()));
        assertEquals(before, readState(id));
    }

    @Test
    void should_restoreArchivedNoticeToPublishedAndReturnNotFound_when_fullUpdateOrIdRequiresIt()
            throws Exception {
        var session = login(ADMIN_A_EMAIL);
        var missingId = UUID.randomUUID();

        assertEquals(404, get(session.client(), "/api/admin/notices/" + missingId).statusCode());
        assertEquals(404, putJson(
                        session.client(),
                        "/api/admin/notices/" + missingId,
                        noticeUpdate("draft", "없음", null, null, false, null, null),
                        session.csrfToken())
                .statusCode());

        var created = createNotice(session, "보존 공지", "archived-notice");
        var id = extractId(created.body());
        var archived = putJson(
                session.client(),
                "/api/admin/notices/" + id,
                noticeUpdate("archived", "보존 공지", null, null, false, null, null),
                session.csrfToken());
        var stillReadable = get(session.client(), "/api/admin/notices/" + id);
        var restored = putJson(
                session.client(),
                "/api/admin/notices/" + id,
                noticeUpdate(
                        "published",
                        "게시 복구 공지",
                        "복구 요약",
                        "게시 복구 본문",
                        true,
                        "2032-01-01T00:00:00.123456Z",
                        "2032-02-01T00:00:00.123457Z"),
                session.csrfToken());

        assertEquals(200, archived.statusCode());
        assertTrue(archived.body().contains("\"status\":\"archived\""));
        assertEquals(200, stillReadable.statusCode());
        assertTrue(stillReadable.body().contains("\"status\":\"archived\""));
        assertEquals(200, restored.statusCode());
        assertTrue(restored.body().contains("\"status\":\"published\""));
        assertTrue(restored.body().contains("\"bodyMarkdown\":\"게시 복구 본문\""));
        assertTrue(restored.body().contains("\"publishedAt\":\"2032-01-01T00:00:00.123456Z\""));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM notices WHERE id = ?", Integer.class, id));
    }

    private HttpResponse<String> createNotice(
            AuthenticatedSession session, String title, String slug) throws Exception {
        return createRawNotice(
                session, noticeCreate(title, slug, null, null, false, null, null));
    }

    private HttpResponse<String> createRawNotice(AuthenticatedSession session, String body)
            throws Exception {
        return postJson(session.client(), "/api/admin/notices", body, session.csrfToken());
    }

    private AdminUser createAdmin(String email) {
        return adminUserRepository.saveAndFlush(
                AdminUser.create(email, passwordEncoder.encode(ADMIN_PASSWORD)));
    }

    private void insertNotice(
            UUID id,
            String slug,
            boolean pinned,
            OffsetDateTime publishedAt,
            OffsetDateTime updatedAt) {
        jdbcTemplate.update(
                """
                INSERT INTO notices (
                    id, status, title, slug, summary, body_markdown, pinned,
                    published_at, expires_at, created_at, updated_at, created_by, updated_by
                ) VALUES (?, 'draft', ?, ?, NULL, NULL, ?, ?, NULL, ?, ?, ?, ?)
                """,
                id,
                slug,
                slug,
                pinned,
                publishedAt,
                updatedAt,
                updatedAt,
                adminA.getId(),
                adminA.getId());
    }

    private NoticeState readState(UUID id) {
        return jdbcTemplate.queryForObject(
                """
                SELECT status, title, slug, summary, body_markdown, pinned,
                       published_at, expires_at, created_at, updated_at, created_by, updated_by
                FROM notices
                WHERE id = ?
                """,
                (resultSet, rowNumber) -> new NoticeState(
                        resultSet.getString("status"),
                        resultSet.getString("title"),
                        resultSet.getString("slug"),
                        resultSet.getString("summary"),
                        resultSet.getString("body_markdown"),
                        resultSet.getBoolean("pinned"),
                        resultSet.getObject("published_at", OffsetDateTime.class),
                        resultSet.getObject("expires_at", OffsetDateTime.class),
                        resultSet.getObject("created_at", OffsetDateTime.class),
                        resultSet.getObject("updated_at", OffsetDateTime.class),
                        resultSet.getObject("created_by", UUID.class),
                        resultSet.getObject("updated_by", UUID.class)),
                id);
    }

    private void assertOrdered(String body, String... slugs) {
        var previous = -1;
        for (var slug : slugs) {
            var current = body.indexOf("\"slug\":\"" + slug + "\"");
            assertTrue(current > previous, body);
            previous = current;
        }
    }

    private void clearFixtures() {
        jdbcTemplate.update("DELETE FROM notices");
        adminUserRepository.findByEmail(ADMIN_A_EMAIL).ifPresent(adminUserRepository::delete);
        adminUserRepository.findByEmail(ADMIN_B_EMAIL).ifPresent(adminUserRepository::delete);
        adminUserRepository.flush();
    }

    private AuthenticatedSession login(String email) throws Exception {
        var client = newClient();
        var csrfToken = fetchCsrf(client);
        var login = postJson(client, "/api/admin/auth/login", loginBody(email), csrfToken);
        assertEquals(200, login.statusCode());
        return new AuthenticatedSession(client, csrfToken);
    }

    private TestClient newClient() {
        var cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        var httpClient = HttpClient.newBuilder()
                .cookieHandler(cookieManager)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        return new TestClient(httpClient, cookieManager);
    }

    private String fetchCsrf(TestClient client) throws Exception {
        var response = get(client, "/api/admin/auth/csrf");
        assertEquals(200, response.statusCode());
        var matcher = CSRF_TOKEN_PATTERN.matcher(response.body());
        assertTrue(matcher.find());
        return matcher.group(1);
    }

    private UUID extractId(String body) {
        var matcher = ID_PATTERN.matcher(body);
        assertTrue(matcher.find(), body);
        return UUID.fromString(matcher.group(1));
    }

    private HttpResponse<String> get(TestClient client, String path) throws Exception {
        return request(client, "GET", path, null, null);
    }

    private HttpResponse<String> postJson(TestClient client, String path, String body, String csrfToken)
            throws Exception {
        return request(client, "POST", path, body, csrfToken);
    }

    private HttpResponse<String> putJson(TestClient client, String path, String body, String csrfToken)
            throws Exception {
        return request(client, "PUT", path, body, csrfToken);
    }

    private HttpResponse<String> request(
            TestClient client, String method, String path, String body, String csrfToken) throws Exception {
        var bodyPublisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
        var builder = HttpRequest.newBuilder(uri(path)).method(method, bodyPublisher);
        if (body != null) {
            builder.header("Content-Type", "application/json");
        }
        if (csrfToken != null) {
            builder.header("X-CSRF-TOKEN", csrfToken);
        }
        return client.httpClient().send(
                builder.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private String loginBody(String email) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + ADMIN_PASSWORD + "\"}";
    }

    private String noticeCreate(
            String title,
            String slug,
            String summary,
            String bodyMarkdown,
            Boolean pinned,
            String publishedAt,
            String expiresAt) {
        return """
                {
                  "title": "%s",
                  "slug": "%s",
                  "summary": %s,
                  "bodyMarkdown": %s,
                  "pinned": %s,
                  "publishedAt": %s,
                  "expiresAt": %s
                }
                """.formatted(
                title,
                slug,
                jsonString(summary),
                jsonString(bodyMarkdown),
                pinned == null ? "null" : pinned,
                jsonString(publishedAt),
                jsonString(expiresAt));
    }

    private String noticeUpdate(
            String status,
            String title,
            String summary,
            String bodyMarkdown,
            boolean pinned,
            String publishedAt,
            String expiresAt) {
        return """
                {
                  "status": "%s",
                  "title": "%s",
                  "summary": %s,
                  "bodyMarkdown": %s,
                  "pinned": %s,
                  "publishedAt": %s,
                  "expiresAt": %s
                }
                """.formatted(
                status,
                title,
                jsonString(summary),
                jsonString(bodyMarkdown),
                pinned,
                jsonString(publishedAt),
                jsonString(expiresAt));
    }

    private String jsonString(String value) {
        if (value == null) {
            return "null";
        }
        var escaped = value.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\n", "\\n")
                .replace("\r", "\\r")
                .replace("\t", "\\t");
        return "\"" + escaped + "\"";
    }

    private boolean isSuccess(int status) {
        return status >= 200 && status < 300;
    }

    private record NoticeState(
            String status,
            String title,
            String slug,
            String summary,
            String bodyMarkdown,
            boolean pinned,
            OffsetDateTime publishedAt,
            OffsetDateTime expiresAt,
            OffsetDateTime createdAt,
            OffsetDateTime updatedAt,
            UUID createdBy,
            UUID updatedBy) {}

    private record TestClient(HttpClient httpClient, CookieManager cookieManager) {}

    private record AuthenticatedSession(TestClient client, String csrfToken) {}
}
