package kr.co.rhaomi.backend;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.when;

import java.io.ByteArrayInputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.StreamSupport;
import kr.co.rhaomi.backend.admin.AdminUser;
import kr.co.rhaomi.backend.admin.AdminUserRepository;
import kr.co.rhaomi.backend.breed.BreedAdminService;
import kr.co.rhaomi.backend.breed.BreedCreateRequest;
import kr.co.rhaomi.backend.breed.BreedUpdateRequest;
import kr.co.rhaomi.backend.build.BuildDataReader;
import kr.co.rhaomi.backend.build.BuildMediaService;
import kr.co.rhaomi.backend.build.BuildSnapshotService;
import kr.co.rhaomi.backend.gallery.GalleryAdminService;
import kr.co.rhaomi.backend.gallery.GalleryCreateRequest;
import kr.co.rhaomi.backend.gallery.GalleryUpdateRequest;
import kr.co.rhaomi.backend.media.MediaAdminService;
import kr.co.rhaomi.backend.media.MediaProperties;
import kr.co.rhaomi.backend.media.MediaTestFixtures;
import kr.co.rhaomi.backend.notice.NoticeAdminService;
import kr.co.rhaomi.backend.notice.NoticeCreateRequest;
import kr.co.rhaomi.backend.notice.NoticeUpdateRequest;
import kr.co.rhaomi.backend.service.ServiceAdminService;
import kr.co.rhaomi.backend.service.ServiceCreateRequest;
import kr.co.rhaomi.backend.service.ServiceUpdateRequest;
import kr.co.rhaomi.backend.shop.ShopSettingsAdminService;
import kr.co.rhaomi.backend.shop.ShopSettingsRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@ActiveProfiles("test")
class BuildApiIntegrationTests {

    private static final String BUILD_TOKEN =
            "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";
    private static final String ADMIN_EMAIL = "build.integration@example.com";
    private static final String ADMIN_PASSWORD = "local-build-integration-password-123!";
    private static final Instant GENERATED_AT = Instant.parse("2035-01-01T00:00:00.123456Z");

    @LocalServerPort
    private int port;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private AdminUserRepository adminUserRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private BreedAdminService breedAdminService;

    @Autowired
    private ServiceAdminService serviceAdminService;

    @Autowired
    private GalleryAdminService galleryAdminService;

    @Autowired
    private NoticeAdminService noticeAdminService;

    @Autowired
    private ShopSettingsAdminService shopSettingsAdminService;

    @Autowired
    private MediaAdminService mediaAdminService;

    @Autowired
    private MediaProperties mediaProperties;

    @Autowired
    private BuildSnapshotService snapshotService;

    @Autowired
    private BuildMediaService mediaService;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @MockitoBean
    private Clock clock;

    @MockitoSpyBean
    private BuildDataReader buildDataReader;

    private AdminUser admin;

    @BeforeEach
    void setUpFixtures() throws Exception {
        clearFixtures();
        when(clock.instant()).thenReturn(GENERATED_AT);
        admin = adminUserRepository.saveAndFlush(
                AdminUser.create(ADMIN_EMAIL, passwordEncoder.encode(ADMIN_PASSWORD)));
    }

    @AfterEach
    void clearFixturesAfterTest() throws Exception {
        clearFixtures();
    }

