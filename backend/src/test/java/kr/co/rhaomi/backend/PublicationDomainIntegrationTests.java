package kr.co.rhaomi.backend;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.UUID;
import kr.co.rhaomi.backend.admin.AdminUser;
import kr.co.rhaomi.backend.admin.AdminUserRepository;
import kr.co.rhaomi.backend.breed.BreedAdminService;
import kr.co.rhaomi.backend.breed.BreedCreateRequest;
import kr.co.rhaomi.backend.breed.BreedUpdateRequest;
import kr.co.rhaomi.backend.content.NoticeWindowInvalidException;
import kr.co.rhaomi.backend.gallery.GalleryAdminService;
import kr.co.rhaomi.backend.gallery.GalleryCreateRequest;
import kr.co.rhaomi.backend.gallery.GalleryRelationInvalidException;
import kr.co.rhaomi.backend.gallery.GalleryUpdateRequest;
import kr.co.rhaomi.backend.media.MediaAdminService;
import kr.co.rhaomi.backend.media.MediaProperties;
import kr.co.rhaomi.backend.media.MediaStatus;
import kr.co.rhaomi.backend.media.MediaTypeUnsupportedException;
import kr.co.rhaomi.backend.notice.NoticeAdminService;
import kr.co.rhaomi.backend.notice.NoticeCreateRequest;
import kr.co.rhaomi.backend.notice.NoticeUpdateRequest;
import kr.co.rhaomi.backend.service.ServiceAdminService;
import kr.co.rhaomi.backend.service.ServiceCreateRequest;
import kr.co.rhaomi.backend.service.ServiceUpdateRequest;
import kr.co.rhaomi.backend.shop.BusinessHoursInvalidException;
import kr.co.rhaomi.backend.shop.ShopSettingsAdminService;
import kr.co.rhaomi.backend.shop.ShopSettingsRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;

@SpringBootTest
@ActiveProfiles("test")
class PublicationDomainIntegrationTests {

    private static final String ADMIN_EMAIL = "publication.domain@example.com";
    private static final String ADMIN_PASSWORD = "local-publication-password-123!";

    @Autowired
    private BreedAdminService breedAdminService;

    @Autowired
    private ServiceAdminService serviceAdminService;

    @Autowired
    private NoticeAdminService noticeAdminService;

    @Autowired
    private ShopSettingsAdminService shopSettingsAdminService;

    @Autowired
    private GalleryAdminService galleryAdminService;

    @Autowired
    private MediaAdminService mediaAdminService;

    @Autowired
    private MediaProperties mediaProperties;

    @Autowired
    private AdminUserRepository adminUserRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
    void should_recordRevisionAndImmediateMatrix_when_breedAndServiceMutate() {
        var breed = breedAdminService.create(
                new BreedCreateRequest("푸들", "publication-poodle", null, 100),
                admin.getId());
        breedAdminService.update(
                breed.id(),
                new BreedUpdateRequest("archived", "푸들", null, 100),
                admin.getId());
        breedAdminService.update(
                breed.id(),
                new BreedUpdateRequest("published", "토이 푸들", null, 50),
                admin.getId());
        breedAdminService.update(
                breed.id(),
                new BreedUpdateRequest("published", "미니 푸들", "공개 설명", 40),
                admin.getId());
        breedAdminService.update(
                breed.id(),
                new BreedUpdateRequest("draft", "미니 푸들", "공개 설명", 40),
                admin.getId());

        var service = serviceAdminService.create(
                new ServiceCreateRequest(
                        "전체미용", "publication-full-grooming", null, null, 100),
                admin.getId());
        serviceAdminService.update(
                service.id(),
                new ServiceUpdateRequest("archived", "전체미용", null, null, 100),
                admin.getId());
        serviceAdminService.update(
                service.id(),
                new ServiceUpdateRequest(
                        "published", "전체미용", "견종별 스타일", "상담 후 안내", 50),
                admin.getId());
        serviceAdminService.update(
                service.id(),
                new ServiceUpdateRequest(
                        "published", "전체미용", "스타일 상담", "가격 상담", 40),
                admin.getId());
        serviceAdminService.update(
                service.id(),
                new ServiceUpdateRequest(
                        "archived", "전체미용", "스타일 상담", "가격 상담", 40),
                admin.getId());

        assertEquals(10L, currentRevision());
        assertEquals(6, eventCount());
        assertEquals(0, eventCountAtRevision(1));
        assertEquals(0, eventCountAtRevision(2));
        assertImmediate(3, "BREED", breed.id());
        assertImmediate(4, "BREED", breed.id());
        assertImmediate(5, "BREED", breed.id());
        assertEquals(0, eventCountAtRevision(6));
        assertEquals(0, eventCountAtRevision(7));
        assertImmediate(8, "SERVICE", service.id());
        assertImmediate(9, "SERVICE", service.id());
        assertImmediate(10, "SERVICE", service.id());
    }

