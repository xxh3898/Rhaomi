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
class ContentAdminApiContractTests {

    private static final String ADMIN_A_EMAIL = "content.admin.a@example.com";
    private static final String ADMIN_B_EMAIL = "content.admin.b@example.com";
    private static final String ADMIN_PASSWORD = "local-content-password-123!";
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
    void should_rejectAnonymousAndMissingCsrf_when_contentEndpointsAreProtected() throws Exception {
        var anonymous = newClient();

        assertEquals(401, get(anonymous, "/api/admin/breeds").statusCode());
        assertEquals(401, get(anonymous, "/api/admin/services").statusCode());
        assertEquals(403, postJson(anonymous, "/api/admin/breeds", breedCreate("푸들", "poodle", null), null)
                .statusCode());
        assertEquals(403, putJson(
                        anonymous,
                        "/api/admin/services/" + UUID.randomUUID(),
                        serviceUpdate("draft", "전체미용", null, null, 100),
                        null)
                .statusCode());
        var anonymousCsrf = fetchCsrf(anonymous);
        assertEquals(401, postJson(
                        anonymous,
                        "/api/admin/breeds",
                        breedCreate("푸들", "poodle", 100),
                        anonymousCsrf)
                .statusCode());

        var authenticated = login(ADMIN_A_EMAIL);
        assertEquals(403, postJson(
                        authenticated.client(),
                        "/api/admin/services",
                        serviceCreate("전체미용", "full-grooming", null, null, null),
                        null)
                .statusCode());
        var created = createBreed(authenticated, "푸들", "poodle", 100);
        assertEquals(201, created.statusCode());
        assertEquals(403, putJson(
                        authenticated.client(),
                        "/api/admin/breeds/" + extractId(created.body()),
                        breedUpdate("draft", "푸들", null, 100),
                        null)
                .statusCode());
    }

    @Test
    void should_createDraftContentWithServerAudit_when_authenticatedWithCsrf() throws Exception {
        var session = login(ADMIN_A_EMAIL);

        var breed = postJson(
                session.client(),
                "/api/admin/breeds",
                breedCreate("  비숑 프리제  ", "bichon-frise", null),
                session.csrfToken());
        var service = postJson(
                session.client(),
                "/api/admin/services",
                serviceCreate("  전체미용  ", "full-grooming", null, null, null),
                session.csrfToken());

        assertEquals(201, breed.statusCode());
        assertTrue(breed.body().contains("\"status\":\"draft\""));
        assertTrue(breed.body().contains("\"name\":\"비숑 프리제\""));
        assertTrue(breed.body().contains("\"sortOrder\":100"));
        assertTrue(breed.body().contains("\"createdBy\":\"" + adminA.getId() + "\""));
        assertTrue(breed.body().contains("\"updatedBy\":\"" + adminA.getId() + "\""));

        assertEquals(201, service.statusCode());
        assertTrue(service.body().contains("\"status\":\"draft\""));
        assertTrue(service.body().contains("\"sortOrder\":100"));
        assertTrue(service.body().contains("\"description\":null"));
        assertTrue(service.body().contains("\"priceText\":null"));

        var breedId = extractId(breed.body());
        var serviceId = extractId(service.body());
        assertEquals("draft", jdbcTemplate.queryForObject(
                "SELECT status FROM breeds WHERE id = ?", String.class, breedId));
        assertEquals(adminA.getId(), jdbcTemplate.queryForObject(
                "SELECT created_by FROM services WHERE id = ?", UUID.class, serviceId));
        assertNotNull(jdbcTemplate.queryForObject(
                "SELECT created_at FROM breeds WHERE id = ?", java.time.OffsetDateTime.class, breedId));
    }

