package kr.co.rhaomi.production;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import kr.co.rhaomi.backend.config.AdminBootstrap;
import kr.co.rhaomi.backend.BackendApplication;
import kr.co.rhaomi.backend.publication.PublicationStateService;
import kr.co.rhaomi.publisher.PublisherApplication;
import kr.co.rhaomi.publisher.PublisherControlLoop;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.SpringApplication;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.bind.annotation.RestController;
import tools.jackson.databind.ObjectMapper;

class ProductionInitialContentTaskIntegrationTests {

    private static final String ADMIN_EMAIL = "initial.content@example.invalid";
    private static final String ADMIN_PASSWORD = "synthetic-initial-content-password-123!";
    private static final String BUILD_TOKEN =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String CODE_SHA = "a".repeat(40);
    private static final String IMAGE_DIGEST = "sha256:" + "b".repeat(64);
    private static final Clock IMPORT_CLOCK =
            Clock.fixed(Instant.parse("2026-09-11T00:00:00Z"), ZoneOffset.UTC);

    @TempDir
    Path tempDirectory;

    @Test
    void should_importCanonicalBundleAndCreateOnePendingEvent_when_databaseIsPristine()
            throws Exception {
        withMigratedSchema(schemaUrl -> {
            provisionAdmin(schemaUrl);
            var bundleRoot = InitialContentTestBundle.create(tempDirectory.resolve("success"));
            var mediaRoot = Files.createDirectories(tempDirectory.resolve("success-media"));
            var output = new ByteArrayOutputStream();

            try (var context = runInitialContent(
                    schemaUrl,
                    bundleRoot,
                    mediaRoot,
                    InitialContentImportCheckpoint.noop(),
                    new PrintStream(output, true, StandardCharsets.UTF_8))) {
                assertFalse(context instanceof WebServerApplicationContext);
                assertTrue(context.getBeansWithAnnotation(RestController.class).isEmpty());
                assertTrue(context.getBeansOfType(AdminBootstrap.class).isEmpty());
                assertTrue(context.getBeansOfType(PublisherControlLoop.class).isEmpty());
                assertFalse(context.getEnvironment().getProperty("spring.flyway.enabled", Boolean.class));
                assertEquals(
                        "validate",
                        context.getEnvironment().getProperty("spring.jpa.hibernate.ddl-auto"));
            }

            assertEquals(1L, queryLong(schemaUrl, "SELECT COUNT(*) FROM shop_settings"));
            assertEquals(1L, queryLong(schemaUrl, "SELECT COUNT(*) FROM breeds WHERE status = 'published'"));
            assertEquals(1L, queryLong(schemaUrl, "SELECT COUNT(*) FROM services WHERE status = 'published'"));
            assertEquals(1L, queryLong(schemaUrl, "SELECT COUNT(*) FROM notices WHERE status = 'published'"));
            assertEquals(1L, queryLong(schemaUrl, "SELECT COUNT(*) FROM gallery_items WHERE status = 'published'"));
            assertEquals(1L, queryLong(schemaUrl, "SELECT COUNT(*) FROM media_assets WHERE status = 'active'"));
            assertEquals(1L, queryLong(schemaUrl, "SELECT content_revision FROM content_revision_state WHERE singleton_key = 1"));
            assertEquals(0L, queryLong(schemaUrl, "SELECT publish_generation FROM publish_generation_state WHERE singleton_key = 1"));
            assertEquals(1L, queryLong(schemaUrl, "SELECT COUNT(*) FROM publishing_outbox"));
            assertEquals(
                    "CONTENT_CHANGED|SHOP_SETTINGS|PENDING",
                    queryString(
                            schemaUrl,
                            "SELECT kind || '|' || source_type || '|' || state FROM publishing_outbox"));
            assertNull(queryNullableString(schemaUrl, "SELECT publish_generation::text FROM publishing_outbox"));
            assertEquals(
                    1L,
                    queryLong(
                            schemaUrl,
                            "SELECT COUNT(*) FROM gallery_items gallery "
                                    + "JOIN breeds breed ON breed.id = gallery.breed_id "
                                    + "JOIN services service ON service.id = gallery.primary_service_id "
                                    + "JOIN media_assets media ON media.id = gallery.cover_image_id "
                                    + "WHERE breed.status = 'published' AND service.status = 'published' "
                                    + "AND media.status = 'active'"));
            assertEquals(
                    queryString(schemaUrl, "SELECT id::text FROM admin_users"),
                    queryString(schemaUrl, "SELECT created_by::text FROM shop_settings"));
            assertEquals(1L, regularFileCount(mediaRoot.resolve("masters")));

            var evidence = output.toString(StandardCharsets.UTF_8);
            assertTrue(evidence.contains("\"contract\":\"rhaomi-initial-content-v1\""));
            assertTrue(evidence.contains("\"publicationStatus\":\"PENDING\""));
            assertFalse(evidence.contains(InitialContentTestBundle.SHOP_NAME));
            assertFalse(evidence.contains(bundleRoot.toString()));
            assertFalse(evidence.contains(ADMIN_EMAIL));
            assertFalse(evidence.contains(ADMIN_PASSWORD));

            assertBuildSnapshotV2(schemaUrl, mediaRoot);
        });
    }

