package kr.co.rhaomi.publisher;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import kr.co.rhaomi.backend.BackendApplication;
import kr.co.rhaomi.backend.publication.PublicationEventStatus;
import kr.co.rhaomi.backend.publication.PublicationState;
import kr.co.rhaomi.backend.publication.PublicationStateService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(
        classes = BackendApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
@EnabledIfEnvironmentVariable(named = "RHAOMI_FULL_PUBLISHER_E2E", matches = "true")
class FullPublicationPipelineIntegrationTests {

    private static final String BUILD_TOKEN =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String CODE_SHA = "a".repeat(40);
    private static final String DIGEST = "sha256:" + "b".repeat(64);
    private static final UUID ADMIN_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000101");
    private static final UUID SHOP_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000102");
    private static final UUID SERVICE_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000103");
    private static final UUID NOTICE_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000104");
    private static final UUID BREED_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000105");
    private static final UUID MEDIA_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000106");
    private static final UUID GALLERY_ID =
            UUID.fromString("00000000-0000-4000-8000-000000000107");
    private static final String MEDIA_STORAGE_KEY =
            "masters/00/00000000-0000-4000-8000-000000000106.png";

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private PublicationStateService stateService;

    @Autowired
    private ObjectMapper objectMapper;

    @TempDir
    Path tempDirectory;

    private Path sourceRoot;
    private Path nodeExecutable;
    private Path releaseRoot;
    private Path currentLink;
    private Path previousLink;
    private Path lockFile;
    private Path mediaPath;
    private Instant baseTime;

    @BeforeEach
    void setUp() throws Exception {
        sourceRoot = requiredPath("RHAOMI_PUBLISHER_E2E_SOURCE_ROOT");
        nodeExecutable = requiredPath("RHAOMI_PUBLISHER_E2E_NODE");
        releaseRoot = tempDirectory.resolve("public/releases").toAbsolutePath();
        currentLink = tempDirectory.resolve("public/current").toAbsolutePath();
        previousLink = tempDirectory.resolve("public/previous").toAbsolutePath();
        lockFile = tempDirectory.resolve("state/locks/publisher.lock").toAbsolutePath();
        mediaPath = requiredPath("RHAOMI_MEDIA_ROOT").resolve(MEDIA_STORAGE_KEY);
        baseTime = Instant.now().truncatedTo(ChronoUnit.MICROS);
        Files.deleteIfExists(mediaPath);
        clearFixtures();
        insertPublicContent();
    }

    @AfterEach
    void tearDown() throws Exception {
        clearFixtures();
        Files.deleteIfExists(mediaPath);
    }

    @Test
    void should_completeActualReleaseAndMapNoopTransientAndTerminalResults() throws Exception {
        jdbcTemplate.update(
                "UPDATE publish_generation_state SET publish_generation = 4 WHERE singleton_key = 1");
        var firstEvent = insertPendingEvent(1, baseTime.minusSeconds(1));

        assertEquals(
                PublisherControlLoop.CycleOutcome.RESULT_RECORDED,
                runNext(executor(backendBaseUrl()), baseTime));
        assertEquals(PublicationState.SUCCEEDED, status(firstEvent).state());
        assertEquals("SUCCESS", status(firstEvent).lastResultCode().name());
        assertEquals("5", releaseGeneration(currentLink));
        assertTrue(Files.readString(resolveSite(currentLink).resolve("index.html"))
                .contains("통합 검증 서비스"));
        assertTrue(Files.readString(resolveSite(currentLink).resolve("index.html"))
                .contains("합성 갤러리 강아지"));
        assertTrue(Files.readString(resolveSite(currentLink).resolve("index.html"))
                .contains("<picture"));
        try (var files = Files.walk(resolveSite(currentLink).resolve("generated/media"))) {
            var extensions = files.filter(Files::isRegularFile)
                    .map(path -> path.getFileName().toString())
                    .toList();
            assertTrue(extensions.stream().anyMatch(name -> name.endsWith(".avif")));
            assertTrue(extensions.stream().anyMatch(name -> name.endsWith(".webp")));
            assertTrue(extensions.stream().anyMatch(name -> name.endsWith(".jpeg")));
        }
        assertTrue(Files.readString(
                        resolveSite(currentLink)
                                .resolve("notices/integration-notice/index.html"))
                .contains("통합 검증 공지"));

        jdbcTemplate.update(
                "UPDATE content_revision_state SET content_revision = 2 WHERE singleton_key = 1");
        var secondEvent = insertPendingEvent(2, baseTime.plusSeconds(59));
        assertEquals(
                PublisherControlLoop.CycleOutcome.RESULT_RECORDED,
                runNext(executor(backendBaseUrl()), baseTime.plusSeconds(60)));
        assertEquals(PublicationState.SUCCEEDED, status(secondEvent).state());
        assertEquals("6", releaseGeneration(currentLink));
        assertEquals("5", releaseGeneration(previousLink));

        var recoveredLower = insertExpiredProcessingEvent(
                2,
                4,
                baseTime.plusSeconds(119),
                baseTime.plusSeconds(120));
        assertEquals(
                PublisherControlLoop.CycleOutcome.RESULT_RECORDED,
                runNext(executor(backendBaseUrl()), baseTime.plusSeconds(120)));
        assertEquals(PublicationState.NOOP, status(recoveredLower).state());
        assertEquals("NO_PUBLIC_CHANGE", status(recoveredLower).lastResultCode().name());
        assertEquals("6", releaseGeneration(currentLink));
        assertEquals("5", releaseGeneration(previousLink));

        jdbcTemplate.update(
                "UPDATE content_revision_state SET content_revision = 3 WHERE singleton_key = 1");
        var transientEvent = insertPendingEvent(3, baseTime.plusSeconds(179));
        assertEquals(
                PublisherControlLoop.CycleOutcome.RESULT_RECORDED,
                runNext(executor("http://127.0.0.1:1/"), baseTime.plusSeconds(180)));
        assertEquals(PublicationState.RETRY_WAIT, status(transientEvent).state());
        assertEquals("TRANSIENT_FAILURE", status(transientEvent).lastResultCode().name());
        var retryStatus = status(transientEvent);
        assertEquals(Long.valueOf(7), retryStatus.publishGeneration());
        assertNotNull(retryStatus.nextAttemptAt());
        assertEquals("6", releaseGeneration(currentLink));

        assertEquals(
                PublisherControlLoop.CycleOutcome.RESULT_RECORDED,
                runNext(executor(backendBaseUrl()), retryStatus.nextAttemptAt()));
        assertEquals(PublicationState.SUCCEEDED, status(transientEvent).state());
        assertEquals(Long.valueOf(7), status(transientEvent).publishGeneration());
        assertEquals("7", releaseGeneration(currentLink));
        assertEquals("6", releaseGeneration(previousLink));

        jdbcTemplate.update(
                "UPDATE content_revision_state SET content_revision = 4 WHERE singleton_key = 1");
        var terminalEvent = insertPendingEvent(4, baseTime.plusSeconds(219));
        try (var invalidSnapshot = invalidSnapshotServer()) {
            assertEquals(
                    PublisherControlLoop.CycleOutcome.RESULT_RECORDED,
                    runNext(executor(invalidSnapshot.baseUrl()), baseTime.plusSeconds(220)));
        }
        assertEquals(PublicationState.FAILED, status(terminalEvent).state());
        assertEquals("TERMINAL_FAILURE", status(terminalEvent).lastResultCode().name());
        assertEquals("7", releaseGeneration(currentLink));
        assertEquals("6", releaseGeneration(previousLink));
    }

    private PublisherControlLoop.CycleOutcome runNext(
            PublicationBuildExecutor executor, Instant start) throws Exception {
        var clock = new MutableClock(start);
        var buildThreads = Executors.newThreadPerTaskExecutor(
                Thread.ofVirtual().name("full-publisher-e2e-", 0).factory());
        try {
            var settings = new PublisherSettings(
                    "full-publisher-e2e",
                    Duration.ofMillis(100),
                    Duration.ofMinutes(5),
                    Duration.ofSeconds(30),
                    Duration.ofSeconds(5),
                    lockFile);
            var loop = new PublisherControlLoop(
                    new PublicationStateServiceAdapter(stateService),
                    new AsyncPublicationBuildTaskFactory(executor, buildThreads),
                    new FileSystemPublicationExecutionLock(lockFile),
                    clock,
                    (duration, stopSignal) -> {
                        clock.advance(PublisherSettings.DEBOUNCE_WINDOW);
                        return !stopSignal.isRequested();
                    },
                    settings,
                    new PublisherStopSignal());
            return loop.runNext();
        } finally {
            buildThreads.shutdownNow();
            assertTrue(buildThreads.awaitTermination(10, TimeUnit.SECONDS));
        }
    }

    private NodePublicationBuildExecutor executor(String buildApiUrl) {
        var environment = new LinkedHashMap<String, String>();
        environment.put("BUILD_API_INTERNAL_URL", buildApiUrl);
        environment.put("BUILD_API_CREDENTIAL", BUILD_TOKEN);
        environment.put("RHAOMI_PUBLISHER_SOURCE_ROOT", sourceRoot.toString());
        environment.put(
                "RHAOMI_PUBLISHER_WORK_ROOT",
                tempDirectory.resolve("state/publisher").toAbsolutePath().toString());
        environment.put("RHAOMI_PUBLIC_RELEASE_ROOT", releaseRoot.toString());
        environment.put("RHAOMI_PUBLIC_CURRENT_LINK", currentLink.toString());
        environment.put("RHAOMI_PUBLIC_PREVIOUS_LINK", previousLink.toString());
        environment.put("PUBLIC_SITE_URL", "https://publisher-e2e.example.invalid/");
        environment.put("RHAOMI_CODE_SHA", CODE_SHA);
        environment.put("RHAOMI_CODE_IMAGE_TAG", "sha-" + CODE_SHA);
        environment.put("RHAOMI_CODE_IMAGE_DIGEST", DIGEST);
        environment.put("RHAOMI_FLYWAY_VERSION", "9");
        environment.put("RHAOMI_SBOM_REFERENCE", DIGEST);
        environment.put("RHAOMI_PUBLISHER_BUILD_TIMEOUT_MS", "120000");
        environment.put("RHAOMI_RELEASE_RETENTION", "5");
        environment.put("PATH", requiredEnvironment("PATH"));
        return new NodePublicationBuildExecutor(new PublicationExecutorSettings(
                nodeExecutable,
                sourceRoot.resolve("scripts/publish-static-release.mts"),
                Duration.ofMillis(250),
                environment));
    }

    private String backendBaseUrl() {
        return "http://127.0.0.1:" + port + "/";
    }

    private UUID insertPendingEvent(long revision, Instant availableAt) {
        var id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO publishing_outbox (
                    id, kind, source_type, source_id, content_revision, available_at
                ) VALUES (?, 'CONTENT_CHANGED', 'SERVICE', ?, ?, ?)
                """,
                id,
                SERVICE_ID,
                revision,
                offset(availableAt));
        return id;
    }

    private UUID insertExpiredProcessingEvent(
            long revision, long generation, Instant leaseUntil, Instant availableAt) {
        var id = UUID.randomUUID();
        var claimedAt = leaseUntil.minusSeconds(30);
        jdbcTemplate.update(
                """
                INSERT INTO publishing_outbox (
                    id, kind, source_type, source_id, content_revision, available_at,
                    state, publish_generation, attempt_count, claim_owner,
                    claimed_at, lease_until
                ) VALUES (
                    ?, 'CONTENT_CHANGED', 'SERVICE', ?, ?, ?,
                    'PROCESSING', ?, 1, 'expired-publisher', ?, ?
                )
                """,
                id,
                SERVICE_ID,
                revision,
                offset(availableAt),
                generation,
                offset(claimedAt),
                offset(leaseUntil));
        return id;
    }

    private PublicationEventStatus status(UUID eventId) {
        return stateService.findStatus(eventId).orElseThrow();
    }

    private String releaseGeneration(Path link) throws Exception {
        var site = resolveSite(link);
        var manifest = site.getParent().resolve("release-manifest.json");
        return objectMapper.readTree(Files.readString(manifest))
                .get("publishGeneration")
                .asText();
    }

    private Path resolveSite(Path link) throws Exception {
        return link.getParent().resolve(Files.readSymbolicLink(link)).normalize();
    }

    private void insertPublicContent() throws Exception {
        jdbcTemplate.update(
                """
                INSERT INTO admin_users (id, email, password_hash)
                VALUES (?, 'publisher.e2e@example.com', 'synthetic-hash')
                """,
                ADMIN_ID);
        var mediaBytes = syntheticMediaBytes();
        Files.createDirectories(mediaPath.getParent());
        Files.write(mediaPath, mediaBytes);
        jdbcTemplate.update(
                """
                INSERT INTO media_assets (
                    id, status, source_content_type, content_type, file_extension,
                    storage_key, source_byte_size, byte_size, width, height, sha256,
                    created_by, updated_by
                ) VALUES (
                    ?, 'active', 'image/png', 'image/png', 'png', ?,
                    ?, ?, 64, 48, ?, ?, ?
                )
                """,
                MEDIA_ID,
                MEDIA_STORAGE_KEY,
                mediaBytes.length,
                mediaBytes.length,
                HexFormat.of().formatHex(
                        MessageDigest.getInstance("SHA-256").digest(mediaBytes)),
                ADMIN_ID,
                ADMIN_ID);
        jdbcTemplate.update(
                """
                INSERT INTO breeds (
                    id, status, name, slug, description, sort_order,
                    created_by, updated_by
                ) VALUES (
                    ?, 'published', '통합 검증 견종', 'integration-breed',
                    '합성 견종 설명', 1, ?, ?
                )
                """,
                BREED_ID,
                ADMIN_ID,
                ADMIN_ID);
        jdbcTemplate.update(
                """
                INSERT INTO shop_settings (
                    id, shop_name, region_label, business_type, phone, address,
                    opening_time, closing_time, closed_weekday, parking_available,
                    parking_note, hero_title, hero_description, reservation_notice,
                    hero_image_id, hero_image_alt_text, og_image_id,
                    created_by, updated_by
                ) VALUES (
                    ?, '통합 검증 라오미펫', '테스트 지역', '반려견 미용',
                    '02-000-0000', '테스트시 통합구 검증로 1', '10:00', '19:00',
                    'MONDAY', TRUE, '합성 주차 안내', '통합 검증 라오미펫',
                    '실제 운영 데이터가 아닌 합성 설명', '합성 예약 안내',
                    ?, '합성 Hero 이미지', ?, ?, ?
                )
                """,
                SHOP_ID,
                MEDIA_ID,
                MEDIA_ID,
                ADMIN_ID,
                ADMIN_ID);
        jdbcTemplate.update(
                """
                INSERT INTO services (
                    id, status, name, slug, description, price_text,
                    sort_order, created_by, updated_by
                ) VALUES (
                    ?, 'published', '통합 검증 서비스', 'integration-service',
                    '합성 서비스 설명', '상담 후 안내', 1, ?, ?
                )
                """,
                SERVICE_ID,
                ADMIN_ID,
                ADMIN_ID);
        jdbcTemplate.update(
                """
                INSERT INTO gallery_items (
                    id, status, dog_name, breed_id, primary_service_id,
                    cover_image_id, summary, alt_text, featured, sort_order,
                    performed_at, published_at, created_by, updated_by
                ) VALUES (
                    ?, 'published', '합성 갤러리 강아지', ?, ?, ?,
                    '합성 갤러리 설명', '합성 갤러리 대표 이미지', TRUE, 1,
                    '2020-01-01T00:00:00Z', '2020-01-02T00:00:00Z', ?, ?
                )
                """,
                GALLERY_ID,
                BREED_ID,
                SERVICE_ID,
                MEDIA_ID,
                ADMIN_ID,
                ADMIN_ID);
        jdbcTemplate.update(
                """
                INSERT INTO notices (
                    id, status, title, slug, summary, body_markdown, pinned,
                    published_at, created_by, updated_by
                ) VALUES (
                    ?, 'published', '통합 검증 공지', 'integration-notice',
                    '합성 공지 요약', '**통합 검증 공지 본문**', TRUE,
                    '2020-01-01T00:00:00Z', ?, ?
                )
                """,
                NOTICE_ID,
                ADMIN_ID,
                ADMIN_ID);
        jdbcTemplate.update(
                "UPDATE content_revision_state SET content_revision = 1 WHERE singleton_key = 1");
    }

    private byte[] syntheticMediaBytes() throws Exception {
        try (var input = FullPublicationPipelineIntegrationTests.class
                .getResourceAsStream("/media/synthetic-source.png")) {
            if (input == null) {
                throw new IllegalStateException("Synthetic media fixture is missing");
            }
            return input.readAllBytes();
        }
    }

    private void clearFixtures() {
        jdbcTemplate.update("DELETE FROM publishing_outbox");
        jdbcTemplate.update("DELETE FROM gallery_items");
        jdbcTemplate.update("DELETE FROM notices");
        jdbcTemplate.update("DELETE FROM shop_settings");
        jdbcTemplate.update("DELETE FROM services");
        jdbcTemplate.update("DELETE FROM breeds");
        jdbcTemplate.update("DELETE FROM media_assets");
        jdbcTemplate.update("DELETE FROM admin_users");
        jdbcTemplate.update(
                "UPDATE publish_generation_state SET publish_generation = 0 WHERE singleton_key = 1");
        jdbcTemplate.update(
                "UPDATE content_revision_state SET content_revision = 0 WHERE singleton_key = 1");
    }

    private InvalidSnapshotServer invalidSnapshotServer() throws Exception {
        var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        server.setExecutor(executor);
        server.createContext("/", exchange -> {
            var body = "{}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().set("content-type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var response = exchange.getResponseBody()) {
                response.write(body);
            }
        });
        server.start();
        return new InvalidSnapshotServer(
                server,
                executor,
                "http://127.0.0.1:" + server.getAddress().getPort() + "/");
    }

    private Path requiredPath(String name) {
        return Path.of(requiredEnvironment(name)).toAbsolutePath().normalize();
    }

    private String requiredEnvironment(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required publisher E2E environment is missing");
        }
        return value;
    }

    private OffsetDateTime offset(Instant value) {
        return value.atOffset(ZoneOffset.UTC);
    }

    private static final class MutableClock extends Clock {

        private Instant value;

        private MutableClock(Instant value) {
            this.value = value;
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return value;
        }

        private void advance(Duration duration) {
            value = value.plus(duration);
        }
    }

    private record InvalidSnapshotServer(
            HttpServer server,
            java.util.concurrent.ExecutorService executor,
            String baseUrl) implements AutoCloseable {

        @Override
        public void close() {
            server.stop(0);
            executor.shutdownNow();
        }
    }
}