    @Test
    void should_returnExactPublishedSnapshotAndDistinctVerifiedManifest_when_generationIsActive()
            throws Exception {
        var heroMedia = uploadPng();
        var ogMedia = uploadJpeg();
        var unlinkedMedia = uploadPng();
        putShop(heroMedia, heroMedia, ogMedia);

        var breedB = publishBreed("B breed", "build-breed-b", 20);
        var breedA = publishBreed("A breed", "build-breed-a", 20);
        createDraftBreed("Draft breed", "build-breed-draft", 1);
        var serviceB = publishService("B service", "build-service-b", 20);
        var serviceA = publishService("A service", "build-service-a", 20);
        createDraftService("Draft service", "build-service-draft", 1);

        var dueGallery = publishGallery(
                "Due dog",
                breedA,
                serviceA,
                heroMedia,
                ogMedia,
                null,
                true,
                50,
                GENERATED_AT.minusSeconds(10));
        var futureGallery = publishGallery(
                "Future dog",
                breedB,
                serviceB,
                unlinkedMedia,
                null,
                null,
                false,
                1,
                GENERATED_AT.plusSeconds(1));

        var pinnedNotice = publishNotice(
                "Pinned notice",
                "build-pinned-notice",
                "**Markdown source**",
                true,
                GENERATED_AT.minusSeconds(20),
                null);
        var newerNotice = publishNotice(
                "Newer notice",
                "build-newer-notice",
                "newer body",
                false,
                GENERATED_AT.minusSeconds(10),
                null);
        var olderNotice = publishNotice(
                "Older notice",
                "build-older-notice",
                "older body",
                false,
                GENERATED_AT.minusSeconds(30),
                null);
        publishNotice(
                "Future notice",
                "build-future-notice",
                "future body",
                true,
                GENERATED_AT.plusSeconds(1),
                null);
        publishNotice(
                "Expired notice",
                "build-expired-notice",
                "expired body",
                true,
                GENERATED_AT.minusSeconds(30),
                GENERATED_AT.minusSeconds(1));

        var generation = activateGeneration(51);
        var expectedRevision = currentRevision();
        jdbcTemplate.update(
                "UPDATE publishing_outbox SET content_revision = 1 WHERE publish_generation = ?",
                generation);
        var response = getString("/api/build/snapshot?publishGeneration=" + generation);

        assertEquals(200, response.statusCode(), response.body());
        var root = objectMapper.readTree(response.body());
        assertEquals(
                Set.of(
                        "schemaVersion",
                        "contentRevision",
                        "publishGeneration",
                        "generatedAt",
                        "shop",
                        "services",
                        "breeds",
                        "galleryItems",
                        "notices",
                        "mediaAssets"),
                fieldNames(root));
        assertEquals(1, root.get("schemaVersion").asInt());
        assertEquals(expectedRevision, root.get("contentRevision").asLong());
        assertEquals(generation, root.get("publishGeneration").asLong());
        assertEquals(GENERATED_AT, Instant.parse(root.get("generatedAt").asText()));
        assertEquals(0, Instant.parse(root.get("generatedAt").asText()).getNano() % 1_000);
        assertFalse(root.has("gpublishGeneration"));
        assertFalse(root.has("codeImageDigest"));

        assertEquals(
                Set.of(
                        "shopName",
                        "regionLabel",
                        "businessType",
                        "phone",
                        "address",
                        "openingTime",
                        "closingTime",
                        "closedWeekday",
                        "parkingAvailable",
                        "parkingNote",
                        "heroTitle",
                        "heroDescription",
                        "groomerName",
                        "groomerIntro",
                        "reservationNotice",
                        "heroImageId",
                        "heroImageAltText",
                        "groomerImageId",
                        "groomerImageAltText",
                        "ogImageId",
                        "instagramUrl",
                        "naverBlogUrl",
                        "naverMapUrl",
                        "kakaoMapUrl",
                        "naverTalktalkUrl",
                        "kakaoChannelUrl"),
                fieldNames(root.get("shop")));
        assertEquals("10:00", root.get("shop").get("openingTime").asText());
        assertEquals("19:00", root.get("shop").get("closingTime").asText());

        assertEquals(List.of(serviceA, serviceB), ids(root.get("services")));
        assertEquals(
                Set.of("id", "name", "slug", "description", "priceText", "sortOrder"),
                fieldNames(root.get("services").get(0)));
        assertEquals(List.of(breedA, breedB), ids(root.get("breeds")));
        assertEquals(
                Set.of("id", "name", "slug", "description", "sortOrder"),
                fieldNames(root.get("breeds").get(0)));
        assertEquals(List.of(dueGallery), ids(root.get("galleryItems")));
        assertEquals(
                Set.of(
                        "id",
                        "dogName",
                        "breedId",
                        "primaryServiceId",
                        "coverImageId",
                        "beforeImageId",
                        "afterImageId",
                        "summary",
                        "altText",
                        "featured",
                        "sortOrder",
                        "performedAt",
                        "publishedAt"),
                fieldNames(root.get("galleryItems").get(0)));
        assertFalse(response.body().contains(futureGallery.toString()));
        assertEquals(
                List.of(pinnedNotice, newerNotice, olderNotice),
                ids(root.get("notices")));
        assertEquals(
                Set.of(
                        "id",
                        "title",
                        "slug",
                        "summary",
                        "bodyMarkdown",
                        "pinned",
                        "publishedAt",
                        "expiresAt"),
                fieldNames(root.get("notices").get(0)));
        assertTrue(response.body().contains("**Markdown source**"));

        var expectedMedia = StreamSupport.stream(
                        List.of(heroMedia, ogMedia).spliterator(), false)
                .map(UUID::toString)
                .sorted()
                .map(UUID::fromString)
                .toList();
        assertEquals(expectedMedia, ids(root.get("mediaAssets")));
        assertEquals(
                Set.of("id", "contentType", "byteSize", "width", "height"),
                fieldNames(root.get("mediaAssets").get(0)));
        assertFalse(response.body().contains(unlinkedMedia.toString()));
        assertPrivateFieldsAbsent(response.body());
    }

