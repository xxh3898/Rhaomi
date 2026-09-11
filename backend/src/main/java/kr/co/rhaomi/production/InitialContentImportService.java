package kr.co.rhaomi.production;

import jakarta.validation.Validator;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.DigestInputStream;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import kr.co.rhaomi.backend.admin.AdminUserRepository;
import kr.co.rhaomi.backend.breed.BreedAdminService;
import kr.co.rhaomi.backend.breed.BreedCreateRequest;
import kr.co.rhaomi.backend.breed.BreedRepository;
import kr.co.rhaomi.backend.gallery.GalleryAdminService;
import kr.co.rhaomi.backend.gallery.GalleryRepository;
import kr.co.rhaomi.backend.gallery.GalleryUpdateRequest;
import kr.co.rhaomi.backend.media.MediaAdminService;
import kr.co.rhaomi.backend.media.MediaAssetRepository;
import kr.co.rhaomi.backend.media.MediaProperties;
import kr.co.rhaomi.backend.notice.NoticeAdminService;
import kr.co.rhaomi.backend.notice.NoticeCreateRequest;
import kr.co.rhaomi.backend.notice.NoticeRepository;
import kr.co.rhaomi.backend.publication.PublicationEventKind;
import kr.co.rhaomi.backend.publication.PublicationRecorder;
import kr.co.rhaomi.backend.publication.PublicationSourceType;
import kr.co.rhaomi.backend.publication.ScheduledPublicationEvent;
import kr.co.rhaomi.backend.publication.ScheduledPublicationSourceEvent;
import kr.co.rhaomi.backend.service.GroomingServiceRepository;
import kr.co.rhaomi.backend.service.ServiceAdminService;
import kr.co.rhaomi.backend.service.ServiceCreateRequest;
import kr.co.rhaomi.backend.shop.ShopSettingsAdminService;
import kr.co.rhaomi.backend.shop.ShopSettingsBuildValidator;
import kr.co.rhaomi.backend.shop.ShopSettingsRepository;
import kr.co.rhaomi.backend.shop.ShopSettingsRequest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.annotation.Transactional;

class InitialContentImportService {

    private final AdminUserRepository adminUserRepository;
    private final BreedRepository breedRepository;
    private final GroomingServiceRepository serviceRepository;
    private final NoticeRepository noticeRepository;
    private final GalleryRepository galleryRepository;
    private final ShopSettingsRepository shopSettingsRepository;
    private final MediaAssetRepository mediaAssetRepository;
    private final MediaAdminService mediaAdminService;
    private final BreedAdminService breedAdminService;
    private final ServiceAdminService serviceAdminService;
    private final NoticeAdminService noticeAdminService;
    private final GalleryAdminService galleryAdminService;
    private final ShopSettingsAdminService shopSettingsAdminService;
    private final PublicationRecorder publicationRecorder;
    private final InitialContentImportLock importLock;
    private final InitialContentImportCheckpoint checkpoint;
    private final Validator validator;
    private final JdbcTemplate jdbcTemplate;
    private final Clock clock;
    private final Path mediaRoot;

    InitialContentImportService(
            AdminUserRepository adminUserRepository,
            BreedRepository breedRepository,
            GroomingServiceRepository serviceRepository,
            NoticeRepository noticeRepository,
            GalleryRepository galleryRepository,
            ShopSettingsRepository shopSettingsRepository,
            MediaAssetRepository mediaAssetRepository,
            MediaAdminService mediaAdminService,
            BreedAdminService breedAdminService,
            ServiceAdminService serviceAdminService,
            NoticeAdminService noticeAdminService,
            GalleryAdminService galleryAdminService,
            ShopSettingsAdminService shopSettingsAdminService,
            PublicationRecorder publicationRecorder,
            InitialContentImportLock importLock,
            InitialContentImportCheckpoint checkpoint,
            Validator validator,
            JdbcTemplate jdbcTemplate,
            Clock clock,
            MediaProperties mediaProperties) {
        this.adminUserRepository = adminUserRepository;
        this.breedRepository = breedRepository;
        this.serviceRepository = serviceRepository;
        this.noticeRepository = noticeRepository;
        this.galleryRepository = galleryRepository;
        this.shopSettingsRepository = shopSettingsRepository;
        this.mediaAssetRepository = mediaAssetRepository;
        this.mediaAdminService = mediaAdminService;
        this.breedAdminService = breedAdminService;
        this.serviceAdminService = serviceAdminService;
        this.noticeAdminService = noticeAdminService;
        this.galleryAdminService = galleryAdminService;
        this.shopSettingsAdminService = shopSettingsAdminService;
        this.publicationRecorder = publicationRecorder;
        this.importLock = importLock;
        this.checkpoint = checkpoint;
        this.validator = validator;
        this.jdbcTemplate = jdbcTemplate;
        this.clock = clock;
        this.mediaRoot = Path.of(mediaProperties.root()).toAbsolutePath().normalize();
    }

