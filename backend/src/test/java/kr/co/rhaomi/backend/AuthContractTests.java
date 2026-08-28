package kr.co.rhaomi.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.HttpCookie;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
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
class AuthContractTests {

    private static final String ADMIN_EMAIL = "admin.contract@example.com";
    private static final String ADMIN_PASSWORD = "local-test-password-123!";
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

    @BeforeEach
    void clearContractAdminBeforeTest() {
        clearContractAdmin();
    }

    @AfterEach
    void clearContractAdminAfterTest() {
        clearContractAdmin();
    }

    @Test
    void createsFlywaySchemaAndStoresOnlyPasswordHash() {
        var admin = createAdmin(true);

        var columnCount = jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM information_schema.columns
                WHERE table_schema = 'public' AND table_name = 'admin_users'
                """,
                Integer.class);

        assertEquals(7, columnCount);
        assertNotEquals(ADMIN_PASSWORD, admin.getPasswordHash());
        assertTrue(passwordEncoder.matches(ADMIN_PASSWORD, admin.getPasswordHash()));
        assertNotNull(admin.getCreatedAt());
        assertNotNull(admin.getUpdatedAt());
    }

    @Test
    void rejectsMissingCsrfBeforeCredentialValidation() throws Exception {
        createAdmin(true);
        var client = newClient();

        var response = postJson(client, "/api/admin/auth/login", loginBody(ADMIN_EMAIL, ADMIN_PASSWORD), null);

        assertEquals(403, response.statusCode());
        assertTrue(response.body().contains("FORBIDDEN"));
    }

    @Test
    void returnsSameFailureForWrongAndInactiveCredentials() throws Exception {
        createAdmin(true);
        var wrongClient = newClient();
        var wrongCsrf = fetchCsrf(wrongClient).token();
        var wrong = postJson(
                wrongClient,
                "/api/admin/auth/login",
                loginBody(ADMIN_EMAIL, "wrong-local-password"),
                wrongCsrf);

        clearContractAdmin();
        createAdmin(false);
        var inactiveClient = newClient();
        var inactiveCsrf = fetchCsrf(inactiveClient).token();
        var inactive = postJson(
                inactiveClient,
                "/api/admin/auth/login",
                loginBody(ADMIN_EMAIL, ADMIN_PASSWORD),
                inactiveCsrf);

        assertEquals(401, wrong.statusCode());
        assertEquals(401, inactive.statusCode());
        assertEquals(wrong.body(), inactive.body());
        assertTrue(wrong.body().contains("INVALID_CREDENTIALS"));
    }

    @Test
    void authenticatesWithSessionAndInvalidatesItOnLogout() throws Exception {
        var admin = createAdmin(true);
        var anonymous = newClient();

        assertEquals(401, get(anonymous, "/api/admin/auth/me").statusCode());

        var client = newClient();
        var csrfResponse = fetchCsrf(client);
        var beforeLoginSession = sessionCookie(client).getValue();
        var setCookie = String.join(";", csrfResponse.response().headers().allValues("set-cookie"));

        assertTrue(sessionCookie(client).isHttpOnly());
        assertTrue(setCookie.toLowerCase().contains("samesite=lax"));

        var login = postJson(
                client,
                "/api/admin/auth/login",
                loginBody(ADMIN_EMAIL, ADMIN_PASSWORD),
                csrfResponse.token());

        assertEquals(200, login.statusCode());
        assertNotEquals(beforeLoginSession, sessionCookie(client).getValue());
        assertTrue(login.body().contains(admin.getId().toString()));
        assertTrue(login.body().contains(ADMIN_EMAIL));
        assertTrue(login.body().contains("ADMIN"));
        assertFalse(login.body().toLowerCase().contains("password"));
        assertFalse(login.body().toLowerCase().contains("hash"));

        var me = get(client, "/api/admin/auth/me");
        assertEquals(200, me.statusCode());
        assertFalse(me.body().toLowerCase().contains("password"));
        assertFalse(me.body().toLowerCase().contains("hash"));

        var logoutWithoutCsrf = postJson(client, "/api/admin/auth/logout", "", null);
        assertEquals(403, logoutWithoutCsrf.statusCode());
        assertEquals(200, get(client, "/api/admin/auth/me").statusCode());

        var logout = postJson(client, "/api/admin/auth/logout", "", csrfResponse.token());
        assertEquals(204, logout.statusCode());
        assertEquals(401, get(client, "/api/admin/auth/me").statusCode());
    }

    @Test
    void exposesOnlyMinimalAnonymousHealthAndCsrfSurface() throws Exception {
        var client = newClient();

        assertEquals(200, get(client, "/actuator/health").statusCode());
        assertEquals(401, get(client, "/actuator").statusCode());
        assertEquals(200, fetchCsrf(client).response().statusCode());
        assertEquals(401, get(client, "/api/admin/unknown").statusCode());
        assertEquals(401, get(client, "/api/build/unknown").statusCode());
    }

    private AdminUser createAdmin(boolean active) {
        var admin = AdminUser.create(ADMIN_EMAIL, passwordEncoder.encode(ADMIN_PASSWORD));
        if (!active) {
            admin.deactivate();
        }
        return adminUserRepository.saveAndFlush(admin);
    }

    private void clearContractAdmin() {
        adminUserRepository.findByEmail(ADMIN_EMAIL).ifPresent(adminUserRepository::delete);
        adminUserRepository.flush();
    }

    private TestClient newClient() {
        var cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        var httpClient = HttpClient.newBuilder()
                .cookieHandler(cookieManager)
                .connectTimeout(Duration.ofSeconds(5))
                .build();
        return new TestClient(httpClient, cookieManager);
    }

    private CsrfResult fetchCsrf(TestClient client) throws Exception {
        var response = get(client, "/api/admin/auth/csrf");
        assertEquals(200, response.statusCode());
        var matcher = CSRF_TOKEN_PATTERN.matcher(response.body());
        assertTrue(matcher.find());
        return new CsrfResult(matcher.group(1), response);
    }

    private HttpCookie sessionCookie(TestClient client) {
        return client.cookieManager().getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals("RHAOMI_SESSION"))
                .findFirst()
                .orElseThrow();
    }

    private HttpResponse<String> get(TestClient client, String path) throws Exception {
        var request = HttpRequest.newBuilder(uri(path)).GET().build();
        return client.httpClient().send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<String> postJson(
            TestClient client, String path, String body, String csrfToken) throws Exception {
        var request = HttpRequest.newBuilder(uri(path))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (csrfToken != null) {
            request.header("X-CSRF-TOKEN", csrfToken);
        }
        return client.httpClient().send(
                request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private String loginBody(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    private record TestClient(HttpClient httpClient, CookieManager cookieManager) {}

    private record CsrfResult(String token, HttpResponse<String> response) {}
}