    @Test
    void should_listDeterministicallyAndGetById_when_multipleBreedsExist() throws Exception {
        var session = login(ADMIN_A_EMAIL);
        var last = createBreed(session, "나 견종", "last-breed", 10);
        var first = createBreed(session, "가 견종", "first-breed", 10);
        var middle = createBreed(session, "중간 견종", "middle-breed", 5);

        var list = get(session.client(), "/api/admin/breeds");
        var byId = get(session.client(), "/api/admin/breeds/" + extractId(first.body()));

        assertEquals(200, list.statusCode());
        assertTrue(list.body().indexOf("middle-breed") < list.body().indexOf("first-breed"));
        assertTrue(list.body().indexOf("first-breed") < list.body().indexOf("last-breed"));
        assertEquals(200, byId.statusCode());
        assertTrue(byId.body().contains(extractId(first.body()).toString()));
        assertFalse(list.body().toLowerCase().contains("password"));
        assertNotNull(last);
        assertNotNull(middle);
    }

    @Test
    void should_preserveSlugAndCreatedActor_when_secondAdminUpdatesBreed() throws Exception {
        var creator = login(ADMIN_A_EMAIL);
        var created = createBreed(creator, "푸들", "poodle", 100);
        var id = extractId(created.body());
        var updater = login(ADMIN_B_EMAIL);

        var updated = putJson(
                updater.client(),
                "/api/admin/breeds/" + id,
                breedUpdate("published", "  토이 푸들  ", "견종별 사례", 10),
                updater.csrfToken());

        assertEquals(200, updated.statusCode());
        assertTrue(updated.body().contains("\"status\":\"published\""));
        assertTrue(updated.body().contains("\"name\":\"토이 푸들\""));
        assertTrue(updated.body().contains("\"slug\":\"poodle\""));
        assertTrue(updated.body().contains("\"createdBy\":\"" + adminA.getId() + "\""));
        assertTrue(updated.body().contains("\"updatedBy\":\"" + adminB.getId() + "\""));
        assertEquals("poodle", jdbcTemplate.queryForObject(
                "SELECT slug FROM breeds WHERE id = ?", String.class, id));
        assertEquals(adminA.getId(), jdbcTemplate.queryForObject(
                "SELECT created_by FROM breeds WHERE id = ?", UUID.class, id));
        assertEquals(adminB.getId(), jdbcTemplate.queryForObject(
                "SELECT updated_by FROM breeds WHERE id = ?", UUID.class, id));
    }

    @Test
    void should_rejectInvalidSlugDuplicateAndMassAssignment_when_creatingBreed() throws Exception {
        var session = login(ADMIN_A_EMAIL);

        assertEquals(400, postJson(
                        session.client(),
                        "/api/admin/breeds",
                        breedCreate("푸들", "Poodle", 100),
                        session.csrfToken())
                .statusCode());
        assertEquals(400, postJson(
                        session.client(),
                        "/api/admin/breeds",
                        breedCreate("푸들", " poodle ", 100),
                        session.csrfToken())
                .statusCode());

        assertEquals(201, createBreed(session, "푸들", "poodle", 100).statusCode());
        var duplicate = createBreed(session, "다른 푸들", "poodle", 10);
        assertEquals(409, duplicate.statusCode());
        assertTrue(duplicate.body().contains("SLUG_CONFLICT"));

        var injected = """
                {
                  "id": "%s",
                  "status": "published",
                  "name": "말티즈",
                  "slug": "maltese",
                  "description": null,
                  "sortOrder": 100,
                  "createdBy": "%s"
                }
                """.formatted(UUID.randomUUID(), adminB.getId());
        var injectionResponse = postJson(
                session.client(), "/api/admin/breeds", injected, session.csrfToken());

        assertEquals(400, injectionResponse.statusCode());
        assertTrue(injectionResponse.body().contains("INVALID_REQUEST"));
    }