    @Test
    void should_shareOneContentRevision_when_initialImportIncludesFutureExpiryEvent()
            throws Exception {
        withMigratedSchema(schemaUrl -> {
            provisionAdmin(schemaUrl);
            var bundleRoot = InitialContentTestBundle.createWithFutureNoticeExpiry(
                    tempDirectory.resolve("future-expiry"));
            var mediaRoot = Files.createDirectories(tempDirectory.resolve("future-expiry-media"));

            try (var ignored = runInitialContent(
                    schemaUrl,
                    bundleRoot,
                    mediaRoot,
                    InitialContentImportCheckpoint.noop(),
                    new PrintStream(new ByteArrayOutputStream()))) {}

            assertEquals(
                    1L,
                    queryLong(
                            schemaUrl,
                            "SELECT content_revision FROM content_revision_state WHERE singleton_key = 1"));
            assertEquals(2L, queryLong(schemaUrl, "SELECT COUNT(*) FROM publishing_outbox"));
            assertEquals(
                    1L,
                    queryLong(
                            schemaUrl,
                            "SELECT COUNT(DISTINCT content_revision) FROM publishing_outbox"));
            assertEquals(
                    1L,
                    queryLong(
                            schemaUrl,
                            "SELECT COUNT(*) FROM publishing_outbox "
                                    + "WHERE kind = 'CONTENT_CHANGED' AND source_type = 'SHOP_SETTINGS' "
                                    + "AND state = 'PENDING' AND content_revision = 1"));
            assertEquals(
                    1L,
                    queryLong(
                            schemaUrl,
                            "SELECT COUNT(*) FROM publishing_outbox "
                                    + "WHERE kind = 'NOTICE_EXPIRES_AT_DUE' AND source_type = 'NOTICE' "
                                    + "AND state = 'PENDING' AND content_revision = 1 "
                                    + "AND available_at = TIMESTAMPTZ '2026-09-12T00:00:00Z' "
                                    + "AND expected_boundary_at = TIMESTAMPTZ '2026-09-12T00:00:00Z'"));
        });
    }

    @Test
    void should_allowAtMostOneCommit_when_concurrentImportsUseSamePristineDatabase()
            throws Exception {
        withMigratedSchema(schemaUrl -> {
            provisionAdmin(schemaUrl);
            var bundleRoot = InitialContentTestBundle.create(tempDirectory.resolve("concurrent"));
            var mediaRoot = Files.createDirectories(tempDirectory.resolve("concurrent-media"));
            var bundle = new InitialContentBundleReader().read(bundleRoot);

            try (var context = initialContentServiceContext(
                    schemaUrl, bundleRoot, mediaRoot, InitialContentImportCheckpoint.noop())) {
                var service = context.getBean(InitialContentImportService.class);
                var ready = new CountDownLatch(2);
                var start = new CountDownLatch(1);
                var outcomes = new ArrayList<TaskOutcome>();
                try (var executor = Executors.newFixedThreadPool(2)) {
                    var futures = List.of(
                            executor.submit(() -> runConcurrentImport(service, bundle, ready, start)),
                            executor.submit(() -> runConcurrentImport(service, bundle, ready, start)));
                    assertTrue(ready.await(30, TimeUnit.SECONDS));
                    start.countDown();
                    for (var future : futures) {
                        outcomes.add(future.get(120, TimeUnit.SECONDS));
                    }
                }

                assertEquals(1L, outcomes.stream().filter(TaskOutcome::success).count());
                assertEquals(1L, outcomes.stream().filter(outcome -> !outcome.success()).count());
                assertTrue(outcomes.stream()
                        .filter(outcome -> !outcome.success())
                        .allMatch(outcome -> messageChain(outcome.failure())
                                .contains("INITIAL_CONTENT_ALREADY_PROVISIONED")));
            }

            assertEquals(1L, queryLong(schemaUrl, "SELECT COUNT(*) FROM shop_settings"));
            assertEquals(1L, queryLong(schemaUrl, "SELECT COUNT(*) FROM publishing_outbox"));
            assertEquals(1L, regularFileCount(mediaRoot.resolve("masters")));

            var replayException = assertThrows(
                    RuntimeException.class,
                    () -> runInitialContent(
                            schemaUrl,
                            bundleRoot,
                            mediaRoot,
                            InitialContentImportCheckpoint.noop(),
                            new PrintStream(new ByteArrayOutputStream())));
            assertTrue(messageChain(replayException).contains("INITIAL_CONTENT_ALREADY_PROVISIONED"));
            assertEquals(1L, queryLong(schemaUrl, "SELECT COUNT(*) FROM publishing_outbox"));
        });
    }