    @Test
    void should_includeDescriptionsBeyondTenThousandCharacters_when_publishedContentIsValid()
            throws Exception {
        var breedDescription = "견".repeat(10_001);
        var serviceDescription = "서".repeat(10_001);
        putShop(null, null, null);
        var breed = publishBreed(
                "Long breed", "build-long-breed", breedDescription, 1);
        var service = publishService(
                "Long service", "build-long-service", serviceDescription, 1);
        var generation = activateGeneration(63);

        var response = getString("/api/build/snapshot?publishGeneration=" + generation);

        assertEquals(200, response.statusCode(), response.body());
        var root = objectMapper.readTree(response.body());
        assertEquals(breed.toString(), root.get("breeds").get(0).get("id").asText());
        assertEquals(
                breedDescription,
                root.get("breeds").get(0).get("description").asText());
        assertEquals(service.toString(), root.get("services").get(0).get("id").asText());
        assertEquals(
                serviceDescription,
                root.get("services").get(0).get("description").asText());
    }

    @Test
    void should_applyNoticeUpdatedAtOrdering_when_publishedAtAndPinnedAreEqual() throws Exception {
        putShop(null, null, null);
        var first = publishNotice(
                "First notice",
                "build-notice-first",
                "first body",
                false,
                GENERATED_AT.minusSeconds(10),
                null);
        var second = publishNotice(
                "Second notice",
                "build-notice-second",
                "second body",
                false,
                GENERATED_AT.minusSeconds(10),
                null);
        jdbcTemplate.update(
                "UPDATE notices SET updated_at = ? WHERE id = ?",
                GENERATED_AT.minusSeconds(2).atOffset(ZoneOffset.UTC),
                first);
        jdbcTemplate.update(
                "UPDATE notices SET updated_at = ? WHERE id = ?",
                GENERATED_AT.minusSeconds(1).atOffset(ZoneOffset.UTC),
                second);

        var response = snapshotService.snapshot(activateGeneration(52));

        assertEquals(List.of(second, first), response.notices().stream()
                .map(notice -> notice.id())
                .toList());
    }

    @Test
    void should_failWholeSnapshot_when_shopIsMissingOrSelectedMediaIsArchivedOrCorrupt()
            throws Exception {
        setRevision(1);
        var missingShopGeneration = activateGeneration(53);
        assertError(
                getString("/api/build/snapshot?publishGeneration=" + missingShopGeneration),
                422,
                "BUILD_SNAPSHOT_INVALID");

        clearContentFixturesOnly();
        putShop(null, null, null);
        jdbcTemplate.update(
                "UPDATE shop_settings SET instagram_url = 'http://insecure.example.com'");
        assertError(
                getString("/api/build/snapshot?publishGeneration=" + activateGeneration(54)),
                422,
                "BUILD_SNAPSHOT_INVALID");

        jdbcTemplate.update(
                "UPDATE shop_settings SET instagram_url = 'https://example.com/instagram'");
        var media = uploadPng();
        putShop(media, null, null);
        jdbcTemplate.update("UPDATE media_assets SET status = 'archived' WHERE id = ?", media);
        assertError(
                getString("/api/build/snapshot?publishGeneration=" + activateGeneration(55)),
                422,
                "BUILD_SNAPSHOT_INVALID");

        jdbcTemplate.update("UPDATE media_assets SET status = 'active' WHERE id = ?", media);
        Files.write(storagePath(media), new byte[] {1, 2, 3});
        assertError(
                getString("/api/build/snapshot?publishGeneration=" + activateGeneration(56)),
                422,
                "BUILD_SNAPSHOT_INVALID");
    }