    @Test
    void should_rejectSlugAndAuditFields_when_fullUpdateContainsImmutableFields() throws Exception {
        var session = login(ADMIN_A_EMAIL);
        var created = createBreed(session, "푸들", "poodle", 100);
        var id = extractId(created.body());
        var updatedAt = jdbcTemplate.queryForObject(
                "SELECT updated_at FROM breeds WHERE id = ?", java.time.OffsetDateTime.class, id);
        var injected = """
                {
                  "status": "published",
                  "name": "푸들",
                  "slug": "renamed-poodle",
                  "description": null,
                  "sortOrder": 100,
                  "updatedAt": "2026-08-29T00:00:00Z"
                }
                """;

        var response = putJson(
                session.client(), "/api/admin/breeds/" + id, injected, session.csrfToken());

        assertEquals(400, response.statusCode());
        assertEquals("poodle", jdbcTemplate.queryForObject(
                "SELECT slug FROM breeds WHERE id = ?", String.class, id));
        assertEquals("draft", jdbcTemplate.queryForObject(
                "SELECT status FROM breeds WHERE id = ?", String.class, id));
        assertEquals(updatedAt, jdbcTemplate.queryForObject(
                "SELECT updated_at FROM breeds WHERE id = ?", java.time.OffsetDateTime.class, id));

        var invalidStatus = putJson(
                session.client(),
                "/api/admin/breeds/" + id,
                breedUpdate("PUBLISHED", "푸들", null, 100),
                session.csrfToken());
        assertEquals(400, invalidStatus.statusCode());
        assertEquals("draft", jdbcTemplate.queryForObject(
                "SELECT status FROM breeds WHERE id = ?", String.class, id));
    }

    @Test
    void should_validateFinalStateAndRollback_when_publishedServiceLosesRequiredFields() throws Exception {
        var session = login(ADMIN_A_EMAIL);
        var created = postJson(
                session.client(),
                "/api/admin/services",
                serviceCreate("전체미용", "full-grooming", null, null, 100),
                session.csrfToken());
        var id = extractId(created.body());

        var incomplete = putJson(
                session.client(),
                "/api/admin/services/" + id,
                serviceUpdate("published", "전체미용", null, null, 100),
                session.csrfToken());
        assertEquals(422, incomplete.statusCode());
        assertTrue(incomplete.body().contains("PUBLISH_VALIDATION_FAILED"));
        assertEquals("draft", jdbcTemplate.queryForObject(
                "SELECT status FROM services WHERE id = ?", String.class, id));

        var published = putJson(
                session.client(),
                "/api/admin/services/" + id,
                serviceUpdate("published", "전체미용", "견종별 전체 스타일", "상담 후 안내", 10),
                session.csrfToken());
        assertEquals(200, published.statusCode());

        var updatedAt = jdbcTemplate.queryForObject(
                "SELECT updated_at FROM services WHERE id = ?", java.time.OffsetDateTime.class, id);
        var invalidRemoval = putJson(
                session.client(),
                "/api/admin/services/" + id,
                serviceUpdate("published", "전체미용", "   ", "상담 후 안내", 10),
                session.csrfToken());

        assertEquals(422, invalidRemoval.statusCode());
        assertEquals("published", jdbcTemplate.queryForObject(
                "SELECT status FROM services WHERE id = ?", String.class, id));
        assertEquals("견종별 전체 스타일", jdbcTemplate.queryForObject(
                "SELECT description FROM services WHERE id = ?", String.class, id));
        assertEquals(updatedAt, jdbcTemplate.queryForObject(
                "SELECT updated_at FROM services WHERE id = ?", java.time.OffsetDateTime.class, id));

        var missingPrice = putJson(
                session.client(),
                "/api/admin/services/" + id,
                serviceUpdate("published", "전체미용", "견종별 전체 스타일", null, 10),
                session.csrfToken());
        assertEquals(422, missingPrice.statusCode());
    }