    @Test
    void should_rollbackRowsOutboxAndNewMedia_when_transactionFailsAfterWrites() throws Exception {
        withMigratedSchema(schemaUrl -> {
            provisionAdmin(schemaUrl);
            var bundleRoot = InitialContentTestBundle.create(tempDirectory.resolve("rollback"));
            var mediaRoot = Files.createDirectories(tempDirectory.resolve("rollback-media"));
            var bundle = new InitialContentBundleReader().read(bundleRoot);

            try (var context = initialContentServiceContext(
                    schemaUrl,
                    bundleRoot,
                    mediaRoot,
                    () -> {
                        throw new InitialContentImportException("INITIAL_CONTENT_TEST_FAILURE");
                    })) {
                var service = context.getBean(InitialContentImportService.class);
                var exception = assertThrows(
                        InitialContentImportException.class,
                        () -> service.importBundle(bundle));
                assertEquals("INITIAL_CONTENT_TEST_FAILURE", exception.getMessage());
            }

            for (var table : List.of(
                    "shop_settings",
                    "breeds",
                    "services",
                    "notices",
                    "gallery_items",
                    "media_assets",
                    "publishing_outbox")) {
                assertEquals(0L, queryLong(schemaUrl, "SELECT COUNT(*) FROM " + table), table);
            }
            assertEquals(0L, queryLong(schemaUrl, "SELECT content_revision FROM content_revision_state WHERE singleton_key = 1"));
            assertEquals(0L, queryLong(schemaUrl, "SELECT publish_generation FROM publish_generation_state WHERE singleton_key = 1"));
            assertEquals(0L, regularFileCount(mediaRoot.resolve("masters")));
        });
    }

    @Test
    void should_leaveDatabaseAndMediaUnchanged_when_requiredPublicContentIsBlank()
            throws Exception {
        withMigratedSchema(schemaUrl -> {
            provisionAdmin(schemaUrl);
            var bundleRoot = InitialContentTestBundle.create(tempDirectory.resolve("blank-content"));
            var contentPath = bundleRoot.resolve("content.json");
            Files.writeString(
                    contentPath,
                    Files.readString(contentPath, StandardCharsets.UTF_8)
                            .replace("\"title\": \"첫 공지\"", "\"title\": \"   \""),
                    StandardCharsets.UTF_8);
            InitialContentTestBundle.rewriteManifest(bundleRoot);
            var mediaRoot = Files.createDirectories(tempDirectory.resolve("blank-content-media"));

            var exception = assertThrows(
                    RuntimeException.class,
                    () -> runInitialContent(
                            schemaUrl,
                            bundleRoot,
                            mediaRoot,
                            InitialContentImportCheckpoint.noop(),
                            new PrintStream(new ByteArrayOutputStream())));
            assertTrue(messageChain(exception).contains("INITIAL_CONTENT_REQUIRED_DATA_INVALID"));

            assertNoImportedState(schemaUrl, mediaRoot);
        });
    }

    @Test
    void should_preserveExistingCanonicalMediaAndRejectBeforeDatabaseMutation() throws Exception {
        withMigratedSchema(schemaUrl -> {
            provisionAdmin(schemaUrl);
            var bundleRoot = InitialContentTestBundle.create(tempDirectory.resolve("partial-media"));
            var mediaRoot = Files.createDirectories(tempDirectory.resolve("partial-media-root"));
            var bundle = new InitialContentBundleReader().read(bundleRoot);

            try (var context = initialContentServiceContext(
                    schemaUrl, bundleRoot, mediaRoot, InitialContentImportCheckpoint.noop())) {
                var existing = mediaRoot.resolve("masters/preexisting.bin");
                Files.writeString(existing, "preexisting-media", StandardCharsets.UTF_8);
                var exception = assertThrows(
                        InitialContentImportException.class,
                        () -> context.getBean(InitialContentImportService.class).importBundle(bundle));
                assertEquals("INITIAL_CONTENT_ALREADY_PROVISIONED", exception.getMessage());
                assertEquals("preexisting-media", Files.readString(existing, StandardCharsets.UTF_8));
            }

            for (var table : List.of(
                    "shop_settings",
                    "breeds",
                    "services",
                    "notices",
                    "gallery_items",
                    "media_assets",
                    "publishing_outbox")) {
                assertEquals(0L, queryLong(schemaUrl, "SELECT COUNT(*) FROM " + table), table);
            }
            assertEquals(0L, queryLong(schemaUrl, "SELECT content_revision FROM content_revision_state WHERE singleton_key = 1"));
            assertEquals(0L, queryLong(schemaUrl, "SELECT publish_generation FROM publish_generation_state WHERE singleton_key = 1"));
        });
    }