    @Test
    void should_recordImmediateEventForEverySuccessfulPut_when_shopSettingsMutate() {
        var request = validShopRequest("라오미펫");
        var created = shopSettingsAdminService.put(request, admin.getId());
        var sourceId = jdbcTemplate.queryForObject("SELECT id FROM shop_settings", UUID.class);
        var updated = shopSettingsAdminService.put(
                validShopRequest("라오미펫 애견미용"), admin.getId());

        assertTrue(created.created());
        assertFalse(updated.created());
        assertEquals(2L, currentRevision());
        assertEquals(2, eventCount());
        assertImmediate(1, "SHOP_SETTINGS", sourceId);
        assertImmediate(2, "SHOP_SETTINGS", sourceId);
    }

    @Test
    void should_recordChangedNoticeBoundariesAndPreserveOldEvents_when_noticeMutates() {
        var firstPublishedAt = Instant.parse("2030-01-01T00:00:00.123456Z");
        var firstExpiresAt = Instant.parse("2030-02-01T00:00:00.123456Z");
        var secondPublishedAt = Instant.parse("2031-01-01T00:00:00.654321Z");
        var secondExpiresAt = Instant.parse("2031-02-01T00:00:00.654321Z");
        var notice = noticeAdminService.create(
                new NoticeCreateRequest(
                        "예약 공지",
                        "publication-scheduled-notice",
                        null,
                        "예약 공지 본문",
                        false,
                        firstPublishedAt,
                        firstExpiresAt),
                admin.getId());
        noticeAdminService.update(
                notice.id(),
                new NoticeUpdateRequest(
                        "draft",
                        "예약 공지 수정",
                        null,
                        "예약 공지 본문",
                        false,
                        firstPublishedAt,
                        firstExpiresAt),
                admin.getId());
        noticeAdminService.update(
                notice.id(),
                new NoticeUpdateRequest(
                        "published",
                        "예약 공지 수정",
                        null,
                        "예약 공지 본문",
                        false,
                        firstPublishedAt,
                        firstExpiresAt),
                admin.getId());
        noticeAdminService.update(
                notice.id(),
                new NoticeUpdateRequest(
                        "published",
                        "재예약 공지",
                        null,
                        "재예약 공지 본문",
                        true,
                        secondPublishedAt,
                        secondExpiresAt),
                admin.getId());
        noticeAdminService.update(
                notice.id(),
                new NoticeUpdateRequest(
                        "archived",
                        "재예약 공지",
                        null,
                        "재예약 공지 본문",
                        true,
                        secondPublishedAt,
                        secondExpiresAt),
                admin.getId());

        assertEquals(5L, currentRevision());
        assertEquals(7, eventCount());
        assertScheduled(
                1,
                "NOTICE_PUBLISHED_AT_DUE",
                "NOTICE",
                notice.id(),
                firstPublishedAt);
        assertScheduled(
                1,
                "NOTICE_EXPIRES_AT_DUE",
                "NOTICE",
                notice.id(),
                firstExpiresAt);
        assertEquals(0, eventCountAtRevision(2));
        assertImmediate(3, "NOTICE", notice.id());
        assertImmediate(4, "NOTICE", notice.id());
        assertScheduled(
                4,
                "NOTICE_PUBLISHED_AT_DUE",
                "NOTICE",
                notice.id(),
                secondPublishedAt);
        assertScheduled(
                4,
                "NOTICE_EXPIRES_AT_DUE",
                "NOTICE",
                notice.id(),
                secondExpiresAt);
        assertImmediate(5, "NOTICE", notice.id());
        assertEquals(4, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM publishing_outbox WHERE kind <> 'CONTENT_CHANGED'",
                Integer.class));
    }

    @Test
    void should_recordGalleryBoundaryOnPublishAndReschedule_when_galleryMutates() {
        var breedId = insertBreed("published", "publication-gallery-breed");
        var serviceId = insertService("published", "publication-gallery-service");
        var coverId = insertMedia("active");
        var firstPublishedAt = Instant.parse("2032-01-01T00:00:00.123456Z");
        var secondPublishedAt = Instant.parse("2033-01-01T00:00:00.654321Z");
        var gallery = galleryAdminService.create(
                new GalleryCreateRequest(
                        "라미",
                        breedId,
                        serviceId,
                        coverId,
                        null,
                        null,
                        "미용 전후",
                        "라미의 미용 완료 사진",
                        false,
                        100,
                        null,
                        firstPublishedAt),
                admin.getId());
        galleryAdminService.update(
                gallery.id(),
                galleryUpdate("published", breedId, serviceId, coverId, "첫 공개", firstPublishedAt),
                admin.getId());
        galleryAdminService.update(
                gallery.id(),
                galleryUpdate("published", breedId, serviceId, coverId, "공개 수정", firstPublishedAt),
                admin.getId());
        galleryAdminService.update(
                gallery.id(),
                galleryUpdate("published", breedId, serviceId, coverId, "공개 재예약", secondPublishedAt),
                admin.getId());
        galleryAdminService.update(
                gallery.id(),
                galleryUpdate("archived", breedId, serviceId, coverId, "보관", secondPublishedAt),
                admin.getId());

        assertEquals(5L, currentRevision());
        assertEquals(6, eventCount());
        assertEquals(0, eventCountAtRevision(1));
        assertImmediate(2, "GALLERY_ITEM", gallery.id());
        assertScheduled(
                2,
                "GALLERY_PUBLISHED_AT_DUE",
                "GALLERY_ITEM",
                gallery.id(),
                firstPublishedAt);
        assertImmediate(3, "GALLERY_ITEM", gallery.id());
        assertImmediate(4, "GALLERY_ITEM", gallery.id());
        assertScheduled(
                4,
                "GALLERY_PUBLISHED_AT_DUE",
                "GALLERY_ITEM",
                gallery.id(),
                secondPublishedAt);
        assertImmediate(5, "GALLERY_ITEM", gallery.id());
        assertEquals(2, jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM publishing_outbox WHERE kind = 'GALLERY_PUBLISHED_AT_DUE'",
                Integer.class));
    }

    @Test
    void should_recordRevisionOnlyForUploadAndImmediateForStatusChanges_when_mediaMutates()
            throws Exception {
        final UUID mediaId;
        try (var inputStream = resource("/media/synthetic-source.png")) {
            var uploaded = mediaAdminService.upload(
                    inputStream, "image/png", "publication-source.png", admin.getId());
            mediaId = uploaded.id();
        }
        mediaAdminService.updateStatus(mediaId, MediaStatus.ARCHIVED, admin.getId());
        mediaAdminService.updateStatus(mediaId, MediaStatus.ACTIVE, admin.getId());

        assertEquals(3L, currentRevision());
        assertEquals(2, eventCount());
        assertEquals(0, eventCountAtRevision(1));
        assertImmediate(2, "MEDIA_ASSET", mediaId);
        assertImmediate(3, "MEDIA_ASSET", mediaId);
    }

    @Test
    void should_notRecordRevisionOrEvent_when_domainValidationFails() throws Exception {
        var boundary = Instant.parse("2030-01-01T00:00:00.123456Z");

        assertThrows(
                NoticeWindowInvalidException.class,
                () -> noticeAdminService.create(
                        new NoticeCreateRequest(
                                "잘못된 기간",
                                "publication-invalid-window",
                                null,
                                null,
                                false,
                                boundary,
                                boundary),
                        admin.getId()));
        assertThrows(
                GalleryRelationInvalidException.class,
                () -> galleryAdminService.create(
                        new GalleryCreateRequest(
                                null,
                                UUID.randomUUID(),
                                null,
                                null,
                                null,
                                null,
                                null,
                                null,
                                false,
                                100,
                                null,
                                null),
                        admin.getId()));
        assertThrows(
                BusinessHoursInvalidException.class,
                () -> shopSettingsAdminService.put(
                        invalidHoursShopRequest(), admin.getId()));
        assertThrows(
                MediaTypeUnsupportedException.class,
                () -> mediaAdminService.upload(
                        new ByteArrayInputStream(new byte[] {1, 2, 3, 4}),
                        "application/octet-stream",
                        "invalid.bin",
                        admin.getId()));

        assertEquals(0L, currentRevision());
        assertEquals(0, eventCount());
        assertEquals(0, rowCount("notices"));
        assertEquals(0, rowCount("gallery_items"));
        assertEquals(0, rowCount("shop_settings"));
        assertEquals(0, rowCount("media_assets"));
        assertEquals(0L, regularFileCount(mediaRoot().resolve("masters")));
        assertEquals(0L, regularFileCount(mediaRoot().resolve("temp")));
    }

    @Test
    void should_rollbackContentAndRevision_when_outboxInsertFails() {
        jdbcTemplate.execute(
                """
                ALTER TABLE publishing_outbox
                ADD CONSTRAINT ck_publishing_outbox_test_reject_all CHECK (FALSE)
                """);
        try {
            assertThrows(
                    DataAccessException.class,
                    () -> shopSettingsAdminService.put(
                            validShopRequest("실패할 매장"), admin.getId()));
        } finally {
            jdbcTemplate.execute(
                    """
                    ALTER TABLE publishing_outbox
                    DROP CONSTRAINT IF EXISTS ck_publishing_outbox_test_reject_all
                    """);
        }

        assertEquals(0, rowCount("shop_settings"));
        assertEquals(0L, currentRevision());
        assertEquals(0, eventCount());

        shopSettingsAdminService.put(validShopRequest("복구된 매장"), admin.getId());
        assertEquals(1, rowCount("shop_settings"));
        assertEquals(1L, currentRevision());
        assertEquals(1, eventCount());
    }

    @Test
    void should_removeFinalMediaAndRollbackRow_when_revisionAllocationFails() throws Exception {
        jdbcTemplate.update(
                "UPDATE content_revision_state SET content_revision = ? WHERE singleton_key = 1",
                Long.MAX_VALUE);
        try {
            assertThrows(DataAccessException.class, () -> {
                try (var inputStream = resource("/media/synthetic-source.png")) {
                    mediaAdminService.upload(
                            inputStream,
                            "image/png",
                            "publication-rollback.png",
                            admin.getId());
                }
            });

            assertEquals(0, rowCount("media_assets"));
            assertEquals(0, eventCount());
            assertEquals(0L, regularFileCount(mediaRoot().resolve("masters")));
            assertEquals(0L, regularFileCount(mediaRoot().resolve("temp")));
        } finally {
            jdbcTemplate.update(
                    "UPDATE content_revision_state SET content_revision = 0 WHERE singleton_key = 1");
        }
    }

    private GalleryUpdateRequest galleryUpdate(
            String status,
            UUID breedId,
            UUID serviceId,
            UUID coverId,
            String summary,
            Instant publishedAt) {
        return new GalleryUpdateRequest(
                status,
                "라미",
                breedId,
                serviceId,
                coverId,
                null,
                null,
                summary,
                "라미의 미용 완료 사진",
                false,
                100,
                null,
                publishedAt);
    }

    private ShopSettingsRequest validShopRequest(String shopName) {
        return new ShopSettingsRequest(
                shopName,
                "용인 처인구",
                "애견미용",
                "031-123-4567",
                "경기도 용인시 처인구",
                "10:00",
                "19:00",
                null,
                false,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    private ShopSettingsRequest invalidHoursShopRequest() {
        var valid = validShopRequest("잘못된 영업시간");
        return new ShopSettingsRequest(
                valid.shopName(),
                valid.regionLabel(),
                valid.businessType(),
                valid.phone(),
                valid.address(),
                "19:00",
                "10:00",
                valid.closedWeekday(),
                valid.parkingAvailable(),
                valid.parkingNote(),
                valid.heroTitle(),
                valid.heroDescription(),
                valid.groomerName(),
                valid.groomerIntro(),
                valid.reservationNotice(),
                valid.heroImageId(),
                valid.heroImageAltText(),
                valid.groomerImageId(),
                valid.groomerImageAltText(),
                valid.ogImageId(),
                valid.instagramUrl(),
                valid.naverBlogUrl(),
                valid.naverMapUrl(),
                valid.kakaoMapUrl(),
                valid.naverTalktalkUrl(),
                valid.kakaoChannelUrl());
    }

    private UUID insertBreed(String status, String slug) {
        var id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO breeds (
                    id, status, name, slug, description, sort_order,
                    created_at, updated_at, created_by, updated_by
                ) VALUES (?, ?, '테스트 견종', ?, '설명', 100,
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?)
                """,
                id,
                status,
                slug,
                admin.getId(),
                admin.getId());
        return id;
    }

    private UUID insertService(String status, String slug) {
        var id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO services (
                    id, status, name, slug, description, price_text, sort_order,
                    created_at, updated_at, created_by, updated_by
                ) VALUES (?, ?, '테스트 서비스', ?, '설명', '가격', 100,
                          CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?)
                """,
                id,
                status,
                slug,
                admin.getId(),
                admin.getId());
        return id;
    }

    private UUID insertMedia(String status) {
        var id = UUID.randomUUID();
        jdbcTemplate.update(
                """
                INSERT INTO media_assets (
                    id, status, source_content_type, content_type, file_extension,
                    storage_key, source_byte_size, byte_size, width, height, sha256,
                    created_at, updated_at, created_by, updated_by
                ) VALUES (?, ?, 'image/jpeg', 'image/jpeg', 'jpg', ?,
                          100, 100, 4, 3, ?, CURRENT_TIMESTAMP, CURRENT_TIMESTAMP, ?, ?)
                """,
                id,
                status,
                "masters/" + id.toString().substring(0, 2) + "/" + id + ".jpg",
                "a".repeat(64),
                admin.getId(),
                admin.getId());
        return id;
    }

    private void assertImmediate(long revision, String sourceType, UUID sourceId) {
        var rows = jdbcTemplate.queryForList(
                """
                SELECT expected_boundary_at
                FROM publishing_outbox
                WHERE content_revision = ?
                  AND kind = 'CONTENT_CHANGED'
                  AND source_type = ?
                  AND source_id = ?
                """,
                revision,
                sourceType,
                sourceId);
        assertEquals(1, rows.size());
        assertNull(rows.getFirst().get("expected_boundary_at"));
    }

    private void assertScheduled(
            long revision,
            String kind,
            String sourceType,
            UUID sourceId,
            Instant expectedBoundaryAt) {
        var row = jdbcTemplate.queryForObject(
                """
                SELECT available_at, expected_boundary_at
                FROM publishing_outbox
                WHERE content_revision = ?
                  AND kind = ?
                  AND source_type = ?
                  AND source_id = ?
                """,
                (resultSet, rowNumber) -> new BoundaryTimes(
                        resultSet.getObject("available_at", OffsetDateTime.class),
                        resultSet.getObject("expected_boundary_at", OffsetDateTime.class)),
                revision,
                kind,
                sourceType,
                sourceId);
        assertNotNull(row);
        var expected = expectedBoundaryAt.atOffset(ZoneOffset.UTC);
        assertEquals(expected, row.availableAt());
        assertEquals(expected, row.expectedBoundaryAt());
    }

    private long currentRevision() {
        return jdbcTemplate.queryForObject(
                "SELECT content_revision FROM content_revision_state WHERE singleton_key = 1",
                Long.class);
    }

    private int eventCount() {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM publishing_outbox", Integer.class);
    }

    private int eventCountAtRevision(long revision) {
        return jdbcTemplate.queryForObject(
                "SELECT COUNT(*) FROM publishing_outbox WHERE content_revision = ?",
                Integer.class,
                revision);
    }

    private int rowCount(String table) {
        return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
    }

    private InputStream resource(String path) {
        var inputStream = getClass().getResourceAsStream(path);
        assertNotNull(inputStream, path);
        return inputStream;
    }

    private Path mediaRoot() {
        return Path.of(mediaProperties.root()).toAbsolutePath().normalize();
    }

    private long regularFileCount(Path root) throws Exception {
        if (!Files.exists(root)) {
            return 0;
        }
        try (var paths = Files.walk(root)) {
            return paths.filter(Files::isRegularFile).count();
        }
    }

    private void clearFixtures() throws Exception {
        jdbcTemplate.update("DELETE FROM publishing_outbox");
        jdbcTemplate.update("DELETE FROM gallery_items");
        jdbcTemplate.update("DELETE FROM shop_settings");
        jdbcTemplate.update("DELETE FROM notices");
        jdbcTemplate.update("DELETE FROM media_assets");
        jdbcTemplate.update("DELETE FROM services");
        jdbcTemplate.update("DELETE FROM breeds");
        jdbcTemplate.update(
                "UPDATE content_revision_state SET content_revision = 0 WHERE singleton_key = 1");
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

    private record BoundaryTimes(
            OffsetDateTime availableAt, OffsetDateTime expectedBoundaryAt) {}
}
