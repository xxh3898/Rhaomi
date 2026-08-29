package kr.co.rhaomi.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
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
import java.util.Map;
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
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class GalleryAdminApiContractTests {

    private static final String PATH = "/api/admin/gallery-items";
    private static final String ADMIN_A_EMAIL = "gallery.admin.a@example.com";
    private static final String ADMIN_B_EMAIL = "gallery.admin.b@example.com";
    private static final String ADMIN_PASSWORD = "local-gallery-password-123!";
    private static final String HASH = "c".repeat(64);
    private static final Pattern CSRF_TOKEN_PATTERN =
            Pattern.compile("\\\"token\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");

    @LocalServerPort
    private int port;

    @Autowired
    private AdminUserRepository adminUserRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private ObjectMapper objectMapper;

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
    void should_rejectAnonymousMissingCsrfAndPublicBuildRoutes_when_galleryEndpointsAreProtected()
            throws Exception {
        var anonymous = newClient();

        assertEquals(401, get(anonymous, PATH).statusCode());
        assertEquals(403, postJson(anonymous, PATH, "{}", null).statusCode());
        var anonymousCsrf = fetchCsrf(anonymous);
        assertEquals(401, postJson(anonymous, PATH, "{}", anonymousCsrf).statusCode());

        var authenticated = login(ADMIN_A_EMAIL);
        assertEquals(403, postJson(authenticated.client(), PATH, "{}", null).statusCode());
        assertEquals(403, putJson(
                        authenticated.client(),
                        PATH + "/" + UUID.randomUUID(),
                        updateBody(
                                "draft",
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                false,
                                100,
                                null,
                                null),
                        null)
                .statusCode());
        for (var path : new String[] {
            "/api/gallery-items", "/api/gallery-items/" + UUID.randomUUID(),
            "/api/build/gallery-items", "/api/build/gallery-items/" + UUID.randomUUID()
        }) {
            assertFalse(isSuccess(get(anonymous, path).statusCode()), path);
            assertFalse(isSuccess(get(authenticated.client(), path).statusCode()), path);
        }
    }

    @Test
    void should_createMinimalDraftWithDefaultsAndServerAudit_when_authenticatedWithCsrf()
            throws Exception {
        var session = login(ADMIN_A_EMAIL);

        var created = postJson(session.client(), PATH, "{}", session.csrfToken());
        var response = json(created);
        var id = UUID.fromString(response.get("id").asText());
        var fetched = get(session.client(), PATH + "/" + id);
        var listed = get(session.client(), PATH);

        assertEquals(201, created.statusCode());
        assertEquals(PATH + "/" + id, created.headers().firstValue("Location").orElseThrow());
        assertEquals("draft", response.get("status").asText());
        assertTrue(response.get("dogName").isNull());
        assertTrue(response.get("breedId").isNull());
        assertTrue(response.get("primaryServiceId").isNull());
        assertTrue(response.get("coverImageId").isNull());
        assertTrue(response.get("beforeImageId").isNull());
        assertTrue(response.get("afterImageId").isNull());
        assertTrue(response.get("summary").isNull());
        assertTrue(response.get("altText").isNull());
        assertFalse(response.get("featured").asBoolean());
        assertEquals(100, response.get("sortOrder").asInt());
        assertTrue(response.get("performedAt").isNull());
        assertTrue(response.get("publishedAt").isNull());
        assertEquals(adminA.getId().toString(), response.get("createdBy").asText());
        assertEquals(adminA.getId().toString(), response.get("updatedBy").asText());
        assertNotNull(response.get("createdAt").asText());
        assertNotNull(response.get("updatedAt").asText());
        assertEquals(200, fetched.statusCode());
        assertEquals(response, json(fetched));
        assertEquals(200, listed.statusCode());
        assertEquals(id.toString(), json(listed).get(0).get("id").asText());

        var explicitNullDefaults = postJson(
                session.client(),
                PATH,
                "{\"featured\":null,\"sortOrder\":null}",
                session.csrfToken());
        assertEquals(201, explicitNullDefaults.statusCode(), explicitNullDefaults.body());
        assertFalse(json(explicitNullDefaults).get("featured").asBoolean());
        assertEquals(100, json(explicitNullDefaults).get("sortOrder").asInt());
    }

    @Test
    void should_normalizeUnicodeTextAndTimestampPrecisionAndKeepExistingPrivateRelations_when_draftIsCreated()
            throws Exception {
        var session = login(ADMIN_A_EMAIL);
        var breed = insertBreed("archived", "gallery-create-archived-breed");
        var service = insertService("draft", "gallery-create-draft-service");
        var cover = insertMedia("archived");
        var before = insertMedia("archived");
        var after = insertMedia("active");

        var created = postJson(
                session.client(),
                PATH,
                createBody(
                        "\u00a0테스트 강아지\u2003",
                        breed,
                        service,
                        cover,
                        before,
                        after,
                        "\t\u2003",
                        "\u2003미용 전후 사진\u00a0",
                        true,
                        7,
                        "2030-01-01T00:00:00.123456789Z",
                        "2035-12-31T23:59:59.987654999Z"),
                session.csrfToken());
        var response = json(created);
        var fetched = get(session.client(), PATH + "/" + response.get("id").asText());

        assertEquals(201, created.statusCode(), created.body());
        assertEquals("draft", response.get("status").asText());
        assertEquals("테스트 강아지", response.get("dogName").asText());
        assertTrue(response.get("summary").isNull());
        assertEquals("미용 전후 사진", response.get("altText").asText());
        assertTrue(response.get("featured").asBoolean());
        assertEquals(7, response.get("sortOrder").asInt());
        assertEquals("2030-01-01T00:00:00.123456Z", response.get("performedAt").asText());
        assertEquals("2035-12-31T23:59:59.987654Z", response.get("publishedAt").asText());
        assertEquals(response, json(fetched));
        assertEquals(OffsetDateTime.parse("2030-01-01T00:00:00.123456Z"),
                jdbcTemplate.queryForObject(
                        "SELECT performed_at FROM gallery_items WHERE id = ?",
                        OffsetDateTime.class,
                        UUID.fromString(response.get("id").asText())));
    }

    @Test
    void should_rejectCreateMassAssignmentMalformedValuesAndLengthOverflow_withoutInsertingRow()
            throws Exception {
        var session = login(ADMIN_A_EMAIL);
        var invalidBodies = new String[] {
            "{\"status\":\"published\"}",
            "{\"id\":\"" + UUID.randomUUID() + "\"}",
            "{\"createdAt\":\"2030-01-01T00:00:00Z\"}",
            "{\"updatedBy\":\"" + adminB.getId() + "\"}",
            "{\"unknownField\":true}",
            "{\"sortOrder\":-1}",
            "{\"performedAt\":\"not-a-time\"}",
            "{\"publishedAt\":\"not-a-time\"}",
            "{",
            createBody("가".repeat(101), null, null, null, null, null, null, null,
                    false, 100, null, null),
            createBody(null, null, null, null, null, null, "나".repeat(1_001), null,
                    false, 100, null, null),
            createBody(null, null, null, null, null, null, null, "다".repeat(301),
                    false, 100, null, null)
        };

        for (var body : invalidBodies) {
            var response = postJson(session.client(), PATH, body, session.csrfToken());
            assertError(response, 400, "INVALID_REQUEST");
            assertEquals(0, rowCount());
        }

        var boundary = postJson(
                session.client(),
                PATH,
                createBody(
                        "가".repeat(100),
                        null,
                        null,
                        null,
                        null,
                        null,
                        "나".repeat(1_000),
                        "다".repeat(300),
                        false,
                        0,
                        null,
                        null),
                session.csrfToken());
        assertEquals(201, boundary.statusCode(), boundary.body());
    }

    @Test
    void should_rejectEveryMissingRelationAndAllowExistingNonPublicTargets_when_finalStateIsNotPublished()
            throws Exception {
        var session = login(ADMIN_A_EMAIL);
        var missing = UUID.randomUUID();
        var missingBodies = new String[] {
            createBody(null, missing, null, null, null, null, null, null, false, 100, null, null),
            createBody(null, null, missing, null, null, null, null, null, false, 100, null, null),
            createBody(null, null, null, missing, null, null, null, null, false, 100, null, null),
            createBody(null, null, null, null, missing, null, null, null, false, 100, null, null),
            createBody(null, null, null, null, null, missing, null, null, false, 100, null, null)
        };

        for (var body : missingBodies) {
            var response = postJson(session.client(), PATH, body, session.csrfToken());
            assertError(response, 422, "GALLERY_RELATION_INVALID");
            assertEquals(0, rowCount());
        }

        var breed = insertBreed("draft", "gallery-existing-draft-breed");
        var service = insertService("archived", "gallery-existing-archived-service");
        var cover = insertMedia("archived");
        var before = insertMedia("archived");
        var after = insertMedia("active");
        var created = postJson(
                session.client(),
                PATH,
                createBody(
                        null,
                        breed,
                        service,
                        cover,
                        before,
                        after,
                        null,
                        null,
                        false,
                        100,
                        null,
                        null),
                session.csrfToken());

        assertEquals(201, created.statusCode(), created.body());
        assertEquals("draft", json(created).get("status").asText());
    }

    @Test
    void should_publishWithScalarRelationsAndAllowCoverReuse_when_allTargetsArePublic()
            throws Exception {
        var session = login(ADMIN_A_EMAIL);
        var breed = insertBreed("published", "gallery-publish-breed");
        var service = insertService("published", "gallery-publish-service");
        var cover = insertMedia("active");
        var after = insertMedia("active");
        var created = postJson(session.client(), PATH, "{}", session.csrfToken());
        var id = UUID.fromString(json(created).get("id").asText());

        var coverIsBefore = putJson(
                session.client(),
                PATH + "/" + id,
                updateBody(
                        "published",
                        "초코",
                        breed,
                        service,
                        cover,
                        cover,
                        after,
                        "전체미용 사례",
                        "갈색 강아지의 전체미용 완료 사진",
                        true,
                        10,
                        "2031-02-03T04:05:06.123456789Z",
                        "2035-01-01T00:00:00.987654999Z"),
                session.csrfToken());
        var first = json(coverIsBefore);

        assertEquals(200, coverIsBefore.statusCode(), coverIsBefore.body());
        assertEquals("published", first.get("status").asText());
        assertEquals(cover.toString(), first.get("coverImageId").asText());
        assertEquals(cover.toString(), first.get("beforeImageId").asText());
        assertEquals(after.toString(), first.get("afterImageId").asText());
        assertEquals("2031-02-03T04:05:06.123456Z", first.get("performedAt").asText());
        assertEquals("2035-01-01T00:00:00.987654Z", first.get("publishedAt").asText());
        assertFalse(coverIsBefore.body().contains("storageKey"));
        assertFalse(coverIsBefore.body().contains("sha256"));
        assertTrue(coverIsBefore.body().contains("\"breedId\":\"" + breed + "\""));
        assertTrue(coverIsBefore.body().contains("\"primaryServiceId\":\"" + service + "\""));
        assertTrue(coverIsBefore.body().contains("\"coverImageId\":\"" + cover + "\""));

        var coverIsAfter = putJson(
                session.client(),
                PATH + "/" + id,
                updateBody(
                        "published",
                        "초코",
                        breed,
                        service,
                        after,
                        cover,
                        after,
                        "전체미용 사례",
                        "갈색 강아지의 전체미용 완료 사진",
                        false,
                        20,
                        null,
                        "2036-01-01T00:00:00Z"),
                session.csrfToken());

        assertEquals(200, coverIsAfter.statusCode(), coverIsAfter.body());
        assertEquals(after.toString(), json(coverIsAfter).get("coverImageId").asText());
        assertEquals(OffsetDateTime.parse("2036-01-01T00:00:00Z"),
                jdbcTemplate.queryForObject(
                        "SELECT published_at FROM gallery_items WHERE id = ?",
                        OffsetDateTime.class,
                        id));

        var secondCreated = postJson(session.client(), PATH, "{}", session.csrfToken());
        var secondId = UUID.fromString(json(secondCreated).get("id").asText());
        var sharedMedia = putJson(
                session.client(),
                PATH + "/" + secondId,
                validPublished(breed, service, cover, null, after),
                session.csrfToken());
        assertEquals(200, sharedMedia.statusCode(), sharedMedia.body());
    }

    @Test
    void should_rejectEveryNonPublicPublishTargetAndPreserveEntireRow_when_relationStatusIsInvalid()
            throws Exception {
        var session = login(ADMIN_A_EMAIL);
        var validBreed = insertBreed("published", "gallery-valid-breed");
        var draftBreed = insertBreed("draft", "gallery-draft-breed");
        var archivedBreed = insertBreed("archived", "gallery-archived-breed");
        var validService = insertService("published", "gallery-valid-service");
        var draftService = insertService("draft", "gallery-draft-service");
        var archivedService = insertService("archived", "gallery-archived-service");
        var activeCover = insertMedia("active");
        var activeBefore = insertMedia("active");
        var activeAfter = insertMedia("active");
        var archivedMedia = insertMedia("archived");
        var created = postJson(session.client(), PATH, "{}", session.csrfToken());
        var id = UUID.fromString(json(created).get("id").asText());
        var before = readState(id);
        var invalidBodies = new String[] {
            validPublished(draftBreed, validService, activeCover, activeBefore, activeAfter),
            validPublished(archivedBreed, validService, activeCover, activeBefore, activeAfter),
            validPublished(validBreed, draftService, activeCover, activeBefore, activeAfter),
            validPublished(validBreed, archivedService, activeCover, activeBefore, activeAfter),
            validPublished(validBreed, validService, archivedMedia, activeBefore, activeAfter),
            validPublished(validBreed, validService, activeCover, archivedMedia, activeAfter),
            validPublished(validBreed, validService, activeCover, activeBefore, archivedMedia)
        };

        for (var body : invalidBodies) {
            var response = putJson(session.client(), PATH + "/" + id, body, session.csrfToken());
            assertError(response, 422, "GALLERY_RELATION_INVALID");
            assertEquals(before, readState(id));
        }
    }

    @Test
    void should_rejectEveryPublishRequirementEqualBeforeAfterAndPartialPut_withoutChangingAudit()
            throws Exception {
        var session = login(ADMIN_A_EMAIL);
        var breed = insertBreed("published", "gallery-required-breed");
        var service = insertService("published", "gallery-required-service");
        var cover = insertMedia("active");
        var beforeMedia = insertMedia("active");
        var afterMedia = insertMedia("active");
        var created = postJson(session.client(), PATH, "{}", session.csrfToken());
        var id = UUID.fromString(json(created).get("id").asText());
        var before = readState(id);
        var invalidBodies = new String[] {
            updateBody("published", null, null, service, cover, beforeMedia, afterMedia,
                    null, "미용 완료 사진", false, 100, null, "2030-01-01T00:00:00Z"),
            updateBody("published", null, breed, null, cover, beforeMedia, afterMedia,
                    null, "미용 완료 사진", false, 100, null, "2030-01-01T00:00:00Z"),
            updateBody("published", null, breed, service, null, beforeMedia, afterMedia,
                    null, "미용 완료 사진", false, 100, null, "2030-01-01T00:00:00Z"),
            updateBody("published", null, breed, service, cover, beforeMedia, afterMedia,
                    null, null, false, 100, null, "2030-01-01T00:00:00Z"),
            updateBody("published", null, breed, service, cover, beforeMedia, afterMedia,
                    null, "\t\u2003", false, 100, null, "2030-01-01T00:00:00Z"),
            updateBody("published", null, breed, service, cover, beforeMedia, afterMedia,
                    null, "미용 완료 사진", false, 100, null, null),
            updateBody("draft", null, breed, service, cover, beforeMedia, beforeMedia,
                    null, null, false, 100, null, null)
        };

        for (var body : invalidBodies) {
            var response = putJson(session.client(), PATH + "/" + id, body, session.csrfToken());
            assertError(response, 422, "GALLERY_PUBLISH_INVALID");
            assertEquals(before, readState(id));
        }

        var fullBody = updateBody(
                "draft",
                null,
                breed,
                service,
                cover,
                beforeMedia,
                afterMedia,
                null,
                null,
                false,
                100,
                null,
                null);
        var invalidRequestBodies = new String[] {
            fullBody.replace("\"status\": \"draft\"", "\"status\": \"PUBLISHED\""),
            fullBody.replace("\"sortOrder\": 100", "\"sortOrder\": -1"),
            fullBody.replace("\"performedAt\": null", "\"performedAt\": \"not-a-time\""),
            fullBody.replace("\"publishedAt\": null", "\"publishedAt\": \"not-a-time\""),
            appendField(fullBody, "\"id\":\"" + UUID.randomUUID() + "\""),
            appendField(fullBody, "\"updatedAt\":\"2030-01-01T00:00:00Z\""),
            appendField(fullBody, "\"unknownField\":true"),
            updateBody("draft", "가".repeat(101), breed, service, cover, beforeMedia, afterMedia,
                    null, null, false, 100, null, null),
            updateBody("draft", null, breed, service, cover, beforeMedia, afterMedia,
                    "나".repeat(1_001), null, false, 100, null, null),
            updateBody("draft", null, breed, service, cover, beforeMedia, afterMedia,
                    null, "다".repeat(301), false, 100, null, null)
        };
        for (var body : invalidRequestBodies) {
            var response = putJson(session.client(), PATH + "/" + id, body, session.csrfToken());
            assertError(response, 400, "INVALID_REQUEST");
            assertEquals(before, readState(id));
        }

        var full = (ObjectNode) objectMapper.readTree(fullBody);
        for (var field : new String[] {
            "status",
            "dogName",
            "breedId",
            "primaryServiceId",
            "coverImageId",
            "beforeImageId",
            "afterImageId",
            "summary",
            "altText",
            "featured",
            "sortOrder",
            "performedAt",
            "publishedAt"
        }) {
            var partial = full.deepCopy();
            partial.remove(field);
            var response = putJson(
                    session.client(),
                    PATH + "/" + id,
                    objectMapper.writeValueAsString(partial),
                    session.csrfToken());
            assertError(response, 400, "INVALID_REQUEST");
            assertEquals(before, readState(id));
        }
    }

    @Test
    void should_preserveCreatedAuditAndChangeOnlyUpdateAudit_when_secondAdminReplacesItem()
            throws Exception {
        var creator = login(ADMIN_A_EMAIL);
        var breed = insertBreed("published", "gallery-audit-breed");
        var service = insertService("published", "gallery-audit-service");
        var cover = insertMedia("active");
        var created = postJson(creator.client(), PATH, "{}", creator.csrfToken());
        var id = UUID.fromString(json(created).get("id").asText());
        var before = readState(id);
        var updater = login(ADMIN_B_EMAIL);

        var updated = putJson(
                updater.client(),
                PATH + "/" + id,
                updateBody(
                        "published",
                        "보리",
                        breed,
                        service,
                        cover,
                        null,
                        null,
                        "부분미용 사례",
                        "흰 강아지의 부분미용 완료 사진",
                        false,
                        5,
                        null,
                        "2032-01-01T00:00:00Z"),
                updater.csrfToken());
        var after = readState(id);

        assertEquals(200, updated.statusCode(), updated.body());
        assertEquals(adminA.getId().toString(), json(updated).get("createdBy").asText());
        assertEquals(adminB.getId().toString(), json(updated).get("updatedBy").asText());
        assertEquals(before.get("created_at"), after.get("created_at"));
        assertEquals(before.get("created_by"), after.get("created_by"));
        assertNotEquals(before.get("updated_at"), after.get("updated_at"));
        assertEquals(adminB.getId(), after.get("updated_by"));
    }

    @Test
    void should_keepPublishedGalleryUnchangedWhenTargetsBecomePrivateAndRestoreArchivedItem_when_targetsExist()
            throws Exception {
        var session = login(ADMIN_A_EMAIL);
        var breed = insertBreed("published", "gallery-cascade-breed");
        var service = insertService("published", "gallery-cascade-service");
        var cover = insertMedia("active");
        var created = postJson(session.client(), PATH, "{}", session.csrfToken());
        var id = UUID.fromString(json(created).get("id").asText());
        var publishedBody = updateBody(
                "published",
                "라미",
                breed,
                service,
                cover,
                null,
                null,
                null,
                "라미의 미용 완료 사진",
                false,
                100,
                null,
                "2033-01-01T00:00:00Z");

        assertEquals(200, putJson(
                        session.client(), PATH + "/" + id, publishedBody, session.csrfToken())
                .statusCode());
        jdbcTemplate.update("UPDATE breeds SET status = 'archived' WHERE id = ?", breed);
        jdbcTemplate.update("UPDATE services SET status = 'draft' WHERE id = ?", service);
        jdbcTemplate.update("UPDATE media_assets SET status = 'archived' WHERE id = ?", cover);

        assertEquals("published", jdbcTemplate.queryForObject(
                "SELECT status FROM gallery_items WHERE id = ?", String.class, id));
        assertEquals("published", json(get(session.client(), PATH + "/" + id)).get("status").asText());

        var archived = putJson(
                session.client(),
                PATH + "/" + id,
                updateBody(
                        "archived", "라미", breed, service, cover, null, null, null,
                        "라미의 미용 완료 사진", false, 100, null, "2033-01-01T00:00:00Z"),
                session.csrfToken());
        var restoredDraft = putJson(
                session.client(),
                PATH + "/" + id,
                updateBody(
                        "draft", "라미", breed, service, cover, null, null, null,
                        null, false, 100, null, null),
                session.csrfToken());

        assertEquals(200, archived.statusCode(), archived.body());
        assertEquals("archived", json(archived).get("status").asText());
        assertEquals(200, restoredDraft.statusCode(), restoredDraft.body());
        assertEquals("draft", json(restoredDraft).get("status").asText());

        jdbcTemplate.update("UPDATE breeds SET status = 'published' WHERE id = ?", breed);
        jdbcTemplate.update("UPDATE services SET status = 'published' WHERE id = ?", service);
        jdbcTemplate.update("UPDATE media_assets SET status = 'active' WHERE id = ?", cover);
        var restoredPublished = putJson(
                session.client(), PATH + "/" + id, publishedBody, session.csrfToken());
        assertEquals(200, restoredPublished.statusCode(), restoredPublished.body());
        assertEquals("published", json(restoredPublished).get("status").asText());
    }

    @Test
    void should_listWithEveryExplicitTieBreaker_when_galleryStatesDiffer() throws Exception {
        var session = login(ADMIN_A_EMAIL);
        insertOrdering(
                UUID.fromString("00000000-0000-0000-0000-000000000001"),
                "featured-sort-20",
                true,
                20,
                null);
        insertOrdering(
                UUID.fromString("00000000-0000-0000-0000-000000000002"),
                "featured-sort-10",
                true,
                10,
                null);
        insertOrdering(
                UUID.fromString("00000000-0000-0000-0000-000000000010"),
                "published-new",
                false,
                5,
                OffsetDateTime.parse("2030-03-01T00:00:00Z"));
        insertOrdering(
                UUID.fromString("00000000-0000-0000-0000-000000000020"),
                "published-old",
                false,
                5,
                OffsetDateTime.parse("2030-02-01T00:00:00Z"));
        insertOrdering(
                UUID.fromString("00000000-0000-0000-0000-000000000040"),
                "null-tie-b",
                false,
                5,
                null);
        insertOrdering(
                UUID.fromString("00000000-0000-0000-0000-000000000030"),
                "null-tie-a",
                false,
                5,
                null);

        var list = get(session.client(), PATH);

        assertEquals(200, list.statusCode());
        assertOrdered(
                list.body(),
                "featured-sort-10",
                "featured-sort-20",
                "published-new",
                "published-old",
                "null-tie-a",
                "null-tie-b");
    }

    @Test
    void should_returnFixedNotFoundAndInvalidRequestAndRejectUnsupportedMutations_when_routeIsInvalid()
            throws Exception {
        var session = login(ADMIN_A_EMAIL);
        var missing = UUID.randomUUID();
        var missingGet = get(session.client(), PATH + "/" + missing);
        var missingPut = putJson(
                session.client(),
                PATH + "/" + missing,
                updateBody(
                        "draft", null, null, null, null, null, null, null, null,
                        false, 100, null, null),
                session.csrfToken());

        assertError(missingGet, 404, "GALLERY_ITEM_NOT_FOUND");
        assertError(missingPut, 404, "GALLERY_ITEM_NOT_FOUND");

        var malformed = get(session.client(), PATH + "/not-a-uuid");
        assertError(malformed, 400, "INVALID_REQUEST");
        assertFalse(malformed.body().contains("not-a-uuid"));
        assertFalse(malformed.body().contains("MethodArgumentTypeMismatchException"));
        assertFalse(malformed.body().contains("java.lang"));

        var created = postJson(session.client(), PATH, "{}", session.csrfToken());
        var id = UUID.fromString(json(created).get("id").asText());
        var before = readState(id);
        var full = updateBody(
                "archived", null, null, null, null, null, null, null, null,
                false, 100, null, null);
        for (var response : new HttpResponse[] {
            request(session.client(), "PATCH", PATH + "/" + id, full, session.csrfToken()),
            request(session.client(), "DELETE", PATH + "/" + id, null, session.csrfToken()),
            request(session.client(), "POST", PATH + "/" + id + "/publish", null, session.csrfToken())
        }) {
            assertFalse(isSuccess(response.statusCode()));
        }
        assertEquals(before, readState(id));
    }

    private String validPublished(
            UUID breed, UUID service, UUID cover, UUID before, UUID after) {
        return updateBody(
                "published",
                "테스트 강아지",
                breed,
                service,
                cover,
                before,
                after,
                "미용 사례",
                "테스트 강아지의 미용 완료 사진",
                false,
                100,
                null,
                "2030-01-01T00:00:00Z");
    }

    private AdminUser createAdmin(String email) {
        return adminUserRepository.saveAndFlush(
                AdminUser.create(email, passwordEncoder.encode(ADMIN_PASSWORD)));
    }

    private UUID insertBreed(String status, String slug) {
        var id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO breeds (
                    id, status, name, slug, description, sort_order,
                    created_by, updated_by
                ) VALUES (?, ?, '테스트 견종', ?, NULL, 100, ?, ?)
                """,
                id,
                status,
                slug,
                adminA.getId(),
                adminA.getId());
        return id;
    }

    private UUID insertService(String status, String slug) {
        var id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO services (
                    id, status, name, slug, description, price_text, sort_order,
                    created_by, updated_by
                ) VALUES (?, ?, '테스트 서비스', ?, '서비스 설명', '상담 후 안내', 100, ?, ?)
                """,
                id,
                status,
                slug,
                adminA.getId(),
                adminA.getId());
        return id;
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
                adminA.getId(),
                adminA.getId());
        return id;
    }

    private void insertOrdering(
            UUID id, String dogName, boolean featured, int sortOrder, OffsetDateTime publishedAt) {
        jdbcTemplate.update(
                """
                INSERT INTO gallery_items (
                    id, status, dog_name, featured, sort_order, published_at,
                    created_by, updated_by
                ) VALUES (?, 'draft', ?, ?, ?, ?, ?, ?)
                """,
                id,
                dogName,
                featured,
                sortOrder,
                publishedAt,
                adminA.getId(),
                adminA.getId());
    }

    private Map<String, Object> readState(UUID id) {
        return jdbcTemplate.queryForMap(
                """
                SELECT status, dog_name, breed_id, primary_service_id,
                       cover_image_id, before_image_id, after_image_id,
                       summary, alt_text, featured, sort_order, performed_at, published_at,
                       created_at, updated_at, created_by, updated_by
                FROM gallery_items
                WHERE id = ?
                """,
                id);
    }

    private int rowCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM gallery_items", Integer.class);
    }

    private void assertOrdered(String body, String... dogNames) {
        var previous = -1;
        for (var dogName : dogNames) {
            var current = body.indexOf("\"dogName\":\"" + dogName + "\"");
            assertTrue(current > previous, body);
            previous = current;
        }
    }

    private void clearFixtures() {
        jdbcTemplate.update("DELETE FROM gallery_items");
        jdbcTemplate.update("DELETE FROM media_assets");
        jdbcTemplate.update("DELETE FROM services");
        jdbcTemplate.update("DELETE FROM breeds");
        adminUserRepository.findByEmail(ADMIN_A_EMAIL).ifPresent(adminUserRepository::delete);
        adminUserRepository.findByEmail(ADMIN_B_EMAIL).ifPresent(adminUserRepository::delete);
        adminUserRepository.flush();
    }

    private AuthenticatedSession login(String email) throws Exception {
        var client = newClient();
        var csrfToken = fetchCsrf(client);
        var login = postJson(client, "/api/admin/auth/login", loginBody(email), csrfToken);
        assertEquals(200, login.statusCode(), login.body());
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
        assertTrue(matcher.find(), response.body());
        return matcher.group(1);
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        return objectMapper.readTree(response.body());
    }

    private void assertError(HttpResponse<String> response, int status, String code) throws Exception {
        assertEquals(status, response.statusCode(), response.body());
        var error = json(response);
        assertEquals(code, error.get("code").asText(), response.body());
        assertNotNull(error.get("message").asText());
        assertEquals(2, error.size(), response.body());
    }

    private HttpResponse<String> get(TestClient client, String path) throws Exception {
        return request(client, "GET", path, null, null);
    }

    private HttpResponse<String> postJson(
            TestClient client, String path, String body, String csrfToken) throws Exception {
        return request(client, "POST", path, body, csrfToken);
    }

    private HttpResponse<String> putJson(
            TestClient client, String path, String body, String csrfToken) throws Exception {
        return request(client, "PUT", path, body, csrfToken);
    }

    private HttpResponse<String> request(
            TestClient client, String method, String path, String body, String csrfToken)
            throws Exception {
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

    private String createBody(
            String dogName,
            UUID breedId,
            UUID primaryServiceId,
            UUID coverImageId,
            UUID beforeImageId,
            UUID afterImageId,
            String summary,
            String altText,
            Boolean featured,
            Integer sortOrder,
            String performedAt,
            String publishedAt) {
        return """
                {
                  "dogName": %s,
                  "breedId": %s,
                  "primaryServiceId": %s,
                  "coverImageId": %s,
                  "beforeImageId": %s,
                  "afterImageId": %s,
                  "summary": %s,
                  "altText": %s,
                  "featured": %s,
                  "sortOrder": %s,
                  "performedAt": %s,
                  "publishedAt": %s
                }
                """.formatted(
                jsonString(dogName),
                jsonUuid(breedId),
                jsonUuid(primaryServiceId),
                jsonUuid(coverImageId),
                jsonUuid(beforeImageId),
                jsonUuid(afterImageId),
                jsonString(summary),
                jsonString(altText),
                featured == null ? "null" : featured,
                sortOrder == null ? "null" : sortOrder,
                jsonString(performedAt),
                jsonString(publishedAt));
    }

    private String updateBody(
            String status,
            String dogName,
            UUID breedId,
            UUID primaryServiceId,
            UUID coverImageId,
            UUID beforeImageId,
            UUID afterImageId,
            String summary,
            String altText,
            boolean featured,
            int sortOrder,
            String performedAt,
            String publishedAt) {
        return """
                {
                  "status": %s,
                  "dogName": %s,
                  "breedId": %s,
                  "primaryServiceId": %s,
                  "coverImageId": %s,
                  "beforeImageId": %s,
                  "afterImageId": %s,
                  "summary": %s,
                  "altText": %s,
                  "featured": %s,
                  "sortOrder": %s,
                  "performedAt": %s,
                  "publishedAt": %s
                }
                """.formatted(
                jsonString(status),
                jsonString(dogName),
                jsonUuid(breedId),
                jsonUuid(primaryServiceId),
                jsonUuid(coverImageId),
                jsonUuid(beforeImageId),
                jsonUuid(afterImageId),
                jsonString(summary),
                jsonString(altText),
                featured,
                sortOrder,
                jsonString(performedAt),
                jsonString(publishedAt));
    }

    private String jsonUuid(UUID value) {
        return value == null ? "null" : jsonString(value.toString());
    }

    private String appendField(String body, String field) {
        var closingBrace = body.lastIndexOf('}');
        return body.substring(0, closingBrace) + ",\n  " + field + "\n}";
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

    private record TestClient(HttpClient httpClient, CookieManager cookieManager) {}

    private record AuthenticatedSession(TestClient client, String csrfToken) {}
}