    @Test
    void should_preserveCommittedContentAndGenerationAndRejectReimport_when_publicationFails()
            throws Exception {
        withMigratedSchema(schemaUrl -> {
            provisionAdmin(schemaUrl);
            var bundleRoot = InitialContentTestBundle.create(
                    tempDirectory.resolve("publication-failure"));
            var mediaRoot = Files.createDirectories(
                    tempDirectory.resolve("publication-failure-media"));

            try (var ignored = runInitialContent(
                    schemaUrl,
                    bundleRoot,
                    mediaRoot,
                    InitialContentImportCheckpoint.noop(),
                    new PrintStream(new ByteArrayOutputStream()))) {}

            var dataSource = new DriverManagerDataSource(
                    schemaUrl,
                    requiredEnvironment("SPRING_DATASOURCE_USERNAME"),
                    requiredEnvironment("SPRING_DATASOURCE_PASSWORD"));
            var stateService = new PublicationStateService(new JdbcTemplate(dataSource));
            var transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
            var publicationTime = Instant.now()
                    .plusSeconds(5)
                    .truncatedTo(java.time.temporal.ChronoUnit.MICROS);
            var claim = transaction
                    .execute(status -> stateService.claimNext(
                            "initial-content-failure-test",
                            publicationTime,
                            Duration.ofMinutes(5)))
                    .orElseThrow();
            assertEquals(1L, claim.publishGeneration());
            assertTrue(Boolean.TRUE.equals(transaction.execute(status ->
                    stateService.recordTerminalFailure(
                            claim.eventId(),
                            claim.publishGeneration(),
                            "initial-content-failure-test",
                            publicationTime))));

            assertEquals(1L, queryLong(schemaUrl, "SELECT COUNT(*) FROM shop_settings"));
            assertEquals(1L, queryLong(schemaUrl, "SELECT COUNT(*) FROM breeds"));
            assertEquals(1L, queryLong(schemaUrl, "SELECT COUNT(*) FROM services"));
            assertEquals(1L, queryLong(schemaUrl, "SELECT COUNT(*) FROM notices"));
            assertEquals(1L, queryLong(schemaUrl, "SELECT COUNT(*) FROM gallery_items"));
            assertEquals(1L, queryLong(schemaUrl, "SELECT COUNT(*) FROM media_assets"));
            assertEquals(1L, queryLong(schemaUrl, "SELECT COUNT(*) FROM publishing_outbox"));
            assertEquals(
                    "FAILED|TERMINAL_FAILURE|1",
                    queryString(
                            schemaUrl,
                            "SELECT state || '|' || last_result_code || '|' "
                                    + "|| publish_generation::text FROM publishing_outbox"));
            assertEquals(0L, queryLong(schemaUrl, "SELECT COUNT(*) FROM publishing_outbox WHERE state = 'SUCCEEDED'"));

            var replayException = assertThrows(
                    RuntimeException.class,
                    () -> runInitialContent(
                            schemaUrl,
                            bundleRoot,
                            mediaRoot,
                            InitialContentImportCheckpoint.noop(),
                            new PrintStream(new ByteArrayOutputStream())));
            assertTrue(messageChain(replayException).contains("INITIAL_CONTENT_ALREADY_PROVISIONED"));
            assertEquals(1L, queryLong(schemaUrl, "SELECT COUNT(*) FROM publishing_outbox"));
            assertEquals(1L, regularFileCount(mediaRoot.resolve("masters")));
        });
    }