    @Test
    void should_keepArchivedRowsAndRestoreThem_when_fullRepresentationIsValid() throws Exception {
        var session = login(ADMIN_A_EMAIL);
        var created = postJson(
                session.client(),
                "/api/admin/services",
                serviceCreate("부분미용", "partial-grooming", "부분 관리", "상담 후 안내", 50),
                session.csrfToken());
        var id = extractId(created.body());

        var archived = putJson(
                session.client(),
                "/api/admin/services/" + id,
                serviceUpdate("archived", "부분미용", "부분 관리", "상담 후 안내", 50),
                session.csrfToken());
        var stillReadable = get(session.client(), "/api/admin/services/" + id);
        var restoredDraft = putJson(
                session.client(),
                "/api/admin/services/" + id,
                serviceUpdate("draft", "부분미용", null, null, 50),
                session.csrfToken());
        var restoredPublished = putJson(
                session.client(),
                "/api/admin/services/" + id,
                serviceUpdate("published", "부분미용", "부분 관리", "상담 후 안내", 50),
                session.csrfToken());

        assertEquals(200, archived.statusCode());
        assertTrue(archived.body().contains("\"status\":\"archived\""));
        assertEquals(200, stillReadable.statusCode());
        assertTrue(stillReadable.body().contains("\"status\":\"archived\""));
        assertEquals(200, restoredDraft.statusCode());
        assertTrue(restoredDraft.body().contains("\"status\":\"draft\""));
        assertEquals(200, restoredPublished.statusCode());
        assertTrue(restoredPublished.body().contains("\"status\":\"published\""));
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM services WHERE id = ?", Integer.class, id));
    }

    @Test
    void should_returnNotFoundAndKeepRow_when_idIsMissingOrUnsupportedMutationIsRequested()
            throws Exception {
        var session = login(ADMIN_A_EMAIL);
        var missingId = UUID.randomUUID();

        var missingGet = get(session.client(), "/api/admin/breeds/" + missingId);
        var missingPut = putJson(
                session.client(),
                "/api/admin/breeds/" + missingId,
                breedUpdate("draft", "푸들", null, 100),
                session.csrfToken());

        assertEquals(404, missingGet.statusCode());
        assertTrue(missingGet.body().contains("CONTENT_NOT_FOUND"));
        assertEquals(404, missingPut.statusCode());

        var created = createBreed(session, "푸들", "poodle", 100);
        var id = extractId(created.body());
        var delete = request(session.client(), "DELETE", "/api/admin/breeds/" + id, null, session.csrfToken());
        var patch = request(
                session.client(),
                "PATCH",
                "/api/admin/breeds/" + id,
                breedUpdate("archived", "푸들", null, 100),
                session.csrfToken());

        assertFalse(delete.statusCode() >= 200 && delete.statusCode() < 300);
        assertFalse(patch.statusCode() >= 200 && patch.statusCode() < 300);
        assertEquals(1, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM breeds WHERE id = ?", Integer.class, id));
    }

    private HttpResponse<String> createBreed(
            AuthenticatedSession session, String name, String slug, Integer sortOrder) throws Exception {
        return postJson(
                session.client(),
                "/api/admin/breeds",
                breedCreate(name, slug, sortOrder),
                session.csrfToken());
    }

    private AdminUser createAdmin(String email) {
        return adminUserRepository.saveAndFlush(
                AdminUser.create(email, passwordEncoder.encode(ADMIN_PASSWORD)));
    }

    private void clearFixtures() {
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

    private String breedCreate(String name, String slug, Integer sortOrder) {
        var sort = sortOrder == null ? "null" : sortOrder.toString();
        return """
                {"name":"%s","slug":"%s","description":null,"sortOrder":%s}
                """.formatted(name, slug, sort);
    }

    private String breedUpdate(String status, String name, String description, int sortOrder) {
        return """
                {"status":"%s","name":"%s","description":%s,"sortOrder":%d}
                """.formatted(status, name, jsonString(description), sortOrder);
    }

    private String serviceCreate(
            String name, String slug, String description, String priceText, Integer sortOrder) {
        var sort = sortOrder == null ? "null" : sortOrder.toString();
        return """
                {"name":"%s","slug":"%s","description":%s,"priceText":%s,"sortOrder":%s}
                """.formatted(name, slug, jsonString(description), jsonString(priceText), sort);
    }

    private String serviceUpdate(
            String status, String name, String description, String priceText, int sortOrder) {
        return """
                {"status":"%s","name":"%s","description":%s,"priceText":%s,"sortOrder":%d}
                """.formatted(
                status, name, jsonString(description), jsonString(priceText), sortOrder);
    }

    private String jsonString(String value) {
        return value == null ? "null" : "\"" + value + "\"";
    }

    private record TestClient(HttpClient httpClient, CookieManager cookieManager) {}

    private record AuthenticatedSession(TestClient client, String csrfToken) {}
}
