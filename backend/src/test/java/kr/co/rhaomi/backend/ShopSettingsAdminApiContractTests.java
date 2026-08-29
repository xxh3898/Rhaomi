package kr.co.rhaomi.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class ShopSettingsAdminApiContractTests {

    private static final String PATH = "/api/admin/shop-settings";
    private static final String ADMIN_A_EMAIL = "shop.admin.a@example.com";
    private static final String ADMIN_B_EMAIL = "shop.admin.b@example.com";
    private static final String ADMIN_PASSWORD = "local-shop-password-123!";
    private static final String HASH = "a".repeat(64);
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
    void should_rejectAnonymousMissingCsrfAndPublicRead_when_shopSettingsEndpointIsProtected()
            throws Exception {
        var anonymous = newClient();

        assertEquals(401, get(anonymous, PATH).statusCode());
        assertEquals(403, putJson(anonymous, PATH, validSettings(), null).statusCode());
        var anonymousCsrf = fetchCsrf(anonymous);
        assertEquals(401, putJson(anonymous, PATH, validSettings(), anonymousCsrf).statusCode());

        var authenticated = login(ADMIN_A_EMAIL);
        assertEquals(403, putJson(authenticated.client(), PATH, validSettings(), null).statusCode());
        assertFalse(isSuccess(get(anonymous, "/api/shop-settings").statusCode()));
        assertFalse(isSuccess(get(authenticated.client(), "/api/shop-settings").statusCode()));
        assertFalse(isSuccess(get(authenticated.client(), "/api/public/shop-settings").statusCode()));
        assertFalse(isSuccess(get(authenticated.client(), "/api/build/shop-settings").statusCode()));
    }

    @Test
    void should_returnNotFoundThenCreateNormalizedSingletonAndRoundTrip_when_firstPutIsValid()
            throws Exception {
        var session = login(ADMIN_A_EMAIL);
        var beforeInitialization = get(session.client(), PATH);

        assertEquals(404, beforeInitialization.statusCode());
        assertTrue(beforeInitialization.body().contains("SHOP_SETTINGS_NOT_FOUND"));

        var request = replaceField(
                validSettings(), "shopName", jsonString("라오미펫"), jsonString("\u00a0라오미펫\u00a0"));
        request = replaceField(
                request, "phone", jsonString("02-1234-5678"), jsonString("\u00a002-1234-5678\u00a0"));
        request = replaceField(
                request, "parkingNote", jsonString("주차 가능"), jsonString("\u2003"));
        request = replaceField(
                request, "heroTitle", jsonString("반려견의 편안한 하루"), jsonString("  편안한 미용  "));
        var created = putJson(session.client(), PATH, request, session.csrfToken());
        var fetched = get(session.client(), PATH);

        assertEquals(201, created.statusCode());
        assertEquals(PATH, created.headers().firstValue("Location").orElseThrow());
        assertEquals(200, fetched.statusCode());
        assertEquals(created.body(), fetched.body());
        assertTrue(created.body().contains("\"shopName\":\"라오미펫\""));
        assertTrue(created.body().contains("\"regionLabel\":\"서울\""));
        assertTrue(created.body().contains("\"businessType\":\"애견미용\""));
        assertTrue(created.body().contains("\"phone\":\"02-1234-5678\""));
        assertTrue(created.body().contains("\"address\":\"서울시 어딘가\""));
        assertTrue(created.body().contains("\"openingTime\":\"10:00\""));
        assertTrue(created.body().contains("\"closingTime\":\"19:00\""));
        assertTrue(created.body().contains("\"closedWeekday\":\"MONDAY\""));
        assertTrue(created.body().contains("\"parkingAvailable\":true"));
        assertTrue(created.body().contains("\"parkingNote\":null"));
        assertTrue(created.body().contains("\"heroTitle\":\"편안한 미용\""));
        assertTrue(created.body().contains("\"heroDescription\":\"예약제로 운영합니다.\""));
        assertTrue(created.body().contains("\"groomerName\":\"라오미\""));
        assertTrue(created.body().contains("\"groomerIntro\":\"반려견의 속도에 맞춥니다.\""));
        assertTrue(created.body().contains("\"reservationNotice\":\"예약 전 상담이 필요합니다.\""));
        assertTrue(created.body().contains("\"heroImageId\":null"));
        assertTrue(created.body().contains("\"heroImageAltText\":null"));
        assertTrue(created.body().contains("\"groomerImageId\":null"));
        assertTrue(created.body().contains("\"groomerImageAltText\":null"));
        assertTrue(created.body().contains("\"ogImageId\":null"));
        assertTrue(created.body().contains("\"instagramUrl\":\"https://example.com/rhaomi\""));
        assertTrue(created.body().contains("\"naverBlogUrl\":\"https://blog.example/rhaomi\""));
        assertTrue(created.body().contains("\"naverMapUrl\":\"https://map.example/naver\""));
        assertTrue(created.body().contains("\"kakaoMapUrl\":\"https://map.example/kakao\""));
        assertTrue(created.body().contains("\"naverTalktalkUrl\":\"https://talk.example/naver\""));
        assertTrue(created.body().contains("\"kakaoChannelUrl\":\"https://channel.example/kakao\""));
        assertTrue(created.body().contains("\"createdBy\":\"" + adminA.getId() + "\""));
        assertTrue(created.body().contains("\"updatedBy\":\"" + adminA.getId() + "\""));
        assertFalse(created.body().contains("\"id\":"));
        assertFalse(created.body().contains("singletonKey"));
        assertFalse(created.body().contains("\"status\":"));
        assertEquals(1, rowCount());
    }

    @Test
    void should_createAndRoundTripNormalizedMediaRelations_when_activeAssetIsReusedForEveryRole()
            throws Exception {
        var mediaId = insertMedia("active");
        var session = login(ADMIN_A_EMAIL);
        var heroAlt = "가".repeat(300);
        var request = withMediaRelations(
                validSettings(),
                mediaId,
                "\u2003" + heroAlt + "\u00a0",
                mediaId,
                "  라오미펫 은총쌤 프로필 사진  ",
                mediaId);

        var created = putJson(session.client(), PATH, request, session.csrfToken());
        var fetched = get(session.client(), PATH);

        assertEquals(201, created.statusCode(), created.body());
        assertEquals(created.body(), fetched.body());
        assertTrue(created.body().contains("\"heroImageId\":\"" + mediaId + "\""));
        assertTrue(created.body().contains("\"heroImageAltText\":\"" + heroAlt + "\""));
        assertTrue(created.body().contains("\"groomerImageId\":\"" + mediaId + "\""));
        assertTrue(created.body().contains(
                "\"groomerImageAltText\":\"라오미펫 은총쌤 프로필 사진\""));
        assertTrue(created.body().contains("\"ogImageId\":\"" + mediaId + "\""));
        assertFalse(created.body().contains("storageKey"));
        assertFalse(created.body().contains("fileExtension"));
        assertFalse(created.body().contains("sha256"));
        assertFalse(created.body().contains("originalFilename"));
        assertEquals(1, rowCount());
    }

    @Test
    void should_notCreateSingleton_when_firstPutReferencesMissingMedia() throws Exception {
        var session = login(ADMIN_A_EMAIL);
        var request = withMediaRelations(
                validSettings(), UUID.randomUUID(), "Hero", null, null, null);

        var response = putJson(session.client(), PATH, request, session.csrfToken());

        assertEquals(422, response.statusCode(), response.body());
        assertTrue(response.body().contains("SHOP_MEDIA_RELATION_INVALID"));
        assertEquals(0, rowCount());
    }

    @Test
    void should_replaceThenClearEveryMediaRelation_when_subsequentPutsAreValid() throws Exception {
        var firstMedia = insertMedia("active");
        var secondMedia = insertMedia("active");
        var session = login(ADMIN_A_EMAIL);
        var initial = withMediaRelations(
                validSettings(), firstMedia, "첫 Hero", firstMedia, "첫 프로필", firstMedia);
        assertEquals(201, putJson(session.client(), PATH, initial, session.csrfToken()).statusCode());
        var createdState = readState();

        var replacement = withMediaRelations(
                validSettings(), secondMedia, "교체 Hero", secondMedia, "교체 프로필", secondMedia);
        var replaced = putJson(session.client(), PATH, replacement, session.csrfToken());
        var replacedState = readState();

        assertEquals(200, replaced.statusCode(), replaced.body());
        assertEquals(secondMedia, replacedState.get("hero_image_id"));
        assertEquals(secondMedia, replacedState.get("groomer_image_id"));
        assertEquals(secondMedia, replacedState.get("og_image_id"));
        assertEquals(createdState.get("created_at"), replacedState.get("created_at"));
        assertEquals(createdState.get("created_by"), replacedState.get("created_by"));

        var cleared = putJson(session.client(), PATH, validSettings(), session.csrfToken());
        var clearedState = readState();

        assertEquals(200, cleared.statusCode(), cleared.body());
        assertTrue(cleared.body().contains("\"heroImageId\":null"));
        assertTrue(cleared.body().contains("\"groomerImageId\":null"));
        assertTrue(cleared.body().contains("\"ogImageId\":null"));
        assertNull(clearedState.get("hero_image_id"));
        assertNull(clearedState.get("hero_image_alt_text"));
        assertNull(clearedState.get("groomer_image_id"));
        assertNull(clearedState.get("groomer_image_alt_text"));
        assertNull(clearedState.get("og_image_id"));
        assertEquals(createdState.get("created_at"), clearedState.get("created_at"));
        assertEquals(createdState.get("created_by"), clearedState.get("created_by"));
    }

    @Test
    void should_rejectMissingArchivedMalformedAndInvalidPairs_withoutChangingRowOrAudit()
            throws Exception {
        var session = initializedSession();
        var before = readState();
        var missing = UUID.randomUUID();
        var archived = insertMedia("archived");
        var active = insertMedia("active");

        var invalidRelations = new String[] {
            withMediaRelations(validSettings(), missing, "Hero", null, null, null),
            withMediaRelations(validSettings(), null, null, missing, "프로필", null),
            withMediaRelations(validSettings(), null, null, null, null, missing),
            withMediaRelations(validSettings(), archived, "Hero", null, null, null),
            withMediaRelations(validSettings(), null, null, archived, "프로필", null),
            withMediaRelations(validSettings(), null, null, null, null, archived),
            withMediaRelations(validSettings(), active, null, null, null, null),
            withMediaRelations(validSettings(), active, "\t\n ", null, null, null),
            withMediaRelations(validSettings(), null, "Hero", null, null, null),
            withMediaRelations(validSettings(), null, null, active, null, null),
            withMediaRelations(validSettings(), null, null, active, "\t\n ", null),
            withMediaRelations(validSettings(), null, null, null, "프로필", null)
        };

        for (var request : invalidRelations) {
            var response = putJson(session.client(), PATH, request, session.csrfToken());
            assertEquals(422, response.statusCode(), response.body());
            assertTrue(response.body().contains("SHOP_MEDIA_RELATION_INVALID"), response.body());
            assertEquals(before, readState());
        }

        for (var field : new String[] {"heroImageId", "groomerImageId", "ogImageId"}) {
            var malformed = replaceField(validSettings(), field, "null", jsonString("not-a-uuid"));
            var response = putJson(session.client(), PATH, malformed, session.csrfToken());
            assertEquals(400, response.statusCode(), response.body());
            assertTrue(response.body().contains("INVALID_REQUEST"), response.body());
            assertFalse(response.body().contains("not-a-uuid"), response.body());
            assertEquals(before, readState());
        }

        var tooLong = withMediaRelations(
                validSettings(), active, "가".repeat(301), null, null, null);
        var tooLongResponse = putJson(session.client(), PATH, tooLong, session.csrfToken());
        assertEquals(400, tooLongResponse.statusCode(), tooLongResponse.body());
        assertTrue(tooLongResponse.body().contains("INVALID_REQUEST"), tooLongResponse.body());
        assertEquals(before, readState());
    }

    @Test
    void should_keepStoredRelationsUntilExplicitChange_when_referencedMediaIsArchivedLater()
            throws Exception {
        var mediaId = insertMedia("active");
        var replacementId = insertMedia("active");
        var session = login(ADMIN_A_EMAIL);
        var related = withMediaRelations(
                validSettings(), mediaId, "Hero", mediaId, "프로필", mediaId);
        assertEquals(201, putJson(session.client(), PATH, related, session.csrfToken()).statusCode());
        var beforeArchive = readState();

        jdbcTemplate.update("UPDATE media_assets SET status = 'archived' WHERE id = ?", mediaId);

        assertEquals(beforeArchive, readState());
        var fetched = get(session.client(), PATH);
        assertEquals(200, fetched.statusCode());
        assertTrue(fetched.body().contains("\"heroImageId\":\"" + mediaId + "\""));

        var stalePut = putJson(session.client(), PATH, related, session.csrfToken());
        assertEquals(422, stalePut.statusCode(), stalePut.body());
        assertTrue(stalePut.body().contains("SHOP_MEDIA_RELATION_INVALID"));
        assertEquals(beforeArchive, readState());

        var cleared = putJson(session.client(), PATH, validSettings(), session.csrfToken());
        assertEquals(200, cleared.statusCode(), cleared.body());
        var afterClear = readState();
        assertNull(afterClear.get("hero_image_id"));
        assertNull(afterClear.get("groomer_image_id"));
        assertNull(afterClear.get("og_image_id"));

        var replacement = withMediaRelations(
                validSettings(), replacementId, "새 Hero", replacementId, "새 프로필", replacementId);
        var replaced = putJson(session.client(), PATH, replacement, session.csrfToken());
        assertEquals(200, replaced.statusCode(), replaced.body());
        assertTrue(replaced.body().contains("\"ogImageId\":\"" + replacementId + "\""));
    }

    @Test
    void should_replaceFullRepresentationAndPreserveCreatedAudit_when_secondAdminPuts()
            throws Exception {
        var creator = login(ADMIN_A_EMAIL);
        assertEquals(201, putJson(creator.client(), PATH, validSettings(), creator.csrfToken()).statusCode());
        var before = readState();
        var updater = login(ADMIN_B_EMAIL);
        var changed = replaceField(
                validSettings(), "shopName", jsonString("라오미펫"), jsonString("라오미펫 2호점"));
        changed = replaceField(changed, "closedWeekday", jsonString("MONDAY"), "null");
        changed = replaceField(changed, "parkingAvailable", "true", "false");
        changed = replaceField(
                changed, "heroDescription", jsonString("예약제로 운영합니다."), jsonString("  "));
        changed = replaceField(
                changed,
                "naverBlogUrl",
                jsonString("https://blog.example/rhaomi"),
                jsonString("  "));

        var updated = putJson(updater.client(), PATH, changed, updater.csrfToken());
        var fetched = get(updater.client(), PATH);
        var after = readState();

        assertEquals(200, updated.statusCode());
        assertEquals(updated.body(), fetched.body());
        assertTrue(updated.body().contains("\"shopName\":\"라오미펫 2호점\""));
        assertTrue(updated.body().contains("\"closedWeekday\":null"));
        assertTrue(updated.body().contains("\"parkingAvailable\":false"));
        assertTrue(updated.body().contains("\"heroDescription\":null"));
        assertTrue(updated.body().contains("\"naverBlogUrl\":null"));
        assertTrue(updated.body().contains("\"createdBy\":\"" + adminA.getId() + "\""));
        assertTrue(updated.body().contains("\"updatedBy\":\"" + adminB.getId() + "\""));
        assertEquals(before.get("created_at"), after.get("created_at"));
        assertEquals(before.get("created_by"), after.get("created_by"));
        assertNotEquals(before.get("updated_at"), after.get("updated_at"));
        assertNotEquals(before.get("updated_by"), after.get("updated_by"));
        assertEquals(adminB.getId(), after.get("updated_by"));
        assertEquals(1, rowCount());
    }

    @Test
    void should_keepExactlyOneRow_when_sequentialPutRetriesRepeat() throws Exception {
        var session = login(ADMIN_A_EMAIL);

        assertEquals(201, putJson(session.client(), PATH, validSettings(), session.csrfToken()).statusCode());
        for (var attempt = 0; attempt < 4; attempt++) {
            var request = replaceField(
                    validSettings(),
                    "heroTitle",
                    jsonString("반려견의 편안한 하루"),
                    jsonString("재시도 " + attempt));
            assertEquals(200, putJson(session.client(), PATH, request, session.csrfToken()).statusCode());
            assertEquals(1, rowCount());
        }
    }

    @Test
    void should_rejectMalformedTimeWeekdayAndPartialBody_withoutChangingRow() throws Exception {
        var session = initializedSession();
        var before = readState();
        var invalidRequests = new String[] {
            replaceField(validSettings(), "openingTime", jsonString("10:00"), jsonString("10:00:00")),
            replaceField(validSettings(), "openingTime", jsonString("10:00"), jsonString("24:00")),
            replaceField(validSettings(), "closedWeekday", jsonString("MONDAY"), jsonString("monday")),
            replaceField(validSettings(), "closedWeekday", jsonString("MONDAY"), jsonString("HOLIDAY")),
            "{\"shopName\":\"부분 수정\"}",
            "{"
        };

        for (var request : invalidRequests) {
            var response = putJson(session.client(), PATH, request, session.csrfToken());
            assertEquals(400, response.statusCode(), response.body());
            assertTrue(response.body().contains("INVALID_REQUEST"), response.body());
            assertEquals(before, readState());
        }
    }

    @Test
    void should_rejectEqualAndReversedHoursWith422_withoutChangingRow() throws Exception {
        var session = initializedSession();
        var before = readState();

        for (var closingTime : new String[] {"10:00", "09:59"}) {
            var request = replaceField(
                    validSettings(), "closingTime", jsonString("19:00"), jsonString(closingTime));
            var response = putJson(session.client(), PATH, request, session.csrfToken());

            assertEquals(422, response.statusCode(), response.body());
            assertTrue(response.body().contains("BUSINESS_HOURS_INVALID"), response.body());
            assertEquals(before, readState());
        }
    }

    @Test
    void should_enforcePhoneAndHttpsUrlBoundaries_withoutChangingRowOnFailure() throws Exception {
        var session = initializedSession();

        var minPhone = replaceField(
                validSettings(), "phone", jsonString("02-1234-5678"), jsonString("1234567"));
        assertEquals(200, putJson(session.client(), PATH, minPhone, session.csrfToken()).statusCode());

        var maxPhoneValue = "+82 " + "1".repeat(28);
        assertEquals(32, maxPhoneValue.length());
        var maxPhone = replaceField(
                validSettings(), "phone", jsonString("02-1234-5678"), jsonString(maxPhoneValue));
        assertEquals(200, putJson(session.client(), PATH, maxPhone, session.csrfToken()).statusCode());

        var urlPrefix = "https://example.com/";
        var maxUrlValue = urlPrefix + "a".repeat(2_048 - urlPrefix.length());
        var maxUrl = replaceField(
                validSettings(), "instagramUrl", jsonString("https://example.com/rhaomi"), jsonString(maxUrlValue));
        assertEquals(200, putJson(session.client(), PATH, maxUrl, session.csrfToken()).statusCode());

        var before = readState();
        var invalidRequests = new String[] {
            replaceField(validSettings(), "phone", jsonString("02-1234-5678"), jsonString("123456")),
            replaceField(
                    validSettings(), "phone", jsonString("02-1234-5678"), jsonString(maxPhoneValue + "1")),
            replaceField(validSettings(), "phone", jsonString("02-1234-5678"), jsonString("010.1234.5678")),
            replaceField(validSettings(), "phone", jsonString("02-1234-5678"), jsonString("010\n1234")),
            replaceField(
                    validSettings(), "phone", jsonString("02-1234-5678"), jsonString("\n02-1234-5678")),
            replaceField(
                    validSettings(),
                    "instagramUrl",
                    jsonString("https://example.com/rhaomi"),
                    jsonString("http://example.com/rhaomi")),
            replaceField(
                    validSettings(),
                    "instagramUrl",
                    jsonString("https://example.com/rhaomi"),
                    jsonString("javascript:alert(1)")),
            replaceField(
                    validSettings(),
                    "instagramUrl",
                    jsonString("https://example.com/rhaomi"),
                    jsonString("data:text/plain,test")),
            replaceField(
                    validSettings(),
                    "instagramUrl",
                    jsonString("https://example.com/rhaomi"),
                    jsonString("file:///tmp/test")),
            replaceField(
                    validSettings(),
                    "instagramUrl",
                    jsonString("https://example.com/rhaomi"),
                    jsonString("/relative/path")),
            replaceField(
                    validSettings(),
                    "instagramUrl",
                    jsonString("https://example.com/rhaomi"),
                    jsonString("https://user@example.com/rhaomi")),
            replaceField(
                    validSettings(),
                    "instagramUrl",
                    jsonString("https://example.com/rhaomi"),
                    jsonString("https:///missing-host")),
            replaceField(
                    validSettings(),
                    "instagramUrl",
                    jsonString("https://example.com/rhaomi"),
                    jsonString("https://example.com/line\nfeed")),
            replaceField(
                    validSettings(),
                    "instagramUrl",
                    jsonString("https://example.com/rhaomi"),
                    jsonString("\nhttps://example.com/rhaomi")),
            replaceField(
                    validSettings(),
                    "instagramUrl",
                    jsonString("https://example.com/rhaomi"),
                    jsonString(maxUrlValue + "a"))
        };

        for (var request : invalidRequests) {
            var response = putJson(session.client(), PATH, request, session.csrfToken());
            assertEquals(400, response.statusCode(), response.body());
            assertTrue(response.body().contains("INVALID_REQUEST"), response.body());
            assertEquals(before, readState());
        }
    }

    @Test
    void should_enforceEveryTextLimitAndRequiredValue_withoutChangingRowOnFailure() throws Exception {
        var session = initializedSession();
        var fields = Map.ofEntries(
                Map.entry("shopName", new TextBoundary("라오미펫", 100)),
                Map.entry("regionLabel", new TextBoundary("서울", 100)),
                Map.entry("businessType", new TextBoundary("애견미용", 100)),
                Map.entry("address", new TextBoundary("서울시 어딘가", 300)),
                Map.entry("parkingNote", new TextBoundary("주차 가능", 300)),
                Map.entry("heroTitle", new TextBoundary("반려견의 편안한 하루", 200)),
                Map.entry("heroDescription", new TextBoundary("예약제로 운영합니다.", 1_000)),
                Map.entry("groomerName", new TextBoundary("라오미", 100)),
                Map.entry("groomerIntro", new TextBoundary("반려견의 속도에 맞춥니다.", 2_000)),
                Map.entry("reservationNotice", new TextBoundary("예약 전 상담이 필요합니다.", 4_000)));

        for (var entry : fields.entrySet()) {
            var field = entry.getKey();
            var boundary = entry.getValue();
            var accepted = replaceField(
                    validSettings(),
                    field,
                    jsonString(boundary.original()),
                    jsonString("가".repeat(boundary.maxLength())));
            assertEquals(
                    200,
                    putJson(session.client(), PATH, accepted, session.csrfToken()).statusCode(),
                    field);

            var before = readState();
            var rejected = replaceField(
                    validSettings(),
                    field,
                    jsonString(boundary.original()),
                    jsonString("가".repeat(boundary.maxLength() + 1)));
            assertEquals(
                    400,
                    putJson(session.client(), PATH, rejected, session.csrfToken()).statusCode(),
                    field);
            assertEquals(before, readState(), field);
        }

        for (var field : new String[] {"shopName", "regionLabel", "businessType", "phone", "address"}) {
            var original = switch (field) {
                case "shopName" -> "라오미펫";
                case "regionLabel" -> "서울";
                case "businessType" -> "애견미용";
                case "phone" -> "02-1234-5678";
                case "address" -> "서울시 어딘가";
                default -> throw new IllegalStateException();
            };
            var before = readState();
            var rejected = replaceField(
                    validSettings(), field, jsonString(original), jsonString("\u00a0\t\n "));
            assertEquals(
                    400,
                    putJson(session.client(), PATH, rejected, session.csrfToken()).statusCode(),
                    field);
            assertEquals(before, readState(), field);
        }
    }

    @Test
    void should_rejectImmutableUnknownFieldsAndUnsupportedRoutes_withoutChangingRow()
            throws Exception {
        var session = initializedSession();
        var before = readState();

        for (var injected : new String[] {
            "\"id\":\"" + UUID.randomUUID() + "\"",
            "\"singletonKey\":true",
            "\"status\":\"published\"",
            "\"createdAt\":\"2030-01-01T00:00:00Z\"",
            "\"updatedAt\":\"2030-01-01T00:00:00Z\"",
            "\"createdBy\":\"" + adminB.getId() + "\"",
            "\"updatedBy\":\"" + adminB.getId() + "\"",
            "\"storageKey\":\"masters/aa/private.jpg\"",
            "\"sha256\":\"" + HASH + "\"",
            "\"originalFilename\":\"private.jpg\"",
            "\"unknownField\":\"value\""
        }) {
            var response = putJson(
                    session.client(), PATH, addField(validSettings(), injected), session.csrfToken());
            assertEquals(400, response.statusCode(), response.body());
            assertTrue(response.body().contains("INVALID_REQUEST"), response.body());
            assertEquals(before, readState());
        }

        assertFalse(isSuccess(request(
                        session.client(), "POST", PATH, validSettings(), session.csrfToken())
                .statusCode()));
        assertFalse(isSuccess(request(
                        session.client(), "PATCH", PATH, validSettings(), session.csrfToken())
                .statusCode()));
        assertFalse(isSuccess(request(
                        session.client(), "DELETE", PATH, null, session.csrfToken())
                .statusCode()));
        assertFalse(isSuccess(get(session.client(), PATH + "/" + UUID.randomUUID()).statusCode()));
        assertFalse(isSuccess(putJson(
                        session.client(),
                        PATH + "/" + UUID.randomUUID(),
                        validSettings(),
                        session.csrfToken())
                .statusCode()));
        assertEquals(before, readState());
    }

    private AuthenticatedSession initializedSession() throws Exception {
        var session = login(ADMIN_A_EMAIL);
        assertEquals(201, putJson(session.client(), PATH, validSettings(), session.csrfToken()).statusCode());
        return session;
    }

    private AdminUser createAdmin(String email) {
        return adminUserRepository.saveAndFlush(
                AdminUser.create(email, passwordEncoder.encode(ADMIN_PASSWORD)));
    }

    private int rowCount() {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM shop_settings", Integer.class);
    }

    private Map<String, Object> readState() {
        return jdbcTemplate.queryForMap(
                """
                SELECT shop_name, region_label, business_type, phone, address,
                       opening_time, closing_time, closed_weekday, parking_available,
                       parking_note, hero_title, hero_description, groomer_name,
                       groomer_intro, reservation_notice, instagram_url, naver_blog_url,
                       naver_map_url, kakao_map_url, naver_talktalk_url,
                       kakao_channel_url, hero_image_id, hero_image_alt_text,
                       groomer_image_id, groomer_image_alt_text, og_image_id,
                       created_at, updated_at, created_by, updated_by
                FROM shop_settings
                """);
    }

    private void clearFixtures() {
        jdbcTemplate.update("DELETE FROM shop_settings");
        jdbcTemplate.update("DELETE FROM media_assets");
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

    private String validSettings() {
        return """
                {
                  "shopName": "라오미펫",
                  "regionLabel": "서울",
                  "businessType": "애견미용",
                  "phone": "02-1234-5678",
                  "address": "서울시 어딘가",
                  "openingTime": "10:00",
                  "closingTime": "19:00",
                  "closedWeekday": "MONDAY",
                  "parkingAvailable": true,
                  "parkingNote": "주차 가능",
                  "heroTitle": "반려견의 편안한 하루",
                  "heroDescription": "예약제로 운영합니다.",
                  "groomerName": "라오미",
                  "groomerIntro": "반려견의 속도에 맞춥니다.",
                  "reservationNotice": "예약 전 상담이 필요합니다.",
                  "heroImageId": null,
                  "heroImageAltText": null,
                  "groomerImageId": null,
                  "groomerImageAltText": null,
                  "ogImageId": null,
                  "instagramUrl": "https://example.com/rhaomi",
                  "naverBlogUrl": "https://blog.example/rhaomi",
                  "naverMapUrl": "https://map.example/naver",
                  "kakaoMapUrl": "https://map.example/kakao",
                  "naverTalktalkUrl": "https://talk.example/naver",
                  "kakaoChannelUrl": "https://channel.example/kakao"
                }
                """;
    }

    private String withMediaRelations(
            String body,
            UUID heroImageId,
            String heroImageAltText,
            UUID groomerImageId,
            String groomerImageAltText,
            UUID ogImageId) {
        var result = replaceField(body, "heroImageId", "null", jsonUuid(heroImageId));
        result = replaceField(
                result, "heroImageAltText", "null", jsonNullableString(heroImageAltText));
        result = replaceField(result, "groomerImageId", "null", jsonUuid(groomerImageId));
        result = replaceField(
                result,
                "groomerImageAltText",
                "null",
                jsonNullableString(groomerImageAltText));
        return replaceField(result, "ogImageId", "null", jsonUuid(ogImageId));
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

    private String jsonUuid(UUID value) {
        return value == null ? "null" : jsonString(value.toString());
    }

    private String jsonNullableString(String value) {
        return value == null ? "null" : jsonString(value);
    }

    private String replaceField(String body, String field, String originalJson, String replacementJson) {
        var original = "\"" + field + "\": " + originalJson;
        var replacement = "\"" + field + "\": " + replacementJson;
        assertTrue(body.contains(original), field + " field fixture mismatch");
        return body.replace(original, replacement);
    }

    private String addField(String body, String rawField) {
        var closingBrace = body.lastIndexOf('}');
        return body.substring(0, closingBrace) + ",\n  " + rawField + "\n}";
    }

    private String jsonString(String value) {
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

    private record TextBoundary(String original, int maxLength) {}

    private record TestClient(HttpClient httpClient, CookieManager cookieManager) {}

    private record AuthenticatedSession(TestClient client, String csrfToken) {}
}