    @Test
    @EnabledIfEnvironmentVariable(named = "RHAOMI_FULL_PUBLISHER_E2E", matches = "true")
    void should_publishImportedBundleThroughCanonicalPublisherAndAtomicFirstCurrentSwitch()
            throws Exception {
        withMigratedSchema(schemaUrl -> {
            provisionAdmin(schemaUrl);
            var bundleRoot = InitialContentTestBundle.create(
                    tempDirectory.resolve("publisher-e2e"));
            var mediaRoot = Files.createDirectories(
                    tempDirectory.resolve("publisher-e2e-media"));
            try (var ignored = runInitialContent(
                    schemaUrl,
                    bundleRoot,
                    mediaRoot,
                    InitialContentImportCheckpoint.noop(),
                    new PrintStream(new ByteArrayOutputStream()))) {}

            var sourceRoot = requiredPath("RHAOMI_PUBLISHER_E2E_SOURCE_ROOT");
            var nodeExecutable = requiredPath("RHAOMI_PUBLISHER_E2E_NODE");
            var publicRoot = tempDirectory.resolve("publisher-e2e-public").toAbsolutePath();
            var releaseRoot = publicRoot.resolve("releases");
            var currentLink = publicRoot.resolve("current");
            var previousLink = publicRoot.resolve("previous");
            var lockFile = tempDirectory
                    .resolve("publisher-e2e-state/locks/publisher.lock")
                    .toAbsolutePath();
            var backendApplication = new SpringApplication(BackendApplication.class);
            backendApplication.setWebApplicationType(WebApplicationType.SERVLET);

            try (var backend = backendApplication.run(
                            "--server.port=0",
                            "--spring.flyway.enabled=false",
                            "--spring.jpa.hibernate.ddl-auto=validate",
                            "--spring.datasource.url=" + schemaUrl,
                            "--spring.datasource.username="
                                    + requiredEnvironment("SPRING_DATASOURCE_USERNAME"),
                            "--spring.datasource.password="
                                    + requiredEnvironment("SPRING_DATASOURCE_PASSWORD"),
                            "--rhaomi.media.root=" + mediaRoot,
                            "--rhaomi.build-service.token=" + BUILD_TOKEN,
                            "--rhaomi.bootstrap-admin.enabled=false",
                            "--rhaomi.admin-auth.webauthn.required=false")) {
                var web = (WebServerApplicationContext) backend;
                var buildApiUrl = "http://127.0.0.1:" + web.getWebServer().getPort() + "/";
                try (var ignored = PublisherApplication.run(publisherArguments(
                        schemaUrl,
                        buildApiUrl,
                        sourceRoot,
                        nodeExecutable,
                        releaseRoot,
                        currentLink,
                        previousLink,
                        lockFile))) {
                    awaitPublicationSuccess(schemaUrl);
                }
            }

            assertTrue(Files.isSymbolicLink(currentLink));
            assertFalse(Files.exists(previousLink));
            var siteRoot = currentLink
                    .getParent()
                    .resolve(Files.readSymbolicLink(currentLink))
                    .normalize();
            var releasePackage = siteRoot.getParent();
            var home = Files.readString(siteRoot.resolve("index.html"), StandardCharsets.UTF_8);
            assertTrue(home.contains(InitialContentTestBundle.SHOP_NAME));
            assertTrue(home.contains(InitialContentTestBundle.BREED_NAME));
            assertTrue(home.contains(InitialContentTestBundle.SERVICE_NAME));
            assertTrue(home.contains(InitialContentTestBundle.NOTICE_TITLE));
            assertTrue(home.contains(InitialContentTestBundle.GALLERY_DOG_NAME));
            assertTrue(home.contains("<picture"));
            assertTrue(Files.readString(
                            siteRoot.resolve("notices/first-notice/index.html"),
                            StandardCharsets.UTF_8)
                    .contains(InitialContentTestBundle.NOTICE_TITLE));
            assertTrue(Files.isRegularFile(releasePackage.resolve("release-manifest.json")));
            assertFalse(Files.exists(siteRoot.resolve("release-manifest.json")));
            var manifest = new ObjectMapper()
                    .readTree(Files.readString(
                            releasePackage.resolve("release-manifest.json"),
                            StandardCharsets.UTF_8));
            assertEquals("1", manifest.get("contentRevision").asText());
            assertEquals("1", manifest.get("publishGeneration").asText());
            assertEquals(
                    "SUCCEEDED|SUCCESS|1",
                    queryString(
                            schemaUrl,
                            "SELECT state || '|' || last_result_code || '|' "
                                    + "|| publish_generation::text FROM publishing_outbox"));
        });
    }

    private TaskOutcome runConcurrentImport(
            InitialContentImportService service,
            InitialContentBundle bundle,
            CountDownLatch ready,
            CountDownLatch start) {
        ready.countDown();
        try {
            if (!start.await(30, TimeUnit.SECONDS)) {
                throw new IllegalStateException("concurrency gate timeout");
            }
            service.importBundle(bundle);
            return new TaskOutcome(true, null);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            return new TaskOutcome(false, exception);
        } catch (Throwable throwable) {
            return new TaskOutcome(false, throwable);
        }
    }

    private org.springframework.context.ConfigurableApplicationContext runInitialContent(
            String schemaUrl,
            Path bundleRoot,
            Path mediaRoot,
            InitialContentImportCheckpoint checkpoint,
            PrintStream output) {
        return ProductionDatabaseTaskApplication.runInitialContent(
                taskArguments(schemaUrl, mediaRoot),
                bundleRoot,
                IMPORT_CLOCK,
                checkpoint,
                output);
    }

