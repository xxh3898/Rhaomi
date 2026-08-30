package kr.co.rhaomi.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
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
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class BuildSecurityContractTests {

    private static final String BUILD_TOKEN =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String ADMIN_EMAIL = "build.security@example.com";
    private static final String ADMIN_PASSWORD = "local-build-security-password-123!";
    private static final Pattern CSRF_TOKEN_PATTERN =
            Pattern.compile("\\\"token\\\"\\s*:\\s*\\\"([^\\\"]+)\\\"");

    @LocalServerPort
    private int port;

    @Autowired
    private AdminUserRepository adminUserRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @BeforeEach
    void setUpAdmin() {
        clearAdmin();
        adminUserRepository.saveAndFlush(
                AdminUser.create(ADMIN_EMAIL, passwordEncoder.encode(ADMIN_PASSWORD)));
    }

    @AfterEach
    void clearAdminAfterTest() {
        clearAdmin();
    }

    @Test
    void should_authenticateOnlyExactBearerTokenAndNeverCreateSession_when_buildGetIsRequested()
            throws Exception {
        var client = newClient();
        var path = "/api/build/snapshot?publishGeneration=1";

        var missing = send(client, "GET", path, null, null);
        var wrong = send(client, "GET", path, "Bearer " + "f".repeat(64), null);
        assertError(missing, 401, "BUILD_UNAUTHORIZED");
        assertEquals(missing.body(), wrong.body());

        for (var malformed : List.of(
                "Basic " + BUILD_TOKEN,
                "bearer " + BUILD_TOKEN,
                "Bearer",
                "Bearer  " + BUILD_TOKEN,
                "Bearer " + BUILD_TOKEN.substring(1),
                "Bearer " + BUILD_TOKEN.toUpperCase())) {
            assertError(
                    send(client, "GET", path, malformed, null),
                    401,
                    "BUILD_UNAUTHORIZED");
        }

        var valid = send(client, "GET", path, "Bearer " + BUILD_TOKEN, null);
        assertError(valid, 409, "BUILD_GENERATION_NOT_ACTIVE");
        assertTrue(valid.headers().allValues("set-cookie").isEmpty());
        assertFalse(valid.body().contains(BUILD_TOKEN));
    }

    @Test
    void should_rejectDuplicateAuthorizationAndKeepAdminAndBuildPrincipalsSeparated()
            throws Exception {
        var duplicateRequest = HttpRequest.newBuilder(uri("/api/build/snapshot?publishGeneration=1"))
                .header("Authorization", "Bearer " + BUILD_TOKEN)
                .header("Authorization", "Bearer " + BUILD_TOKEN)
                .GET()
                .build();
        assertError(
                newClient().send(duplicateRequest, HttpResponse.BodyHandlers.ofString()),
                401,
                "BUILD_UNAUTHORIZED");

        var adminClient = login();
        assertError(
                send(
                        adminClient,
                        "GET",
                        "/api/build/snapshot?publishGeneration=1",
                        null,
                        null),
                401,
                "BUILD_UNAUTHORIZED");

        var buildOnly = send(
                newClient(),
                "GET",
                "/api/admin/auth/me",
                "Bearer " + BUILD_TOKEN,
                null);
        assertError(buildOnly, 401, "UNAUTHORIZED");
    }

    @Test
    void should_denyEveryNonGetAndUnknownBuildPath_when_tokenIsValid() throws Exception {
        var client = newClient();
        var snapshot = "/api/build/snapshot?publishGeneration=1";
        for (var method : List.of("POST", "PUT", "PATCH", "DELETE")) {
            assertError(
                    send(client, method, snapshot, "Bearer " + BUILD_TOKEN, "{}"),
                    403,
                    "BUILD_FORBIDDEN");
        }
        assertError(
                send(
                        client,
                        "GET",
                        "/api/build/unknown?publishGeneration=1",
                        "Bearer " + BUILD_TOKEN,
                        null),
                403,
                "BUILD_FORBIDDEN");
    }

    @Test
    void should_requireNoCsrfForBuildGetAndPreserveAdminMutationCsrf_when_chainsAreSeparated()
            throws Exception {
        assertError(
                send(
                        newClient(),
                        "GET",
                        "/api/build/snapshot?publishGeneration=1",
                        "Bearer " + BUILD_TOKEN,
                        null),
                409,
                "BUILD_GENERATION_NOT_ACTIVE");

        var adminClient = login();
        assertError(
                send(adminClient, "PUT", "/api/admin/shop-settings", null, "{}"),
                403,
                "FORBIDDEN");
    }

    private HttpClient newClient() {
        return HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .connectTimeout(Duration.ofSeconds(5))
                .build();
    }

    private HttpClient login() throws Exception {
        var client = newClient();
        var csrfResponse = send(client, "GET", "/api/admin/auth/csrf", null, null);
        var matcher = CSRF_TOKEN_PATTERN.matcher(csrfResponse.body());
        assertTrue(matcher.find());
        var login = send(
                client,
                "POST",
                "/api/admin/auth/login",
                null,
                "{\"email\":\"" + ADMIN_EMAIL + "\",\"password\":\"" + ADMIN_PASSWORD + "\"}",
                matcher.group(1));
        assertEquals(200, login.statusCode());
        return client;
    }

    private HttpResponse<String> send(
            HttpClient client, String method, String path, String authorization, String body)
            throws Exception {
        return send(client, method, path, authorization, body, null);
    }

    private HttpResponse<String> send(
            HttpClient client,
            String method,
            String path,
            String authorization,
            String body,
            String csrfToken)
            throws Exception {
        var publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8);
        var request = HttpRequest.newBuilder(uri(path))
                .timeout(Duration.ofSeconds(10))
                .method(method, publisher);
        if (authorization != null) {
            request.header("Authorization", authorization);
        }
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        if (csrfToken != null) {
            request.header("X-CSRF-TOKEN", csrfToken);
        }
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private void assertError(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"code\":\"" + code + "\""), response.body());
        assertFalse(response.body().contains(BUILD_TOKEN));
        assertFalse(response.body().toLowerCase().contains("exception"));
    }

    private void clearAdmin() {
        adminUserRepository.findByEmail(ADMIN_EMAIL).ifPresent(adminUserRepository::delete);
        adminUserRepository.flush();
    }
}