    @Test
    void should_failWholeSnapshot_when_publishedGalleryRelationOrFileIsInvalid() throws Exception {
        var cover = uploadPng();
        putShop(null, null, null);
        var breed = publishBreed("Gallery breed", "build-gallery-breed", 1);
        var service = publishService("Gallery service", "build-gallery-service", 1);
        publishGallery(
                "Gallery dog",
                breed,
                service,
                cover,
                null,
                null,
                false,
                1,
                GENERATED_AT.minusSeconds(1));

        jdbcTemplate.update("UPDATE breeds SET status = 'draft' WHERE id = ?", breed);
        assertError(
                getString("/api/build/snapshot?publishGeneration=" + activateGeneration(56)),
                422,
                "BUILD_SNAPSHOT_INVALID");

        jdbcTemplate.update("UPDATE breeds SET status = 'published' WHERE id = ?", breed);
        jdbcTemplate.update("UPDATE breeds SET description = E'\\t' WHERE id = ?", breed);
        assertError(
                getString("/api/build/snapshot?publishGeneration=" + activateGeneration(57)),
                422,
                "BUILD_SNAPSHOT_INVALID");

        jdbcTemplate.update("UPDATE breeds SET description = '공개 견종 설명' WHERE id = ?", breed);
        jdbcTemplate.update("UPDATE services SET description = E'\\t' WHERE id = ?", service);
        assertError(
                getString("/api/build/snapshot?publishGeneration=" + activateGeneration(58)),
                422,
                "BUILD_SNAPSHOT_INVALID");

        jdbcTemplate.update("UPDATE services SET description = '공개 서비스 설명' WHERE id = ?", service);
        Files.delete(storagePath(cover));
        assertError(
                getString("/api/build/snapshot?publishGeneration=" + activateGeneration(59)),
                422,
                "BUILD_SNAPSHOT_INVALID");
    }

    @Test
    void should_downloadOnlyCurrentPublicMediaWithPrivateHeaders_when_generationIsActive()
            throws Exception {
        var publicMedia = uploadPng();
        var expectedBytes = MediaTestFixtures.resource("synthetic-source.png");
        var unlinked = uploadPng();
        var draftOnly = uploadPng();
        var futureOnly = uploadPng();
        var archivedGalleryOnly = uploadPng();
        var dueGalleryOnly = uploadPng();
        var archived = uploadPng();
        putShop(publicMedia, null, null);
        var breed = publishBreed("Media breed", "build-media-breed", 1);
        var service = publishService("Media service", "build-media-service", 1);
        createDraftGallery(breed, service, draftOnly);
        var archivedGallery = createDraftGallery(breed, service, archivedGalleryOnly);
        jdbcTemplate.update(
                "UPDATE gallery_items SET status = 'archived' WHERE id = ?", archivedGallery);
        publishGallery(
                "Due media dog",
                breed,
                service,
                dueGalleryOnly,
                null,
                null,
                false,
                2,
                GENERATED_AT.minusSeconds(1));
        publishGallery(
                "Future media dog",
                breed,
                service,
                futureOnly,
                null,
                null,
                false,
                1,
                GENERATED_AT.plusSeconds(1));
        jdbcTemplate.update("UPDATE media_assets SET status = 'archived' WHERE id = ?", archived);
        var generation = activateGeneration(58);

        var success = getBytes(
                "/api/build/media/" + publicMedia + "/content?publishGeneration=" + generation);
        assertEquals(200, success.statusCode());
        assertArrayEquals(expectedBytes, success.body());
        assertEquals("image/png", success.headers().firstValue("content-type").orElseThrow());
        assertEquals(
                Integer.toString(expectedBytes.length),
                success.headers().firstValue("content-length").orElseThrow());
        assertEquals(
                "private, no-store",
                success.headers().firstValue("cache-control").orElseThrow());
        assertEquals(
                "nosniff",
                success.headers().firstValue("x-content-type-options").orElseThrow());
        assertTrue(success.headers().allValues("etag").isEmpty());
        assertTrue(success.headers().allValues("accept-ranges").isEmpty());

        var gallerySuccess = getBytes(
                "/api/build/media/" + dueGalleryOnly + "/content?publishGeneration=" + generation);
        assertEquals(200, gallerySuccess.statusCode());
        assertArrayEquals(expectedBytes, gallerySuccess.body());

        String firstNotFoundBody = null;
        for (var id : List.of(
                unlinked,
                draftOnly,
                futureOnly,
                archivedGalleryOnly,
                archived,
                UUID.randomUUID())) {
            var denied = getString(
                    "/api/build/media/" + id + "/content?publishGeneration=" + generation);
            assertError(denied, 404, "BUILD_MEDIA_NOT_FOUND");
            if (firstNotFoundBody == null) {
                firstNotFoundBody = denied.body();
            } else {
                assertEquals(firstNotFoundBody, denied.body());
            }
        }
        assertError(
                getString("/api/build/media/not-a-uuid/content?publishGeneration=" + generation),
                400,
                "INVALID_REQUEST");
        assertError(
                getString("/api/build/media/" + publicMedia + "/content?publishGeneration=999"),
                409,
                "BUILD_GENERATION_NOT_ACTIVE");

        Files.write(storagePath(publicMedia), new byte[expectedBytes.length]);
        var unavailable = getString(
                "/api/build/media/" + publicMedia + "/content?publishGeneration=" + generation);
        assertError(unavailable, 503, "BUILD_MEDIA_UNAVAILABLE");
        assertFalse(unavailable.body().contains(storagePath(publicMedia).toString()));
    }