    private String[] publisherArguments(
            String schemaUrl,
            String buildApiUrl,
            Path sourceRoot,
            Path nodeExecutable,
            Path releaseRoot,
            Path currentLink,
            Path previousLink,
            Path lockFile) {
        return new String[] {
            PublisherApplication.MODE_ARGUMENT,
            "--spring.datasource.url=" + schemaUrl,
            "--spring.datasource.username=" + requiredEnvironment("SPRING_DATASOURCE_USERNAME"),
            "--spring.datasource.password=" + requiredEnvironment("SPRING_DATASOURCE_PASSWORD"),
            "--rhaomi.publisher.owner=initial-content-e2e",
            "--rhaomi.publisher.idle-poll-interval=25ms",
            "--rhaomi.publisher.lease-duration=2m",
            "--rhaomi.publisher.lease-renewal-interval=5s",
            "--rhaomi.publisher.shutdown-timeout=10s",
            "--rhaomi.publisher.lock-file=" + lockFile,
            "--rhaomi.publisher.auto-start=true",
            "--rhaomi.publisher.executor.node-executable=" + nodeExecutable,
            "--rhaomi.publisher.executor.release-script="
                    + sourceRoot.resolve("scripts/publish-static-release.mts"),
            "--rhaomi.publisher.executor.termination-grace=2s",
            "--rhaomi.publisher.executor.build-api-internal-url=" + buildApiUrl,
            "--rhaomi.publisher.executor.build-api-credential=" + BUILD_TOKEN,
            "--rhaomi.publisher.executor.source-root=" + sourceRoot,
            "--rhaomi.publisher.executor.work-root="
                    + tempDirectory.resolve("publisher-e2e-state/publisher").toAbsolutePath(),
            "--rhaomi.publisher.executor.release-root=" + releaseRoot,
            "--rhaomi.publisher.executor.current-link=" + currentLink,
            "--rhaomi.publisher.executor.previous-link=" + previousLink,
            "--rhaomi.publisher.executor.public-site-url=https://initial-content-e2e.example.invalid/",
            "--rhaomi.publisher.executor.code-sha=" + CODE_SHA,
            "--rhaomi.publisher.executor.code-image-tag=sha-" + CODE_SHA,
            "--rhaomi.publisher.executor.code-image-digest=" + IMAGE_DIGEST,
            "--rhaomi.publisher.executor.flyway-version=10",
            "--rhaomi.publisher.executor.sbom-reference=" + IMAGE_DIGEST,
            "--rhaomi.publisher.executor.build-timeout-ms=120000",
            "--rhaomi.publisher.executor.release-retention=5"
        };
    }

    private void awaitPublicationSuccess(String schemaUrl) throws Exception {
        var deadline = System.nanoTime() + Duration.ofMinutes(3).toNanos();
        String status = null;
        while (System.nanoTime() < deadline) {
            status = queryString(
                    schemaUrl,
                    "SELECT state || '|' || COALESCE(last_result_code, '') "
                            + "FROM publishing_outbox");
            if (status.startsWith("SUCCEEDED|")) {
                return;
            }
            if (status.startsWith("FAILED|") || status.startsWith("RETRY_WAIT|")) {
                throw new AssertionError("initial content publication failed: " + status);
            }
            Thread.sleep(100);
        }
        throw new AssertionError("initial content publication timed out: " + status);
    }

    private AnnotationConfigApplicationContext initialContentServiceContext(
            String schemaUrl,
            Path bundleRoot,
            Path mediaRoot,
            InitialContentImportCheckpoint checkpoint) {
        var context = new AnnotationConfigApplicationContext();
        var properties = taskProperties(schemaUrl, mediaRoot);
        context.getEnvironment()
                .getPropertySources()
                .addFirst(new MapPropertySource("initialContentConcurrencyTest", properties));
        context.getBeanFactory().registerSingleton("initialContentInputRoot", bundleRoot);
        context.getBeanFactory().registerSingleton("initialContentClock", IMPORT_CLOCK);
        context.getBeanFactory().registerSingleton("initialContentImportCheckpoint", checkpoint);
        context.getBeanFactory()
                .registerSingleton(
                        "initialContentOutput", new PrintStream(new ByteArrayOutputStream()));
        context.register(ProductionInitialContentTaskConfiguration.class);
        context.refresh();
        return context;
    }

