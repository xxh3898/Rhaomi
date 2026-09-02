package kr.co.rhaomi.backend;

import static kr.co.rhaomi.backend.media.MediaTestFixtures.resource;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.net.CookieManager;
import java.net.CookiePolicy;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.UUID;
import java.util.regex.Pattern;
import kr.co.rhaomi.backend.admin.AdminUser;
import kr.co.rhaomi.backend.admin.AdminUserRepository;
import kr.co.rhaomi.backend.media.MediaProperties;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = "rhaomi.media.max-stored-bytes=32")
@ActiveProfiles("test")
class MediaStoredLimitApiContractTests {

    private static final String ADMIN_EMAIL = "media.stored.limit@example.com";
    private static final String ADMIN_PASSWORD = "local-media-limit-password";
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
    private MediaProperties mediaProperties;

    @BeforeEach
    void setUpAdmin() throws Exception {
        clearFixtures();
        adminUserRepository.saveAndFlush(
                AdminUser.create(ADMIN_EMAIL, passwordEncoder.encode(ADMIN_PASSWORD)));
    }

    @AfterEach
    void clearAdmin() throws Exception {
        clearFixtures();
    }

    @Test
    void should_return422MediaInvalidImage_when_normalizedOutputExceedsStoredLimit()
            throws Exception {
        var client = newClient();
        var csrf = fetchCsrf(client);
        login(client, csrf);
        var boundary = "RhaomiLimitBoundary" + UUID.randomUUID().toString().replace("-", "");
        var body = multipartBody(
                boundary, resource("synthetic-orientation-metadata.heic"));
        var request = HttpRequest.newBuilder(uri("/api/admin/media"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .header("X-CSRF-TOKEN", csrf)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body))
                .build();

        var response = client.send(request, HttpResponse.BodyHandlers.ofString());

        assertEquals(422, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"code\":\"MEDIA_INVALID_IMAGE\""));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM media_assets", Integer.class));
        assertEquals(0, regularFileCount(mediaRoot().resolve("temp")));
        assertEquals(0, regularFileCount(mediaRoot().resolve("masters")));
    }

    private void login(HttpClient client, String csrf) throws Exception {
        var request = HttpRequest.newBuilder(uri("/api/admin/auth/login"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("X-CSRF-TOKEN", csrf)
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"email\":\"" + ADMIN_EMAIL + "\",\"password\":\"" + ADMIN_PASSWORD + "\"}"))
                .build();
        assertEquals(200, client.send(request, HttpResponse.BodyHandlers.ofString()).statusCode());
    }

    private String fetchCsrf(HttpClient client) throws Exception {
        var response = client.send(
                HttpRequest.newBuilder(uri("/api/admin/auth/csrf")).GET().build(),
                HttpResponse.BodyHandlers.ofString());
        var matcher = CSRF_TOKEN_PATTERN.matcher(response.body());
        assertTrue(matcher.find());
        return matcher.group(1);
    }

    private byte[] multipartBody(String boundary, byte[] bytes) throws Exception {
        try (var output = new ByteArrayOutputStream()) {
            output.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.US_ASCII));
            output.write(
                    "Content-Disposition: form-data; name=\"file\"; filename=\"source.heic\"\r\n"
                            .getBytes(StandardCharsets.US_ASCII));
            output.write("Content-Type: image/heic\r\n\r\n".getBytes(StandardCharsets.US_ASCII));
            output.write(bytes);
            output.write(("\r\n--" + boundary + "--\r\n")
                    .getBytes(StandardCharsets.US_ASCII));
            return output.toByteArray();
        }
    }

    private HttpClient newClient() {
        return HttpClient.newBuilder()
                .cookieHandler(new CookieManager(null, CookiePolicy.ACCEPT_ALL))
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private long regularFileCount(Path root) throws Exception {
        if (!Files.exists(root)) {
            return 0;
        }
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).count();
        }
    }

    private Path mediaRoot() {
        return Path.of(mediaProperties.root()).toAbsolutePath().normalize();
    }

    private void clearFixtures() throws Exception {
        jdbcTemplate.update("DELETE FROM media_assets");
        adminUserRepository.findByEmail(ADMIN_EMAIL).ifPresent(adminUserRepository::delete);
        adminUserRepository.flush();
        clearDirectoryContents(mediaRoot().resolve("temp"));
        clearDirectoryContents(mediaRoot().resolve("masters"));
    }

    private void clearDirectoryContents(Path directory) throws Exception {
        if (!Files.isDirectory(directory)) {
            return;
        }
        var paths = new ArrayList<Path>();
        try (var stream = Files.walk(directory)) {
            stream.filter(path -> !path.equals(directory)).forEach(paths::add);
        }
        paths.sort(Comparator.reverseOrder());
        for (var path : paths) {
            Files.deleteIfExists(path);
        }
    }
}
