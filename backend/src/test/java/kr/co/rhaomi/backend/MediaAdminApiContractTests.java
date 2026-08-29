package kr.co.rhaomi.backend;

import static kr.co.rhaomi.backend.media.MediaTestFixtures.apng;
import static kr.co.rhaomi.backend.media.MediaTestFixtures.avifHeader;
import static kr.co.rhaomi.backend.media.MediaTestFixtures.jpeg;
import static kr.co.rhaomi.backend.media.MediaTestFixtures.isoBmffHeader;
import static kr.co.rhaomi.backend.media.MediaTestFixtures.oversizedPngHeader;
import static kr.co.rhaomi.backend.media.MediaTestFixtures.resource;
import static kr.co.rhaomi.backend.media.MediaTestFixtures.sha256;
import static kr.co.rhaomi.backend.media.MediaTestFixtures.truncatedHeic;
import static kr.co.rhaomi.backend.media.MediaTestFixtures.truncatedHeif;
import static kr.co.rhaomi.backend.media.MediaTestFixtures.withIsoBmffMajorBrand;
import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Color;
import java.io.ByteArrayInputStream;
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
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
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

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class MediaAdminApiContractTests {

    private static final String ADMIN_EMAIL = "media.contract@example.com";
    private static final String ADMIN_PASSWORD = "local-media-password-123!";
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

    @Autowired
    private MediaProperties mediaProperties;

    private AdminUser admin;

    @BeforeEach
    void setUpFixtures() throws Exception {
        clearFixtures();
        admin = adminUserRepository.saveAndFlush(
                AdminUser.create(ADMIN_EMAIL, passwordEncoder.encode(ADMIN_PASSWORD)));
    }

    @AfterEach
    void clearFixturesAfterTest() throws Exception {
        clearFixtures();
    }

    @Test
    void should_rejectAnonymousAndMissingCsrf_when_mediaEndpointsAreProtected() throws Exception {
        var anonymous = newClient();
        var id = UUID.randomUUID();

        assertEquals(401, get(anonymous, "/api/admin/media").statusCode());
        assertEquals(401, get(anonymous, "/api/admin/media/" + id).statusCode());
        assertEquals(401, getBytes(anonymous, "/api/admin/media/" + id + "/content").statusCode());
        assertEquals(401, get(anonymous, "/api/media/" + id).statusCode());
        assertEquals(401, get(anonymous, "/api/build/media/" + id).statusCode());

        var anonymousCsrf = fetchCsrf(anonymous);
        assertEquals(401, upload(
                        anonymous,
                        anonymousCsrf,
                        new Part("file", "photo.jpg", "image/jpeg", jpeg()))
                .statusCode());
        assertEquals(401, putJson(
                        anonymous,
                        "/api/admin/media/" + id,
                        "{\"status\":\"archived\"}",
                        anonymousCsrf)
                .statusCode());

        var session = login();
        assertEquals(403, get(session.client(), "/api/media/" + id).statusCode());
        assertEquals(403, get(session.client(), "/api/build/media/" + id).statusCode());
        assertEquals(403, upload(
                        session.client(),
                        null,
                        new Part("file", "photo.jpg", "image/jpeg", jpeg()))
                .statusCode());
        assertEquals(403, putJson(
                        session.client(),
                        "/api/admin/media/" + id,
                        "{\"status\":\"archived\"}",
                        null)
                .statusCode());
    }

    @Test
    void should_preserveJpegAndPngBytes_when_supportedRasterIsUploaded() throws Exception {
        var session = login();
        var jpeg = jpeg();
        var png = resource("synthetic-source.png");

        var jpegUpload = upload(
                session.client(),
                session.csrfToken(),
                new Part("file", "../../../../tmp/not-owned.jpg", "image/jpeg", jpeg));
        var pngUpload = upload(
                session.client(),
                session.csrfToken(),
                new Part("file", "upload", "application/octet-stream", png));

        assertEquals(201, jpegUpload.statusCode());
        assertEquals(201, pngUpload.statusCode());
        var jpegId = extractId(jpegUpload.body());
        var pngId = extractId(pngUpload.body());
        assertResponseExcludesPrivateMetadata(jpegUpload.body());
        assertResponseExcludesPrivateMetadata(pngUpload.body());
        assertTrue(jpegUpload.body().contains("\"status\":\"active\""));
        assertTrue(jpegUpload.body().contains("\"sourceContentType\":\"image/jpeg\""));
        assertTrue(jpegUpload.body().contains("\"contentType\":\"image/jpeg\""));
        assertTrue(pngUpload.body().contains("\"sourceContentType\":\"image/png\""));
        assertTrue(pngUpload.body().contains("\"contentType\":\"image/png\""));
        assertEquals("/api/admin/media/" + jpegId, jpegUpload.headers()
                .firstValue("location")
                .orElseThrow());

        var jpegContent = getBytes(session.client(), "/api/admin/media/" + jpegId + "/content");
        var pngContent = getBytes(session.client(), "/api/admin/media/" + pngId + "/content");
        assertPrivateContent(jpegContent, "image/jpeg", jpeg);
        assertPrivateContent(pngContent, "image/png", png);

        assertStoredFile(jpegId, "jpg", jpeg);
        assertStoredFile(pngId, "png", png);
        assertEquals(2, masterFileCount());
        assertEquals(0, tempFileCount());

        var detail = get(session.client(), "/api/admin/media/" + jpegId);
        var list = get(session.client(), "/api/admin/media");
        assertEquals(200, detail.statusCode());
        assertEquals(200, list.statusCode());
        assertTrue(detail.body().contains(jpegId.toString()));
        assertTrue(list.body().contains(jpegId.toString()));
        assertTrue(list.body().contains(pngId.toString()));
        assertResponseExcludesPrivateMetadata(detail.body());
        assertResponseExcludesPrivateMetadata(list.body());
    }

    @Test
    void should_normalizeHeicAndHeifToMetadataFreeSrgbJpeg_when_mobileSourceIsUploaded()
            throws Exception {
        var session = login();
        var heic = resource("synthetic-orientation-metadata.heic");
        var heif = resource("synthetic-orientation-metadata.heif");
        var sourceText = new String(heic, StandardCharsets.ISO_8859_1);
        assertTrue(sourceText.contains("Exif"));
        assertTrue(sourceText.contains("application/rdf+xml"));
        assertTrue(sourceText.contains("SYNTHETIC-ONLY"));

        var heicUpload = upload(
                session.client(),
                session.csrfToken(),
                new Part("file", "iphone.heic", "image/heic", heic));
        var heifUpload = upload(
                session.client(),
                session.csrfToken(),
                new Part("file", null, "", heif));

        assertEquals(201, heicUpload.statusCode());
        assertEquals(201, heifUpload.statusCode());
        assertTrue(heicUpload.body().contains("\"sourceContentType\":\"image/heic\""));
        assertTrue(heifUpload.body().contains("\"sourceContentType\":\"image/heif\""));
        assertTrue(heicUpload.body().contains("\"contentType\":\"image/jpeg\""));
        assertTrue(heicUpload.body().contains("\"width\":48"));
        assertTrue(heicUpload.body().contains("\"height\":64"));

        var heicId = extractId(heicUpload.body());
        var heifId = extractId(heifUpload.body());
        var heicContent = getBytes(session.client(), "/api/admin/media/" + heicId + "/content");
        var heifContent = getBytes(session.client(), "/api/admin/media/" + heifId + "/content");
        assertEquals(200, heicContent.statusCode());
        assertEquals("image/jpeg", heicContent.headers().firstValue("content-type").orElseThrow());
        assertCanonicalJpeg(heicContent.body());
        assertCanonicalJpeg(heifContent.body());

        var duplicate = upload(
                session.client(),
                session.csrfToken(),
                new Part("file", "duplicate.heic", "image/heic", heic));
        assertEquals(201, duplicate.statusCode());
        var duplicateId = extractId(duplicate.body());
        assertNotEquals(heicId, duplicateId);
        assertEquals(3, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM media_assets", Integer.class));
        assertEquals(
                jdbcTemplate.queryForObject(
                        "SELECT sha256 FROM media_assets WHERE id = ?", String.class, heicId),
                jdbcTemplate.queryForObject(
                        "SELECT sha256 FROM media_assets WHERE id = ?", String.class, duplicateId));
        assertNotEquals(
                jdbcTemplate.queryForObject(
                        "SELECT storage_key FROM media_assets WHERE id = ?", String.class, heicId),
                jdbcTemplate.queryForObject(
                        "SELECT storage_key FROM media_assets WHERE id = ?", String.class, duplicateId));
        assertEquals(3, masterFileCount());
        assertEquals(0, tempFileCount());
    }

    @Test
    void should_normalizeCompatibleBrandHeic_when_majorBrandIsMif1() throws Exception {
        var session = login();
        var source = withIsoBmffMajorBrand(
                resource("synthetic-orientation-metadata.heic"), "mif1");
        assertEquals("mif1", new String(source, 8, 4, StandardCharsets.US_ASCII));
        assertEquals("heic", new String(source, 20, 4, StandardCharsets.US_ASCII));

        var upload = upload(
                session.client(),
                session.csrfToken(),
                new Part(
                        "file",
                        "iphone-compatible-brand.heic",
                        "image/heic",
                        source));

        assertEquals(201, upload.statusCode());
        assertTrue(upload.body().contains("\"sourceContentType\":\"image/heic\""));
        assertTrue(upload.body().contains("\"contentType\":\"image/jpeg\""));
        assertTrue(upload.body().contains("\"width\":48"));
        assertTrue(upload.body().contains("\"height\":64"));

        var id = extractId(upload.body());
        var content = getBytes(session.client(), "/api/admin/media/" + id + "/content");
        assertEquals(200, content.statusCode());
        assertEquals("image/jpeg", content.headers().firstValue("content-type").orElseThrow());
        assertCanonicalJpeg(content.body());
        assertEquals(1, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM media_assets", Integer.class));
        assertEquals(1, masterFileCount());
        assertEquals(0, tempFileCount());
    }

    @Test
    void should_archiveRestoreAndListDeterministically_when_statusIsOnlyMutableField()
            throws Exception {
        var session = login();
        var first = upload(
                session.client(),
                session.csrfToken(),
                new Part("file", "first.jpg", "image/jpeg", jpeg()));
        var second = upload(
                session.client(),
                session.csrfToken(),
                new Part("file", "second.png", "image/png", resource("synthetic-source.png")));
        var firstId = extractId(first.body());
        var secondId = extractId(second.body());
        var firstContent = getBytes(session.client(), "/api/admin/media/" + firstId + "/content").body();
        var storageKey = storageKey(firstId);
        var createdAt = jdbcTemplate.queryForObject(
                "SELECT created_at FROM media_assets WHERE id = ?", OffsetDateTime.class, firstId);

        var archived = putJson(
                session.client(),
                "/api/admin/media/" + firstId,
                "{\"status\":\"archived\"}",
                session.csrfToken());
        assertEquals(200, archived.statusCode());
        assertTrue(archived.body().contains("\"status\":\"archived\""));
        assertArrayEquals(
                firstContent,
                getBytes(session.client(), "/api/admin/media/" + firstId + "/content").body());
        assertEquals(storageKey, storageKey(firstId));
        assertEquals(createdAt, jdbcTemplate.queryForObject(
                "SELECT created_at FROM media_assets WHERE id = ?", OffsetDateTime.class, firstId));

        var restored = putJson(
                session.client(),
                "/api/admin/media/" + firstId,
                "{\"status\":\"active\"}",
                session.csrfToken());
        assertEquals(200, restored.statusCode());
        assertTrue(restored.body().contains("\"status\":\"active\""));
        assertEquals(admin.getId(), jdbcTemplate.queryForObject(
                "SELECT updated_by FROM media_assets WHERE id = ?", UUID.class, firstId));

        var beforeRejectedUpdate = jdbcTemplate.queryForMap(
                "SELECT status, updated_at, updated_by FROM media_assets WHERE id = ?", firstId);
        for (var invalidBody : List.of(
                "{\"status\":\"archived\",\"id\":\"" + UUID.randomUUID() + "\"}",
                "{\"status\":\"deleted\"}",
                "{\"status\":\"active\"")) {
            var response = putJson(
                    session.client(),
                    "/api/admin/media/" + firstId,
                    invalidBody,
                    session.csrfToken());
            assertError(response, 400, "INVALID_REQUEST");
            assertEquals(beforeRejectedUpdate, jdbcTemplate.queryForMap(
                    "SELECT status, updated_at, updated_by FROM media_assets WHERE id = ?", firstId));
        }

        assertError(
                get(session.client(), "/api/admin/media/not-a-uuid"), 400, "INVALID_REQUEST");
        assertError(
                get(session.client(), "/api/admin/media/" + UUID.randomUUID()),
                404,
                "MEDIA_NOT_FOUND");

        var sameCreatedAt = OffsetDateTime.parse("2026-08-29T00:00:00Z");
        jdbcTemplate.update(
                "UPDATE media_assets SET created_at = ? WHERE id IN (?, ?)",
                sameCreatedAt,
                firstId,
                secondId);
        var ordered = List.of(firstId.toString(), secondId.toString()).stream().sorted().toList();
        var list = get(session.client(), "/api/admin/media");
        assertTrue(list.body().indexOf(ordered.get(0)) < list.body().indexOf(ordered.get(1)));

        var deleteRequest = HttpRequest.newBuilder(uri("/api/admin/media/" + firstId))
                .timeout(Duration.ofSeconds(10))
                .header("X-CSRF-TOKEN", session.csrfToken())
                .DELETE()
                .build();
        var delete = session.client().send(deleteRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(405, delete.statusCode());

        var patchRequest = HttpRequest.newBuilder(uri("/api/admin/media/" + firstId))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("X-CSRF-TOKEN", session.csrfToken())
                .method("PATCH", HttpRequest.BodyPublishers.ofString("{\"status\":\"archived\"}"))
                .build();
        var patch = session.client().send(patchRequest, HttpResponse.BodyHandlers.ofString());
        assertEquals(405, patch.statusCode());
        assertEquals(2, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM media_assets", Integer.class));
        assertEquals(2, masterFileCount());
    }

    @Test
    void should_returnFixedErrorsAndLeaveNoOrphans_when_uploadInputIsInvalid() throws Exception {
        var session = login();
        var validJpeg = jpeg();
        var validHeic = resource("synthetic-orientation-metadata.heic");

        assertError(upload(session.client(), session.csrfToken()), 400, "INVALID_REQUEST");
        assertError(
                upload(
                        session.client(),
                        session.csrfToken(),
                        new Part("file", "empty.jpg", "image/jpeg", new byte[0])),
                400,
                "INVALID_REQUEST");
        assertError(
                upload(
                        session.client(),
                        session.csrfToken(),
                        new Part("file", "photo.jpg", "image/jpeg", validJpeg),
                        new Part("unexpected", null, null, "value".getBytes(StandardCharsets.UTF_8))),
                400,
                "INVALID_REQUEST");

        for (var unsupported : List.of(
                new Part("file", "image.gif", "image/gif", "GIF89a".getBytes(StandardCharsets.US_ASCII)),
                new Part("file", "image.webp", "image/webp", "RIFF1234WEBP".getBytes(StandardCharsets.US_ASCII)),
                new Part("file", "image.svg", "image/svg+xml", "<svg/>".getBytes(StandardCharsets.US_ASCII)),
                new Part("file", "image.avif", "image/avif", avifHeader()),
                new Part("file", "wrong.heic", "image/heic", validJpeg),
                new Part("file", "wrong.jpg", "image/jpeg", validHeic),
                new Part("file", "wrong.png", "image/jpeg", validJpeg))) {
            assertError(
                    upload(session.client(), session.csrfToken(), unsupported),
                    415,
                    "MEDIA_TYPE_UNSUPPORTED");
        }

        for (var invalid : List.of(
                new Part("file", "corrupt.jpg", "image/jpeg", new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff}),
                new Part(
                        "file",
                        "corrupt.png",
                        "image/png",
                        Arrays.copyOf(resource("synthetic-source.png"), 16)),
                new Part("file", "corrupt.heic", "image/heic", truncatedHeic()),
                new Part("file", "corrupt.heif", "image/heif", truncatedHeif()),
                new Part("file", "animated.png", "image/png", apng()),
                new Part(
                        "file",
                        "multiple.heic",
                        "image/heic",
                        resource("synthetic-multiple-images.heic")),
                new Part(
                        "file",
                        "sequence.heic",
                        "image/heic",
                        resource("synthetic-sequence-branded.heic")),
                new Part("file", "wide.png", "image/png", oversizedPngHeader(12001, 1)),
                new Part("file", "pixels.png", "image/png", oversizedPngHeader(10000, 7000)))) {
            assertError(
                    upload(session.client(), session.csrfToken(), invalid),
                    422,
                    "MEDIA_INVALID_IMAGE");
        }

        var oversized = new byte[20 * 1024 * 1024 + 1];
        oversized[0] = (byte) 0xff;
        oversized[1] = (byte) 0xd8;
        oversized[2] = (byte) 0xff;
        assertError(
                upload(
                        session.client(),
                        session.csrfToken(),
                        new Part("file", "oversized.jpg", "image/jpeg", oversized)),
                413,
                "MEDIA_TOO_LARGE");

        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM media_assets", Integer.class));
        assertEquals(0, masterFileCount());
        assertEquals(0, tempFileCount());
    }

    @Test
    void should_mapHeifBrandTaxonomyToFixedErrors_when_containerHasNoDecodableImage()
            throws Exception {
        var session = login();

        for (var stillBrand : List.of("heic", "heix", "heim", "heis")) {
            assertBrandUploadError(
                    session,
                    stillBrand,
                    "image/heic",
                    "heic",
                    422,
                    "MEDIA_INVALID_IMAGE");
        }
        for (var sequenceBrand : List.of("hevc", "hevx", "hevm", "hevs")) {
            assertBrandUploadError(
                    session,
                    sequenceBrand,
                    "image/heic",
                    "heic",
                    422,
                    "MEDIA_INVALID_IMAGE");
        }
        assertBrandUploadError(
                session, "msf1", "image/heif", "heif", 422, "MEDIA_INVALID_IMAGE");
        var compatibleStill = upload(
                session.client(),
                session.csrfToken(),
                new Part(
                        "file",
                        "compatible-brand.heic",
                        "image/heic",
                        isoBmffHeader("mif1", "mif1", "heic")));
        assertError(compatibleStill, 422, "MEDIA_INVALID_IMAGE");
        for (var avifBrand : List.of("avif", "avis")) {
            assertBrandUploadError(
                    session,
                    avifBrand,
                    "image/avif",
                    "avif",
                    415,
                    "MEDIA_TYPE_UNSUPPORTED");
        }

        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM media_assets", Integer.class));
        assertEquals(0, masterFileCount());
        assertEquals(0, tempFileCount());
    }

    @Test
    void should_removeFinalMasterAndHideDatabaseDetails_when_persistenceFails() throws Exception {
        var session = login();
        adminUserRepository.deleteById(admin.getId());
        adminUserRepository.flush();

        var response = upload(
                session.client(),
                session.csrfToken(),
                new Part("file", "persistence-failure.jpg", "image/jpeg", jpeg()));

        assertError(response, 500, "INTERNAL_ERROR");
        assertFalse(response.body().toLowerCase().contains("constraint"));
        assertFalse(response.body().contains(mediaProperties.root()));
        assertEquals(0, jdbcTemplate.queryForObject("SELECT COUNT(*) FROM media_assets", Integer.class));
        assertEquals(0, masterFileCount());
        assertEquals(0, tempFileCount());
    }

    @Test
    void should_returnGenericStorageFailure_when_masterIsMissingOrCorrupt() throws Exception {
        var session = login();
        var upload = upload(
                session.client(),
                session.csrfToken(),
                new Part("file", "missing-master.jpg", "image/jpeg", jpeg()));
        var id = extractId(upload.body());
        var privatePath = mediaRoot().resolve(storageKey(id));
        Files.delete(privatePath);

        var response = get(session.client(), "/api/admin/media/" + id + "/content");

        assertError(response, 500, "INTERNAL_ERROR");
        assertFalse(response.body().contains(privatePath.toString()));
        assertFalse(response.body().contains(storageKey(id)));

        var corruptUpload = upload(
                session.client(),
                session.csrfToken(),
                new Part("file", "corrupt-master.jpg", "image/jpeg", jpeg()));
        var corruptId = extractId(corruptUpload.body());
        var corruptStorageKey = storageKey(corruptId);
        var corruptPath = mediaRoot().resolve(corruptStorageKey);
        Files.write(corruptPath, new byte[] {(byte) 0xff, (byte) 0xd8, (byte) 0xff});

        var corruptResponse = get(session.client(), "/api/admin/media/" + corruptId + "/content");

        assertError(corruptResponse, 500, "INTERNAL_ERROR");
        assertFalse(corruptResponse.body().contains(corruptPath.toString()));
        assertFalse(corruptResponse.body().contains(corruptStorageKey));
    }

    private AuthenticatedSession login() throws Exception {
        var client = newClient();
        var csrf = fetchCsrf(client);
        var request = HttpRequest.newBuilder(uri("/api/admin/auth/login"))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .header("X-CSRF-TOKEN", csrf)
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"email\":\"" + ADMIN_EMAIL + "\",\"password\":\"" + ADMIN_PASSWORD + "\"}"))
                .build();
        var response = client.send(request, HttpResponse.BodyHandlers.ofString());
        assertEquals(200, response.statusCode());
        return new AuthenticatedSession(client, csrf);
    }

    private void assertBrandUploadError(
            AuthenticatedSession session,
            String brand,
            String contentType,
            String extension,
            int expectedStatus,
            String expectedCode)
            throws Exception {
        var response = upload(
                session.client(),
                session.csrfToken(),
                new Part(
                        "file",
                        "brand." + extension,
                        contentType,
                        isoBmffHeader(brand, "mif1", brand)));

        assertError(response, expectedStatus, expectedCode);
    }

    private String fetchCsrf(HttpClient client) throws Exception {
        var response = get(client, "/api/admin/auth/csrf");
        assertEquals(200, response.statusCode());
        var matcher = CSRF_TOKEN_PATTERN.matcher(response.body());
        assertTrue(matcher.find());
        return matcher.group(1);
    }

    private HttpResponse<String> upload(HttpClient client, String csrfToken, Part... parts)
            throws Exception {
        var boundary = "RhaomiBoundary" + UUID.randomUUID().toString().replace("-", "");
        var body = new ByteArrayOutputStream();
        for (var part : parts) {
            body.write(("--" + boundary + "\r\n").getBytes(StandardCharsets.US_ASCII));
            var disposition = "Content-Disposition: form-data; name=\"" + part.name() + "\"";
            if (part.filename() != null) {
                disposition += "; filename=\"" + part.filename() + "\"";
            }
            body.write((disposition + "\r\n").getBytes(StandardCharsets.UTF_8));
            if (part.contentType() != null && !part.contentType().isEmpty()) {
                body.write(("Content-Type: " + part.contentType() + "\r\n")
                        .getBytes(StandardCharsets.US_ASCII));
            }
            body.write("\r\n".getBytes(StandardCharsets.US_ASCII));
            body.write(part.bytes());
            body.write("\r\n".getBytes(StandardCharsets.US_ASCII));
        }
        body.write(("--" + boundary + "--\r\n").getBytes(StandardCharsets.US_ASCII));

        var builder = HttpRequest.newBuilder(uri("/api/admin/media"))
                .timeout(Duration.ofSeconds(30))
                .header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray()));
        if (csrfToken != null) {
            builder.header("X-CSRF-TOKEN", csrfToken);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> putJson(
            HttpClient client, String path, String body, String csrfToken) throws Exception {
        var builder = HttpRequest.newBuilder(uri(path))
                .timeout(Duration.ofSeconds(10))
                .header("Content-Type", "application/json")
                .PUT(HttpRequest.BodyPublishers.ofString(body));
        if (csrfToken != null) {
            builder.header("X-CSRF-TOKEN", csrfToken);
        }
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(HttpClient client, String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<byte[]> getBytes(HttpClient client, String path) throws Exception {
        return client.send(
                HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10)).GET().build(),
                HttpResponse.BodyHandlers.ofByteArray());
    }

    private HttpClient newClient() {
        var cookieManager = new CookieManager(null, CookiePolicy.ACCEPT_ALL);
        return HttpClient.newBuilder()
                .cookieHandler(cookieManager)
                .connectTimeout(Duration.ofSeconds(10))
                .build();
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private UUID extractId(String body) {
        var matcher = ID_PATTERN.matcher(body);
        assertTrue(matcher.find(), body);
        return UUID.fromString(matcher.group(1));
    }

    private void assertStoredFile(UUID id, String extension, byte[] expected) throws Exception {
        var key = storageKey(id);
        assertEquals("masters/" + id.toString().substring(0, 2) + "/" + id + "." + extension, key);
        var stored = Files.readAllBytes(mediaRoot().resolve(key));
        assertArrayEquals(expected, stored);
        assertEquals(sha256(expected), jdbcTemplate.queryForObject(
                "SELECT sha256 FROM media_assets WHERE id = ?", String.class, id));
    }

    private String storageKey(UUID id) {
        return jdbcTemplate.queryForObject(
                "SELECT storage_key FROM media_assets WHERE id = ?", String.class, id);
    }

    private void assertPrivateContent(
            HttpResponse<byte[]> response, String contentType, byte[] expected) {
        assertEquals(200, response.statusCode());
        assertEquals(contentType, response.headers().firstValue("content-type").orElseThrow());
        assertEquals(
                Integer.toString(expected.length),
                response.headers().firstValue("content-length").orElseThrow());
        assertEquals("private, no-store", response.headers().firstValue("cache-control").orElseThrow());
        assertEquals("nosniff", response.headers().firstValue("x-content-type-options").orElseThrow());
        assertArrayEquals(expected, response.body());
    }

    private void assertCanonicalJpeg(byte[] bytes) throws Exception {
        assertTrue(bytes.length > 4);
        assertEquals((byte) 0xff, bytes[0]);
        assertEquals((byte) 0xd8, bytes[1]);
        var text = new String(bytes, StandardCharsets.ISO_8859_1);
        for (var marker : List.of(
                "Exif", "application/rdf+xml", "SYNTHETIC-ONLY", "GPSLatitude", "DisplayP3")) {
            assertFalse(text.contains(marker), marker);
        }

        var image = ImageIO.read(new ByteArrayInputStream(bytes));
        assertNotNull(image);
        assertEquals(48, image.getWidth());
        assertEquals(64, image.getHeight());
        assertTrue(image.getColorModel().getColorSpace().isCS_sRGB());
        assertRedDominant(new Color(image.getRGB(5, 5)));
        assertBright(new Color(image.getRGB(image.getWidth() - 6, 5)));
        assertDark(new Color(image.getRGB(5, image.getHeight() - 6)));
        assertGreenDominant(new Color(
                image.getRGB(image.getWidth() - 6, image.getHeight() - 6)));
    }

    private void assertRedDominant(Color color) {
        assertTrue(color.getRed() > color.getGreen() + 60, color.toString());
        assertTrue(color.getRed() > color.getBlue() + 60, color.toString());
    }

    private void assertGreenDominant(Color color) {
        assertTrue(color.getGreen() > color.getRed() + 60, color.toString());
        assertTrue(color.getGreen() > color.getBlue() + 60, color.toString());
    }

    private void assertBright(Color color) {
        assertTrue(color.getRed() > 140, color.toString());
        assertTrue(color.getGreen() > 140, color.toString());
        assertTrue(color.getBlue() > 140, color.toString());
    }

    private void assertDark(Color color) {
        assertTrue(color.getRed() < 120, color.toString());
        assertTrue(color.getGreen() < 120, color.toString());
        assertTrue(color.getBlue() < 120, color.toString());
    }

    private void assertResponseExcludesPrivateMetadata(String body) {
        var lowercase = body.toLowerCase();
        assertFalse(lowercase.contains("storagekey"));
        assertFalse(lowercase.contains("filesystem"));
        assertFalse(lowercase.contains("originalfilename"));
        assertFalse(lowercase.contains("sha256"));
        assertFalse(body.contains(mediaProperties.root()));
    }

    private void assertError(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"code\":\"" + code + "\""), response.body());
        assertFalse(response.body().toLowerCase().contains("exception"), response.body());
        assertFalse(response.body().toLowerCase().contains("constraint"), response.body());
        assertFalse(response.body().contains(mediaProperties.root()), response.body());
    }

    private long masterFileCount() throws Exception {
        return regularFileCount(mediaRoot().resolve("masters"));
    }

    private long tempFileCount() throws Exception {
        return regularFileCount(mediaRoot().resolve("temp"));
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

    private record AuthenticatedSession(HttpClient client, String csrfToken) {}

    private record Part(String name, String filename, String contentType, byte[] bytes) {}
}