    @Test
    void should_leavePublicationAndContentStateUnchanged_when_snapshotAndMediaAreRead() throws Exception {
        var media = uploadPng();
        putShop(media, null, null);
        var generation = activateGeneration(59);
        var before = databaseState();

        snapshotService.snapshot(generation);
        mediaService.content(media, generation);

        assertEquals(before, databaseState());
    }

    @Test
    void should_returnFixedGenericErrorWithoutInternalDetail_when_repositoryFails()
            throws Exception {
        putShop(null, null, null);
        var generation = activateGeneration(60);
        doAnswer(invocation -> {
                    throw new DataAccessResourceFailureException(
                            "private database host and SQL detail");
                })
                .when(buildDataReader)
                .currentContentRevision();

        var response = getString("/api/build/snapshot?publishGeneration=" + generation);

        assertError(response, 500, "BUILD_INTERNAL_ERROR");
        assertFalse(response.body().contains("private database host"));
        assertFalse(response.body().contains("SQL"));
    }

    @Test
    void should_readOneRepeatableSnapshot_when_adminMutationCommitsBetweenCollections()
            throws Exception {
        putShop(null, null, null);
        var serviceId = publishService("Before mutation", "build-repeatable-service", 1);
        var generation = activateGeneration(61);
        var beforeRevision = currentRevision();
        var readerReached = new CountDownLatch(1);
        var resumeReader = new CountDownLatch(1);
        doAnswer(invocation -> {
                    readerReached.countDown();
                    assertTrue(resumeReader.await(10, TimeUnit.SECONDS));
                    return invocation.callRealMethod();
                })
                .when(buildDataReader)
                .publishedServices();

        var executor = Executors.newSingleThreadExecutor();
        try {
            var firstSnapshot = executor.submit(() -> snapshotService.snapshot(generation));
            assertTrue(readerReached.await(10, TimeUnit.SECONDS));

            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                jdbcTemplate.update(
                        "UPDATE services SET name = 'After mutation', updated_at = CURRENT_TIMESTAMP WHERE id = ?",
                        serviceId);
                jdbcTemplate.update(
                        "UPDATE content_revision_state SET content_revision = content_revision + 1 WHERE singleton_key = 1");
            });
            resumeReader.countDown();

            var first = firstSnapshot.get(10, TimeUnit.SECONDS);
            assertEquals(beforeRevision, first.contentRevision());
            assertEquals("Before mutation", first.services().getFirst().name());