    private void provisionAdmin(String schemaUrl) {
        try (var ignored = ProductionDatabaseTaskApplication.runInitialAdmin(
                new String[] {
                    ProductionDatabaseTaskApplication.INITIAL_ADMIN_ARGUMENT,
                    "--spring.datasource.url=" + schemaUrl,
                    "--spring.datasource.username=" + requiredEnvironment("SPRING_DATASOURCE_USERNAME"),
                    "--spring.datasource.password=" + requiredEnvironment("SPRING_DATASOURCE_PASSWORD")
                },
                () -> new InitialAdminCredential(ADMIN_EMAIL, ADMIN_PASSWORD),
                new PrintStream(new ByteArrayOutputStream()))) {}
    }

    private void assertBuildSnapshotV2(String schemaUrl, Path mediaRoot) throws Exception {
        var dataSource = new DriverManagerDataSource(
                schemaUrl,
                requiredEnvironment("SPRING_DATASOURCE_USERNAME"),
                requiredEnvironment("SPRING_DATASOURCE_PASSWORD"));
        var stateService = new PublicationStateService(new JdbcTemplate(dataSource));
        var claimed = new TransactionTemplate(new DataSourceTransactionManager(dataSource))
                .execute(status -> stateService.claimNext(
                        "initial-content-test",
                        Instant.now(),
                        java.time.Duration.ofMinutes(5)));
        assertTrue(claimed != null && claimed.isPresent());
        assertEquals(1L, claimed.orElseThrow().publishGeneration());

        var application = new SpringApplication(BackendApplication.class);
        application.setWebApplicationType(WebApplicationType.SERVLET);
        var token = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
        try (var context = application.run(
                "--server.port=0",
                "--spring.flyway.enabled=false",
                "--spring.jpa.hibernate.ddl-auto=validate",
                "--spring.datasource.url=" + schemaUrl,
                "--spring.datasource.username=" + requiredEnvironment("SPRING_DATASOURCE_USERNAME"),
                "--spring.datasource.password=" + requiredEnvironment("SPRING_DATASOURCE_PASSWORD"),
                "--rhaomi.media.root=" + mediaRoot,
                "--rhaomi.build-service.token=" + token,
                "--rhaomi.bootstrap-admin.enabled=false",
                "--rhaomi.admin-auth.webauthn.required=false")) {
            var web = (WebServerApplicationContext) context;
            var request = HttpRequest.newBuilder()
                    .uri(URI.create("http://127.0.0.1:"
                            + web.getWebServer().getPort()
                            + "/api/build/snapshot?publishGeneration=1"))
                    .header("Authorization", "Bearer " + token)
                    .GET()
                    .build();
            var response = HttpClient.newHttpClient()
                    .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            assertEquals(200, response.statusCode(), response.body());
            var root = new ObjectMapper().readTree(response.body());
            assertEquals(2, root.get("schemaVersion").asInt());
            assertTrue(root.get("contentRevision").isString());
            assertEquals("1", root.get("contentRevision").asText());
            assertTrue(root.get("publishGeneration").isString());
            assertEquals("1", root.get("publishGeneration").asText());
            assertEquals(InitialContentTestBundle.SHOP_NAME, root.get("shop").get("shopName").asText());
            assertEquals(InitialContentTestBundle.BREED_NAME, root.get("breeds").get(0).get("name").asText());
            assertEquals(InitialContentTestBundle.SERVICE_NAME, root.get("services").get(0).get("name").asText());
            assertEquals(InitialContentTestBundle.NOTICE_TITLE, root.get("notices").get(0).get("title").asText());
            assertEquals(InitialContentTestBundle.GALLERY_DOG_NAME, root.get("galleryItems").get(0).get("dogName").asText());
            assertEquals(1, root.get("mediaAssets").size());
        }
    }

    private String[] taskArguments(String schemaUrl, Path mediaRoot) {
        return new String[] {
            ProductionDatabaseTaskApplication.INITIAL_CONTENT_ARGUMENT,
            "--spring.main.web-application-type=servlet",
            "--spring.flyway.enabled=true",
            "--spring.jpa.hibernate.ddl-auto=create-drop",
            "--spring.datasource.url=" + schemaUrl,
            "--spring.datasource.username=" + requiredEnvironment("SPRING_DATASOURCE_USERNAME"),
            "--spring.datasource.password=" + requiredEnvironment("SPRING_DATASOURCE_PASSWORD"),
            "--rhaomi.media.root=" + mediaRoot
        };
    }

