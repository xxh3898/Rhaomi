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
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@DirtiesContext(classMode = DirtiesContext.ClassMode.BEFORE_CLASS)
class AdminLoginRateLimitApiIntegrationTests {

    private static final Pattern CSRF_TOKEN_PATTERN =
            Pattern.compile("\\\"token\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");
    private static final String VALID_EMAIL = "missing-rate-limit@example.com";
    private static final String PASSWORD = "synthetic-password";

    @LocalServerPort
    private int port;

    @Test
    @DirtiesContext(methodMode = DirtiesContext.MethodMode.AFTER_METHOD)
    void should_consumeQuotaOnlyAfterCsrfAndBeanValidationPass() throws Exception {
        var client = newClient();
        var csrfToken = fetchCsrf(client);
        var initialSessionId = sessionCookie(client).getValue();

        for (var index = 0; index < 6; index++) {
            var csrfRejected = postJson(client, loginBody(VALID_EMAIL, PASSWORD), null);
            assertEquals(403, csrfRejected.statusCode());
        }

        for (var index = 0; index < 6; index++) {
            var validationRejected = postJson(client, loginBody("not-an-email", PASSWORD), csrfToken);
            assertEquals(400, validationRejected.statusCode());
            assertTrue(validationRejected.body().contains("INVALID_REQUEST"));
        }

        for (var index = 0; index < 5; index++) {
            var credentialRejected = postJson(client, loginBody(VALID_EMAIL, PASSWORD), csrfToken);
            assertEquals(401, credentialRejected.statusCode());
            assertTrue(credentialRejected.body().contains("INVALID_CREDENTIALS"));
        }

        var limited = postJson(client, loginBody(VALID_EMAIL, PASSWORD), csrfToken);
        var retryAfter = limited.headers().firstValue("Retry-After").orElseThrow();

        assertEquals(429, limited.statusCode());
        assertTrue(Long.parseLong(retryAfter) > 0);
        assertTrue(limited.body().contains("LOGIN_RATE_LIMITED"));
        assertTrue(limited.body().contains("로그인 시도가 많습니다. 잠시 후 다시 시도해 주세요."));
        assertFalse(limited.body().contains(VALID_EMAIL));
        assertFalse(limited.body().contains(PASSWORD));
        assertEquals(initialSessionId, sessionCookie(client).getValue());
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
}