    @Transactional
    InitialContentImportResult importBundle(InitialContentBundle bundle) {
        if (bundle == null) {
            fail("INITIAL_CONTENT_BUNDLE_INVALID");
        }
        importLock.acquire();
        var actorId = requirePristineState();
        var generatedAt = Instant.now(clock).truncatedTo(ChronoUnit.MICROS);
        validateBundleRequests(bundle, generatedAt);

        var mediaIds = importMedia(bundle, actorId);
        var breedIds = importBreeds(bundle, actorId);
        var serviceIds = importServices(bundle, actorId);
        var scheduledEvents = importNotices(bundle, actorId, generatedAt);
        importGalleryItems(bundle, actorId, generatedAt, breedIds, serviceIds, mediaIds);
        var shopId = importShop(bundle.shopSettings(), actorId, mediaIds);

        var settings = shopSettingsRepository
                .findBySingletonKeyTrue()
                .orElseThrow(() -> new InitialContentImportException(
                        "INITIAL_CONTENT_REQUIRED_DATA_MISSING"));
        if (!ShopSettingsBuildValidator.isValid(settings)) {
            fail("INITIAL_CONTENT_REQUIRED_DATA_INVALID");
        }

        var contentRevision = publicationRecorder.recordInitialPublication(
                PublicationSourceType.SHOP_SETTINGS, shopId, scheduledEvents);
        checkpoint.afterWritesBeforeCommit();
        return new InitialContentImportResult(
                bundle.manifestSha256(),
                contentRevision,
                bundle.media().size(),
                bundle.breeds().size(),
                bundle.services().size(),
                bundle.notices().size(),
                bundle.galleryItems().size());
    }

    private UUID requirePristineState() {
        var administrators = adminUserRepository.findAll();
        if (administrators.size() != 1 || !administrators.getFirst().isActive()) {
            fail("INITIAL_CONTENT_ADMIN_AUTHORITY_INVALID");
        }
        if (breedRepository.count() != 0
                || serviceRepository.count() != 0
                || noticeRepository.count() != 0
                || galleryRepository.count() != 0
                || shopSettingsRepository.count() != 0
                || mediaAssetRepository.count() != 0
                || queryLong("SELECT COUNT(*) FROM publishing_outbox") != 0
                || queryLong("SELECT content_revision FROM content_revision_state WHERE singleton_key = 1") != 0
                || queryLong("SELECT publish_generation FROM publish_generation_state WHERE singleton_key = 1") != 0
                || hasExistingMediaState()) {
            fail("INITIAL_CONTENT_ALREADY_PROVISIONED");
        }
        return administrators.getFirst().getId();
    }

