package kr.co.rhaomi.backend.auth;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
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
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class AdminLoginRateLimitApiIntegrationTests {

    private static final Pattern CSRF_TOKEN_PATTERN =
            Pattern.compile("\\\"token\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final Pattern POSITIVE_INTEGER_PATTERN = Pattern.compile("[1-9][0-9]*");
    private static final String ACTIVE_EMAIL = "active-rate-limit@example.com";
    private static final String MISSING_EMAIL = "missing-rate-limit@example.com";
    private static final String ACTIVE_PASSWORD = "synthetic-active-password";
    private static final String WRONG_PASSWORD = "synthetic-wrong-password";
    private static final String RATE_LIMITED_MESSAGE =
            "로그인 시도가 많습니다. 잠시 후 다시 시도해 주세요.";

    @LocalServerPort
    private int port;

    @Autowired
    private AdminUserRepository adminUserRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private AdminLoginRateLimiter loginRateLimiter;

    @BeforeEach
    void clearRateLimitAdminsBeforeTest() {
        clearRateLimitAdmins();
    }

    @AfterEach
    void clearRateLimitAdminsAfterTest() {
        clearRateLimitAdmins();
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void should_consumeQuotaOnlyAfterCsrfAndBeanValidationPass() throws Exception {
        var client = newClient();
        var csrfToken = fetchCsrf(client);
        var initialSessionId = sessionCookie(client).getValue();

        for (var index = 0; index < 6; index++) {
            var csrfRejected = postJson(client, loginBody(MISSING_EMAIL, WRONG_PASSWORD), null);
            assertEquals(403, csrfRejected.statusCode());
        }

        for (var index = 0; index < 6; index++) {
            var validationRejected =
                    postJson(client, loginBody("not-an-email", WRONG_PASSWORD), csrfToken);
            assertEquals(400, validationRejected.statusCode());
            assertTrue(validationRejected.body().contains("INVALID_REQUEST"));
        }

        for (var index = 0; index < 5; index++) {
            var credentialRejected =
                    postJson(client, loginBody(MISSING_EMAIL, WRONG_PASSWORD), csrfToken);
            assertEquals(401, credentialRejected.statusCode());
            assertTrue(credentialRejected.body().contains("INVALID_CREDENTIALS"));
        }

        var limited = postJson(client, loginBody(MISSING_EMAIL, WRONG_PASSWORD), csrfToken);
        var retryAfter = limited.headers().firstValue("Retry-After").orElseThrow();

        assertEquals(429, limited.statusCode());
        assertTrue(Long.parseLong(retryAfter) > 0);
        assertTrue(limited.body().contains("LOGIN_RATE_LIMITED"));
        assertTrue(limited.body().contains(RATE_LIMITED_MESSAGE));
        assertFalse(limited.body().contains(MISSING_EMAIL));
        assertFalse(limited.body().contains(WRONG_PASSWORD));
        assertEquals(initialSessionId, sessionCookie(client).getValue());
    }

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void should_returnSame429Semantics_when_activeAndMissingAccountsExhaustQuota()
            throws Exception {
        var activeAdmin = adminUserRepository.saveAndFlush(
                AdminUser.create(ACTIVE_EMAIL, passwordEncoder.encode(ACTIVE_PASSWORD)));
        assertTrue(activeAdmin.isActive());
        assertTrue(adminUserRepository.findByEmail(ACTIVE_EMAIL).isPresent());
        assertTrue(adminUserRepository.findByEmail(MISSING_EMAIL).isEmpty());

        var activeAccount = exhaustIdentifierQuota(ACTIVE_EMAIL, WRONG_PASSWORD);
        loginRateLimiter.resetForTesting();
        var missingAccount = exhaustIdentifierQuota(MISSING_EMAIL, WRONG_PASSWORD);

        assertEquals(activeAccount.credentialFailureBody(), missingAccount.credentialFailureBody());
        assertEquals(
                activeAccount.limitedResponse().statusCode(),
                missingAccount.limitedResponse().statusCode());
        assertEquals(activeAccount.limitedResponse().body(), missingAccount.limitedResponse().body());
        assertEquals(
                activeAccount.limitedResponse().headers().firstValue("Content-Type"),
                missingAccount.limitedResponse().headers().firstValue("Content-Type"));

        assertRateLimited(activeAccount.limitedResponse());
        assertRateLimited(missingAccount.limitedResponse());
    }

    private QuotaExhaustionResult exhaustIdentifierQuota(String email, String password)
            throws Exception {
        var client = newClient();
        var csrfToken = fetchCsrf(client);
        String credentialFailureBody = null;

        for (var index = 0; index < 5; index++) {
            var credentialRejected = postJson(client, loginBody(email, password), csrfToken);
            assertEquals(401, credentialRejected.statusCode());
            assertTrue(credentialRejected.body().contains("INVALID_CREDENTIALS"));
            if (credentialFailureBody == null) {
                credentialFailureBody = credentialRejected.body();
            } else {
                assertEquals(credentialFailureBody, credentialRejected.body());
            }
        }

        var limited = postJson(client, loginBody(email, password), csrfToken);
        return new QuotaExhaustionResult(credentialFailureBody, limited);
    }

    private void assertRateLimited(HttpResponse<String> response) {
        var retryAfterValues = response.headers().allValues("Retry-After");

        assertEquals(429, response.statusCode());
        assertEquals(1, retryAfterValues.size());
        assertTrue(POSITIVE_INTEGER_PATTERN.matcher(retryAfterValues.getFirst()).matches());
        assertTrue(Long.parseLong(retryAfterValues.getFirst()) > 0);
        assertTrue(response.body().contains("LOGIN_RATE_LIMITED"));
        assertTrue(response.body().contains(RATE_LIMITED_MESSAGE));
        assertFalse(response.body().contains("INVALID_CREDENTIALS"));
        assertFalse(response.body().contains(ACTIVE_EMAIL));
        assertFalse(response.body().contains(MISSING_EMAIL));
        assertFalse(response.body().contains(ACTIVE_PASSWORD));
        assertFalse(response.body().contains(WRONG_PASSWORD));
    }

    private void clearRateLimitAdmins() {
        adminUserRepository.findByEmail(ACTIVE_EMAIL).ifPresent(adminUserRepository::delete);
        adminUserRepository.findByEmail(MISSING_EMAIL).ifPresent(adminUserRepository::delete);
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

    private String fetchCsrf(TestClient client) throws Exception {
        var response = client.httpClient().send(
                HttpRequest.newBuilder(uri("/api/admin/auth/csrf")).GET().build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        assertEquals(200, response.statusCode());
        var matcher = CSRF_TOKEN_PATTERN.matcher(response.body());
        assertTrue(matcher.find());
        return matcher.group(1);
    }

    private HttpResponse<String> postJson(TestClient client, String body, String csrfToken)
            throws Exception {
        var request = HttpRequest.newBuilder(uri("/api/admin/auth/login"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
        if (csrfToken != null) {
            request.header("X-CSRF-TOKEN", csrfToken);
        }
        return client.httpClient().send(
                request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpCookie sessionCookie(TestClient client) {
        return client.cookieManager().getCookieStore().getCookies().stream()
                .filter(cookie -> cookie.getName().equals("RHAOMI_SESSION"))
                .findFirst()
                .orElseThrow();
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private String loginBody(String email, String password) {
        return "{\"email\":\"" + email + "\",\"password\":\"" + password + "\"}";
    }

    private record TestClient(HttpClient httpClient, CookieManager cookieManager) {}

    private record QuotaExhaustionResult(
            String credentialFailureBody, HttpResponse<String> limitedResponse) {}
}