    private HashMap<String, Object> taskProperties(String schemaUrl, Path mediaRoot) {
        var properties = new HashMap<String, Object>();
        properties.put("spring.datasource.url", schemaUrl);
        properties.put(
                "spring.datasource.username", requiredEnvironment("SPRING_DATASOURCE_USERNAME"));
        properties.put(
                "spring.datasource.password", requiredEnvironment("SPRING_DATASOURCE_PASSWORD"));
        properties.put("spring.flyway.enabled", false);
        properties.put("spring.jpa.hibernate.ddl-auto", "validate");
        properties.put("spring.main.web-application-type", "none");
        properties.put("rhaomi.media.root", mediaRoot.toString());
        properties.put("rhaomi.media.max-source-bytes", 20_971_520L);
        properties.put("rhaomi.media.max-stored-bytes", 31_457_280L);
        properties.put("rhaomi.media.max-width", 12_000);
        properties.put("rhaomi.media.max-height", 12_000);
        properties.put("rhaomi.media.max-pixels", 60_000_000L);
        properties.put("rhaomi.media.jpeg-quality", 92);
        return properties;
    }

    private void withMigratedSchema(SchemaAction action) throws Exception {
        var schema = "initial_content_" + UUID.randomUUID().toString().replace("-", "");
        var baseUrl = requiredEnvironment("SPRING_DATASOURCE_URL");
        var username = requiredEnvironment("SPRING_DATASOURCE_USERNAME");
        var password = requiredEnvironment("SPRING_DATASOURCE_PASSWORD");
        createSchema(baseUrl, username, password, schema);
        var schemaUrl = withCurrentSchema(baseUrl, schema);
        try {
            try (var ignored = ProductionDatabaseTaskApplication.run(new String[] {
                ProductionDatabaseTaskApplication.MIGRATE_ARGUMENT,
                "--spring.datasource.url=" + schemaUrl,
                "--spring.datasource.username=" + username,
                "--spring.datasource.password=" + password
            })) {}
            action.run(schemaUrl);
        } finally {
            dropSchema(baseUrl, username, password, schema);
        }
    }

    private long regularFileCount(Path root) throws Exception {
        if (!Files.exists(root)) {
            return 0;
        }
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).count();
        }
    }

    private void assertNoImportedState(String schemaUrl, Path mediaRoot) throws Exception {
        for (var table : List.of(
                "shop_settings",
                "breeds",
                "services",
                "notices",
                "gallery_items",
                "media_assets",
                "publishing_outbox")) {
            assertEquals(0L, queryLong(schemaUrl, "SELECT COUNT(*) FROM " + table), table);
        }
        assertEquals(
                0L,
                queryLong(
                        schemaUrl,
                        "SELECT content_revision FROM content_revision_state WHERE singleton_key = 1"));
        assertEquals(
                0L,
                queryLong(
                        schemaUrl,
                        "SELECT publish_generation FROM publish_generation_state WHERE singleton_key = 1"));
        assertEquals(0L, regularFileCount(mediaRoot.resolve("masters")));
    }

    private long queryLong(String url, String sql) throws SQLException {
        try (var connection = connection(url);
                var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            result.next();
            return result.getLong(1);
        }
    }

    private String queryString(String url, String sql) throws SQLException {
        try (var connection = connection(url);
                var statement = connection.createStatement();
                var result = statement.executeQuery(sql)) {
            result.next();
            return result.getString(1);
        }
    }

    private String queryNullableString(String url, String sql) throws SQLException {
        return queryString(url, sql);
    }

    private java.sql.Connection connection(String url) throws SQLException {
        return DriverManager.getConnection(
                url,
                requiredEnvironment("SPRING_DATASOURCE_USERNAME"),
                requiredEnvironment("SPRING_DATASOURCE_PASSWORD"));
    }

    private void createSchema(String url, String username, String password, String schema)
            throws SQLException {
        try (var connection = DriverManager.getConnection(url, username, password);
                var statement = connection.createStatement()) {
            statement.execute("CREATE SCHEMA " + schema);
        }
    }

    private void dropSchema(String url, String username, String password, String schema)
            throws SQLException {
        try (var connection = DriverManager.getConnection(url, username, password);
                var statement = connection.createStatement()) {
            statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
        }
    }

    private String withCurrentSchema(String url, String schema) {
        return url + (url.contains("?") ? "&" : "?") + "currentSchema=" + schema;
    }

    private String requiredEnvironment(String name) {
        var value = System.getenv(name);
        if (value == null || value.isBlank()) {
            throw new IllegalStateException("Required test database environment is missing");
        }
        return value;
    }

    private Path requiredPath(String name) {
        return Path.of(requiredEnvironment(name)).toAbsolutePath().normalize();
    }

    private String messageChain(Throwable throwable) {
        var messages = new StringBuilder();
        var current = throwable;
        while (current != null) {
            if (current.getMessage() != null) {
                messages.append(current.getMessage()).append('\n');
            }
            current = current.getCause();
        }
        return messages.toString();
    }

    private record TaskOutcome(boolean success, Throwable failure) {}

    @FunctionalInterface
    private interface SchemaAction {
        void run(String schemaUrl) throws Exception;
    }
}