    private void validateBundleRequests(InitialContentBundle bundle, Instant generatedAt) {
        var placeholderMediaIds = new HashMap<String, UUID>();
        bundle.media().forEach(media -> placeholderMediaIds.put(media.key(), UUID.randomUUID()));
        validate(shopRequest(bundle.shopSettings(), placeholderMediaIds));
        for (var breed : bundle.breeds()) {
            validate(new BreedCreateRequest(
                    breed.name(), breed.slug(), breed.description(), breed.sortOrder()));
        }
        for (var service : bundle.services()) {
            validate(new ServiceCreateRequest(
                    service.name(),
                    service.slug(),
                    service.description(),
                    service.priceText(),
                    service.sortOrder()));
        }
        for (var notice : bundle.notices()) {
            if (notice.publishedAt().isAfter(generatedAt)
                    || (notice.expiresAt() != null && !notice.expiresAt().isAfter(generatedAt))) {
                fail("INITIAL_CONTENT_FIRST_PUBLICATION_INVALID");
            }
            validate(new NoticeCreateRequest(
                    notice.title(),
                    notice.slug(),
                    notice.summary(),
                    notice.bodyMarkdown(),
                    notice.pinned(),
                    notice.publishedAt(),
                    notice.expiresAt()));
        }
        for (var item : bundle.galleryItems()) {
            if (item.publishedAt().isAfter(generatedAt)) {
                fail("INITIAL_CONTENT_FIRST_PUBLICATION_INVALID");
            }
            validate(new GalleryUpdateRequest(
                    "published",
                    item.dogName(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    null,
                    null,
                    item.summary(),
                    item.altText(),
                    item.featured(),
                    item.sortOrder(),
                    item.performedAt(),
                    item.publishedAt()));
        }
    }

    private Map<String, UUID> importMedia(InitialContentBundle bundle, UUID actorId) {
        var ids = new HashMap<String, UUID>();
        for (var media : bundle.media()) {
            var digest = sha256Digest();
            try (var input = new DigestInputStream(Files.newInputStream(media.path()), digest)) {
                var response = mediaAdminService.uploadForInitialImport(
                        input,
                        media.contentType(),
                        media.path().getFileName().toString(),
                        actorId);
                if (response.sourceByteSize() != media.byteSize()
                        || !HexFormat.of().formatHex(digest.digest()).equals(media.sha256())) {
                    fail("INITIAL_CONTENT_CHECKSUM_INVALID");
                }
                ids.put(media.key(), response.id());
            } catch (IOException exception) {
                fail("INITIAL_CONTENT_MEDIA_READ_FAILED");
            }
        }
        return Map.copyOf(ids);
    }

    private Map<String, UUID> importBreeds(InitialContentBundle bundle, UUID actorId) {
        var ids = new HashMap<String, UUID>();
        for (var breed : bundle.breeds()) {
            var response = breedAdminService.createPublishedForInitialImport(
                    new BreedCreateRequest(
                            breed.name(), breed.slug(), breed.description(), breed.sortOrder()),
                    actorId);
            ids.put(breed.key(), response.id());
        }
        return Map.copyOf(ids);
    }

    private Map<String, UUID> importServices(InitialContentBundle bundle, UUID actorId) {
        var ids = new HashMap<String, UUID>();
        for (var service : bundle.services()) {
            var response = serviceAdminService.createPublishedForInitialImport(
                    new ServiceCreateRequest(
                            service.name(),
                            service.slug(),
                            service.description(),
                            service.priceText(),
                            service.sortOrder()),
                    actorId);
            ids.put(service.key(), response.id());
        }
        return Map.copyOf(ids);
    }

    private List<ScheduledPublicationSourceEvent> importNotices(
            InitialContentBundle bundle, UUID actorId, Instant generatedAt) {
        var scheduledEvents = new ArrayList<ScheduledPublicationSourceEvent>();
        for (var notice : bundle.notices()) {
            var response = noticeAdminService.createPublishedForInitialImport(
                    new NoticeCreateRequest(
                            notice.title(),
                            notice.slug(),
                            notice.summary(),
                            notice.bodyMarkdown(),
                            notice.pinned(),
                            notice.publishedAt(),
                            notice.expiresAt()),
                    actorId);
            if (response.expiresAt() != null && response.expiresAt().isAfter(generatedAt)) {
                scheduledEvents.add(new ScheduledPublicationSourceEvent(
                        PublicationSourceType.NOTICE,
                        response.id(),
                        new ScheduledPublicationEvent(
                                PublicationEventKind.NOTICE_EXPIRES_AT_DUE,
                                response.expiresAt())));
            }
        }
        return List.copyOf(scheduledEvents);
    }

    private void importGalleryItems(
            InitialContentBundle bundle,
            UUID actorId,
            Instant generatedAt,
            Map<String, UUID> breedIds,
            Map<String, UUID> serviceIds,
            Map<String, UUID> mediaIds) {
        for (var item : bundle.galleryItems()) {
            if (item.publishedAt().isAfter(generatedAt)) {
                fail("INITIAL_CONTENT_FIRST_PUBLICATION_INVALID");
            }
            galleryAdminService.createPublishedForInitialImport(
                    new GalleryUpdateRequest(
                            "published",
                            item.dogName(),
                            requiredId(breedIds, item.breedKey()),
                            requiredId(serviceIds, item.primaryServiceKey()),
                            requiredId(mediaIds, item.coverMediaKey()),
                            optionalId(mediaIds, item.beforeMediaKey()),
                            optionalId(mediaIds, item.afterMediaKey()),
                            item.summary(),
                            item.altText(),
                            item.featured(),
                            item.sortOrder(),
                            item.performedAt(),
                            item.publishedAt()),
                    actorId);
        }
    }

    private UUID importShop(
            InitialContentBundle.Shop shop, UUID actorId, Map<String, UUID> mediaIds) {
        return shopSettingsAdminService.createForInitialImport(
                shopRequest(shop, mediaIds), actorId);
    }

    private ShopSettingsRequest shopRequest(
            InitialContentBundle.Shop shop, Map<String, UUID> mediaIds) {
        return new ShopSettingsRequest(
                shop.shopName(),
                shop.regionLabel(),
                shop.businessType(),
                shop.phone(),
                shop.address(),
                shop.openingTime(),
                shop.closingTime(),
                shop.closedWeekday(),
                shop.parkingAvailable(),
                shop.parkingNote(),
                shop.heroTitle(),
                shop.heroDescription(),
                shop.groomerName(),
                shop.groomerIntro(),
                shop.reservationNotice(),
                optionalId(mediaIds, shop.heroMediaKey()),
                shop.heroImageAltText(),
                optionalId(mediaIds, shop.groomerMediaKey()),
                shop.groomerImageAltText(),
                optionalId(mediaIds, shop.ogMediaKey()),
                shop.instagramUrl(),
                shop.naverBlogUrl(),
                shop.naverMapUrl(),
                shop.kakaoMapUrl(),
                shop.naverTalktalkUrl(),
                shop.kakaoChannelUrl());
    }

    private UUID requiredId(Map<String, UUID> values, String key) {
        var id = values.get(key);
        if (id == null) {
            fail("INITIAL_CONTENT_RELATION_INVALID");
        }
        return id;
    }

    private UUID optionalId(Map<String, UUID> values, String key) {
        return key == null ? null : requiredId(values, key);
    }

    private void validate(Object value) {
        if (!validator.validate(value).isEmpty()) {
            fail("INITIAL_CONTENT_REQUIRED_DATA_INVALID");
        }
    }

    private long queryLong(String sql) {
        var value = jdbcTemplate.queryForObject(sql, Long.class);
        if (value == null) {
            fail("INITIAL_CONTENT_STATE_INVALID");
        }
        return value;
    }

    private boolean hasExistingMediaState() {
        try (var paths = Files.walk(mediaRoot)) {
            return paths.anyMatch(path -> {
                if (path.equals(mediaRoot)) {
                    return false;
                }
                if (Files.isSymbolicLink(path)) {
                    return true;
                }
                if (Files.isDirectory(path, LinkOption.NOFOLLOW_LINKS)) {
                    return !isCanonicalEmptyDirectory(path);
                }
                return true;
            });
        } catch (IOException exception) {
            fail("INITIAL_CONTENT_STATE_INVALID");
            return true;
        }
    }

    private boolean isCanonicalEmptyDirectory(Path path) {
        return path.equals(mediaRoot.resolve("temp"))
                || path.equals(mediaRoot.resolve("masters"));
    }

    private MessageDigest sha256Digest() {
        try {
            return MessageDigest.getInstance("SHA-256");
        } catch (NoSuchAlgorithmException exception) {
            throw new IllegalStateException("SHA-256 unavailable");
        }
    }

    private void fail(String code) {
        throw new InitialContentImportException(code);
    }
}