            var second = snapshotService.snapshot(generation);
            assertEquals(beforeRevision + 1, second.contentRevision());
            assertEquals("After mutation", second.services().getFirst().name());
        } finally {
            resumeReader.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void should_rejectInactiveAndInvalidGenerationWithoutReturningPartialSnapshot() throws Exception {
        putShop(null, null, null);
        var generation = activateGeneration(62);

        for (var invalid : List.of("0", "-1", "not-a-number")) {
            assertError(
                    getString("/api/build/snapshot?publishGeneration=" + invalid),
                    400,
                    "INVALID_REQUEST");
        }
        assertError(getString("/api/build/snapshot"), 400, "INVALID_REQUEST");
        assertError(
                getString("/api/build/snapshot?publishGeneration=999"),
                409,
                "BUILD_GENERATION_NOT_ACTIVE");
        jdbcTemplate.update(
                "UPDATE publishing_outbox SET lease_until = ? WHERE publish_generation = ?",
                GENERATED_AT.minusSeconds(1).atOffset(ZoneOffset.UTC),
                generation);
        assertError(
                getString("/api/build/snapshot?publishGeneration=" + generation),
                409,
                "BUILD_GENERATION_NOT_ACTIVE");
        jdbcTemplate.update(
                """
                UPDATE publishing_outbox
                SET state = 'RETRY_WAIT', claim_owner = NULL, lease_until = NULL,
                    next_attempt_at = ?, last_result_code = 'TRANSIENT_FAILURE'
                WHERE publish_generation = ?
                """,
                GENERATED_AT.plusSeconds(60).atOffset(ZoneOffset.UTC),
                generation);
        assertError(
                getString("/api/build/snapshot?publishGeneration=" + generation),
                409,
                "BUILD_GENERATION_NOT_ACTIVE");
        jdbcTemplate.update(
                """
                UPDATE publishing_outbox
                SET state = 'SUCCEEDED', next_attempt_at = NULL, completed_at = ?,
                    last_result_code = 'SUCCESS'
                WHERE publish_generation = ?
                """,
                GENERATED_AT.atOffset(ZoneOffset.UTC),
                generation);
        assertError(
                getString("/api/build/snapshot?publishGeneration=" + generation),
                409,
                "BUILD_GENERATION_NOT_ACTIVE");
    }

    private UUID publishBreed(String name, String slug, int sortOrder) {
        return publishBreed(name, slug, "공개 견종 설명", sortOrder);
    }

    private UUID publishBreed(String name, String slug, String description, int sortOrder) {
        var created = breedAdminService.create(
                new BreedCreateRequest(name, slug, description, sortOrder), admin.getId());
        return breedAdminService
                .update(
                        created.id(),
                        new BreedUpdateRequest("published", name, description, sortOrder),
                        admin.getId())
                .id();
    }

    private UUID createDraftBreed(String name, String slug, int sortOrder) {
        return breedAdminService
                .create(
                        new BreedCreateRequest(name, slug, "draft breed", sortOrder),
                        admin.getId())
                .id();
    }

    private UUID publishService(String name, String slug, int sortOrder) {
        return publishService(name, slug, "공개 서비스 설명", sortOrder);
    }

    private UUID publishService(String name, String slug, String description, int sortOrder) {
        var created = serviceAdminService.create(
                new ServiceCreateRequest(name, slug, description, "상담 후 안내", sortOrder),
                admin.getId());
        return serviceAdminService
                .update(
                        created.id(),
                        new ServiceUpdateRequest(
                                "published",
                                name,
                                description,
                                "상담 후 안내",
                                sortOrder),
                        admin.getId())
                .id();
    }

    private UUID createDraftService(String name, String slug, int sortOrder) {
        return serviceAdminService
                .create(
                        new ServiceCreateRequest(
                                name, slug, "draft service", "draft price", sortOrder),
                        admin.getId())
                .id();
    }

    private UUID publishGallery(
            String dogName,
            UUID breedId,
            UUID serviceId,
            UUID coverId,
            UUID beforeId,
            UUID afterId,
            boolean featured,
            int sortOrder,
            Instant publishedAt) {
        var created = galleryAdminService.create(
                new GalleryCreateRequest(
                        dogName,
                        breedId,
                        serviceId,
                        coverId,
                        beforeId,
                        afterId,
                        "갤러리 요약",
                        "미용 완료 사진",
                        featured,
                        sortOrder,
                        GENERATED_AT.minusSeconds(3_600),
                        publishedAt),
                admin.getId());
        return galleryAdminService
                .update(
                        created.id(),
                        new GalleryUpdateRequest(
                                "published",
                                dogName,
                                breedId,
                                serviceId,
                                coverId,
                                beforeId,
                                afterId,
                                "갤러리 요약",
                                "미용 완료 사진",
                                featured,
                                sortOrder,
                                GENERATED_AT.minusSeconds(3_600),
                                publishedAt),
                        admin.getId())
                .id();
    }

    private UUID createDraftGallery(UUID breedId, UUID serviceId, UUID coverId) {
        return galleryAdminService
                .create(
                        new GalleryCreateRequest(
                                "Draft dog",
                                breedId,
                                serviceId,
                                coverId,
                                null,
                                null,
                                "draft gallery",
                                "draft alt",
                                false,
                                1,
                                null,
                                GENERATED_AT.minusSeconds(1)),
                        admin.getId())
                .id();
    }

    private UUID publishNotice(
            String title,
            String slug,
            String body,
            boolean pinned,
            Instant publishedAt,
            Instant expiresAt) {
        var created = noticeAdminService.create(
                new NoticeCreateRequest(
                        title, slug, "공지 요약", body, pinned, publishedAt, expiresAt),
                admin.getId());
        return noticeAdminService
                .update(
                        created.id(),
                        new NoticeUpdateRequest(
                                "published",
                                title,
                                "공지 요약",
                                body,
                                pinned,
                                publishedAt,
                                expiresAt),
                        admin.getId())
                .id();
    }

    private void putShop(UUID heroId, UUID groomerId, UUID ogId) {
        shopSettingsAdminService.put(
                new ShopSettingsRequest(
                        "라오미펫",
                        "용인 처인구",
                        "애견미용",
                        "031-123-4567",
                        "경기도 용인시 처인구",
                        "10:00",
                        "19:00",
                        "MONDAY",
                        true,
                        "매장 앞 주차",
                        "반려견을 위한 미용",
                        "편안하고 안전한 미용",
                        "라오미 원장",
                        "반려견의 상태를 먼저 확인합니다.",
                        "예약 전 안내를 확인해 주세요.",
                        heroId,
                        heroId == null ? null : "매장 대표 이미지",
                        groomerId,
                        groomerId == null ? null : "미용사 소개 이미지",
                        ogId,
                        "https://example.com/instagram",
                        "https://example.com/blog",
                        "https://example.com/naver-map",
                        "https://example.com/kakao-map",
                        "https://example.com/talktalk",
                        "https://example.com/channel"),
                admin.getId());
    }

    private UUID uploadPng() {
        var bytes = MediaTestFixtures.resource("synthetic-source.png");
        return mediaAdminService
                .upload(
                        new ByteArrayInputStream(bytes),
                        "image/png",
                        "build-source.png",
                        admin.getId())
                .id();
    }

    private UUID uploadJpeg() {
        var bytes = MediaTestFixtures.jpeg();
        return mediaAdminService
                .upload(
                        new ByteArrayInputStream(bytes),
                        "image/jpeg",
                        "build-source.jpg",
                        admin.getId())
                .id();
    }

    private long activateGeneration(long generation) {
        jdbcTemplate.update(
                "UPDATE publish_generation_state SET publish_generation = ? WHERE singleton_key = 1",
                generation);
        jdbcTemplate.update(
                """
                INSERT INTO publishing_outbox (
                    id, kind, source_type, source_id, content_revision, available_at,
                    state, publish_generation, attempt_count, claim_owner, claimed_at, lease_until
                ) VALUES (?, 'CONTENT_CHANGED', 'SHOP_SETTINGS', ?, ?, ?,
                          'PROCESSING', ?, 1, 'build-integration-publisher', ?, ?)
                """,
                UUID.randomUUID(),
                UUID.randomUUID(),
                Math.max(1, currentRevision()),
                GENERATED_AT.minusSeconds(60).atOffset(ZoneOffset.UTC),
                generation,
                GENERATED_AT.minusSeconds(30).atOffset(ZoneOffset.UTC),
                GENERATED_AT.plusSeconds(300).atOffset(ZoneOffset.UTC));
        return generation;
    }

    private HttpResponse<String> getString(String path) throws Exception {
        var request = HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + BUILD_TOKEN)
                .GET()
                .build();
        return HttpClient.newHttpClient()
                .send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
    }

    private HttpResponse<byte[]> getBytes(String path) throws Exception {
        var request = HttpRequest.newBuilder(uri(path))
                .header("Authorization", "Bearer " + BUILD_TOKEN)
                .GET()
                .build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofByteArray());
    }

    private URI uri(String path) {
        return URI.create("http://127.0.0.1:" + port + path);
    }

    private void assertError(HttpResponse<String> response, int status, String code) {
        assertEquals(status, response.statusCode(), response.body());
        assertTrue(response.body().contains("\"code\":\"" + code + "\""), response.body());
        assertFalse(response.body().contains(BUILD_TOKEN));
        assertFalse(response.body().toLowerCase().contains("exception"));
        assertFalse(response.body().toLowerCase().contains("constraint"));
    }

    private void assertPrivateFieldsAbsent(String body) {
        for (var key : List.of(
                "createdAt",
                "updatedAt",
                "createdBy",
                "updatedBy",
                "status",
                "storageKey",
                "fileExtension",
                "sha256",
                "sourceContentType",
                "password",
                "session",
                "csrf",
                "claimOwner",
                "leaseUntil",
                "eventId")) {
            assertFalse(body.contains("\"" + key + "\""), key);
        }
    }

    private Set<String> fieldNames(JsonNode node) {
        return new LinkedHashSet<>(node.propertyNames());
    }

    private List<UUID> ids(JsonNode array) {
        var ids = new ArrayList<UUID>();
        array.forEach(item -> ids.add(UUID.fromString(item.get("id").asText())));
        return ids;
    }

    private long currentRevision() {
        return jdbcTemplate.queryForObject(
                "SELECT content_revision FROM content_revision_state WHERE singleton_key = 1",
                Long.class);
    }

    private void setRevision(long revision) {
        jdbcTemplate.update(
                "UPDATE content_revision_state SET content_revision = ? WHERE singleton_key = 1",
                revision);
    }

    private Path storagePath(UUID id) {
        var storageKey = jdbcTemplate.queryForObject(
                "SELECT storage_key FROM media_assets WHERE id = ?", String.class, id);
        return Path.of(mediaProperties.root()).toAbsolutePath().normalize().resolve(storageKey);
    }

    private Map<String, String> databaseState() {
        var state = new LinkedHashMap<String, String>();
        for (var table : List.of(
                "content_revision_state",
                "publish_generation_state",
                "publishing_outbox",
                "shop_settings",
                "breeds",
                "services",
                "gallery_items",
                "notices",
                "media_assets")) {
            state.put(table, tableJson(table));
        }
        return state;
    }

    private String tableJson(String table) {
        return jdbcTemplate.queryForObject(
                "SELECT COALESCE(jsonb_agg(to_jsonb(row)), '[]'::jsonb)::text "
                        + "FROM (SELECT * FROM " + table + " ORDER BY 1) row",
                String.class);
    }

    private void clearContentFixturesOnly() throws Exception {
        jdbcTemplate.update("DELETE FROM publishing_outbox");
        jdbcTemplate.update("DELETE FROM gallery_items");
        jdbcTemplate.update("DELETE FROM shop_settings");
        jdbcTemplate.update("DELETE FROM notices");
        jdbcTemplate.update("DELETE FROM media_assets");
        jdbcTemplate.update("DELETE FROM services");
        jdbcTemplate.update("DELETE FROM breeds");
        setRevision(0);
        jdbcTemplate.update(
                "UPDATE publish_generation_state SET publish_generation = 0 WHERE singleton_key = 1");
        clearDirectoryContents(mediaRoot().resolve("temp"));
        clearDirectoryContents(mediaRoot().resolve("masters"));
    }

    private void clearFixtures() throws Exception {
        clearContentFixturesOnly();
        adminUserRepository.findByEmail(ADMIN_EMAIL).ifPresent(adminUserRepository::delete);
        adminUserRepository.flush();
    }

    private Path mediaRoot() {
        return Path.of(mediaProperties.root()).toAbsolutePath().normalize();
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
